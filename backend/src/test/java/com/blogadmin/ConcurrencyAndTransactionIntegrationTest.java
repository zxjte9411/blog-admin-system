package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;

import com.blogadmin.identity.application.AccountService;
import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.application.OpaqueToken;
import com.blogadmin.identity.domain.emailchange.EmailChangeToken;
import com.blogadmin.identity.domain.emailchange.EmailChangeTokenRepository;
import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordResetToken;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.publishing.application.ArticleCleanupExecutor;
import com.blogadmin.publishing.application.ArticleService;
import com.blogadmin.publishing.domain.article.Article;
import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.article.PublicationStatus;
import com.blogadmin.publishing.domain.tag.Tag;
import com.blogadmin.publishing.domain.tag.TagRepository;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class ConcurrencyAndTransactionIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired RefreshSessionRepository sessions;
  @Autowired PasswordResetTokenRepository resetTokens;
  @Autowired EmailChangeTokenRepository emailTokens;
  @Autowired InvitationRepository invitations;
  @Autowired ArticleRepository articles;
  @Autowired TagRepository tags;
  @Autowired AccountService accountService;
  @Autowired AdminUserService adminUserService;
  @Autowired ArticleService articleService;
  @Autowired ArticleCleanupExecutor articleCleanupExecutor;
  @Autowired EntityManagerFactory entityManagerFactory;
  @MockitoBean JavaMailSender mail;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    r.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @BeforeEach
  void clear() {
    articles.deleteAll();
    tags.deleteAll();
    sessions.deleteAll();
    resetTokens.deleteAll();
    emailTokens.deleteAll();
    invitations.deleteAll();
    users.deleteAll();
    reset(mail);
  }

  @Test
  @Timeout(15)
  void testConcurrentEmailChangeRequestAndConfirmNoDeadlock() throws Exception {
    User user =
        new User(
            UUID.randomUUID(),
            "alice@example.com",
            "alice@example.com",
            "Alice",
            passwords.encode("Password123!"),
            "zh-TW");
    user.verify(Instant.now());
    users.save(user);

    OpaqueToken.Issued token = OpaqueToken.generate();
    emailTokens.save(
        new EmailChangeToken(
            UUID.randomUUID(),
            user.getId(),
            "alice.new@example.com",
            token.digest(),
            Instant.now().plusSeconds(3600)));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<Void> requestTask =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          accountService.requestEmail(user, "alice.new@example.com");
          return null;
        };

    Callable<Void> confirmTask =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            accountService.confirmEmail(token.value());
          } catch (Exception ignored) {
            // confirm may fail if request invalidates the token first, but must not deadlock
          }
          return null;
        };

    Future<Void> f1 = executor.submit(requestTask);
    Future<Void> f2 = executor.submit(confirmTask);

    f1.get(10, TimeUnit.SECONDS);
    f2.get(10, TimeUnit.SECONDS);
    executor.shutdown();

    // Verify DB invariant: email_change_tokens has at most 1 active token
    List<EmailChangeToken> active = emailTokens.findByUserIdAndUsedAtIsNull(user.getId());
    assertThat(active.size()).isLessThanOrEqualTo(1);
  }

  @Test
  @Timeout(15)
  void testConcurrentPasswordResetIssuance() throws Exception {
    User user =
        new User(
            UUID.randomUUID(),
            "bob@example.com",
            "bob@example.com",
            "Bob",
            passwords.encode("Password123!"),
            "zh-TW");
    user.verify(Instant.now());
    users.save(user);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<Void> task1 =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          accountService.requestReset("bob@example.com");
          return null;
        };

    Callable<Void> task2 =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          accountService.requestReset("bob@example.com");
          return null;
        };

    Future<Void> f1 = executor.submit(task1);
    Future<Void> f2 = executor.submit(task2);

    f1.get(10, TimeUnit.SECONDS);
    f2.get(10, TimeUnit.SECONDS);
    executor.shutdown();

    List<PasswordResetToken> active = resetTokens.findByUserIdAndUsedAtIsNull(user.getId());
    assertThat(active).hasSize(1);
  }

  @Test
  @Timeout(15)
  void testConcurrentInvitationRedeemSingleUse() throws Exception {
    OpaqueToken.Issued token = OpaqueToken.generate();
    Invitation invitation =
        new Invitation(
            UUID.randomUUID(),
            "invited@example.com",
            token.digest(),
            Instant.now().plusSeconds(3600));
    invitations.save(invitation);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<Boolean> redeemTask1 =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            adminUserService.redeem(token.value(), "Invited User 1", "Password123!", "zh-TW");
            return true;
          } catch (Exception e) {
            return false;
          }
        };

    Callable<Boolean> redeemTask2 =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            adminUserService.redeem(token.value(), "Invited User 2", "Password123!", "zh-TW");
            return true;
          } catch (Exception e) {
            return false;
          }
        };

    Future<Boolean> f1 = executor.submit(redeemTask1);
    Future<Boolean> f2 = executor.submit(redeemTask2);

    boolean r1 = f1.get(10, TimeUnit.SECONDS);
    boolean r2 = f2.get(10, TimeUnit.SECONDS);
    executor.shutdown();

    // Exactly one redeem must succeed
    assertThat(r1 ^ r2).isTrue();

    // Exactly one user must be created
    assertThat(users.findAll()).hasSize(1);

    // Invitation must be marked used
    Invitation updated = invitations.findById(invitation.getId()).orElseThrow();
    assertThat(updated.getUsedAt()).isNotNull();
  }

  @Test
  @Timeout(15)
  void testConcurrentTagGetOrCreateNoDuplicateAndNoException() throws Exception {
    User author =
        new User(
            UUID.randomUUID(),
            "author@example.com",
            "author@example.com",
            "Author",
            passwords.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    users.save(author);

    ExecutorService executor = Executors.newFixedThreadPool(4);
    CyclicBarrier barrier = new CyclicBarrier(4);

    List<Callable<ArticleService.ArticleView>> tasks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      final int idx = i;
      tasks.add(
          () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return articleService.create(
                author,
                "Article " + idx,
                "Content " + idx,
                PublicationStatus.PUBLISHED,
                null,
                Set.of("ConcurrentTag", "SharedTag"));
          });
    }

    List<Future<ArticleService.ArticleView>> futures = executor.invokeAll(tasks);
    for (Future<ArticleService.ArticleView> f : futures) {
      ArticleService.ArticleView view = f.get(10, TimeUnit.SECONDS);
      assertThat(view).isNotNull();
    }
    executor.shutdown();

    // Verify DB invariant: exactly 2 tags exist
    List<Tag> allTags = tags.findAll();
    assertThat(allTags).hasSize(2);

    // Verify all 4 articles are created and linked to the same canonical tags
    var articlePage = articleService.list(author, "", null, null, PageRequest.of(0, 10));
    assertThat(articlePage.getContent()).hasSize(4);
    for (ArticleService.ArticleView a : articlePage.getContent()) {
      assertThat(a.tagNames()).hasSize(2);
    }
  }

  @Test
  @Timeout(15)
  void testConcurrentOrphanTagReuseVsCleanup() throws Exception {
    User author =
        new User(
            UUID.randomUUID(),
            "reusetest@example.com",
            "reusetest@example.com",
            "Reuse Author",
            passwords.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    users.save(author);

    // Create an existing unreferenced (orphan) tag
    tags.saveAndFlush(new Tag(UUID.randomUUID(), "OrphanReuseTag"));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);

    // Thread A: create article reusing the orphan tag
    Callable<ArticleService.ArticleView> createTask =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          return articleService.create(
              author,
              "Reusing Article",
              "Content",
              PublicationStatus.PUBLISHED,
              null,
              Set.of("OrphanReuseTag"));
        };

    // Thread B: maintenance cleanup runs concurrently
    Callable<Void> cleanupTask =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          articleCleanupExecutor.cleanup();
          return null;
        };

    Future<ArticleService.ArticleView> f1 = executor.submit(createTask);
    Future<Void> f2 = executor.submit(cleanupTask);

    ArticleService.ArticleView created = f1.get(10, TimeUnit.SECONDS);
    f2.get(10, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(created).isNotNull();
    assertThat(created.tagNames()).contains("OrphanReuseTag");

    // The tag must still exist and be properly linked
    Tag existingTag = tags.findByNameIgnoreCase("OrphanReuseTag").orElseThrow();
    assertThat(articles.countByTagsId(existingTag.getId())).isEqualTo(1);
  }

  @Test
  void testArticleQueryBatchFetchingNoNPlusOne() {
    User author =
        new User(
            UUID.randomUUID(),
            "batchauthor@example.com",
            "batchauthor@example.com",
            "Batch Author",
            passwords.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    users.save(author);

    for (int i = 0; i < 20; i++) {
      articleService.create(
          author,
          "Batch Article " + i,
          "Batch Content " + i,
          PublicationStatus.PUBLISHED,
          null,
          Set.of("TagA" + (i % 5), "TagB" + (i % 3)));
    }

    SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    Statistics statistics = sessionFactory.getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    // Execute list with page size 20 and map to view
    var page = articleService.list(author, "", null, null, PageRequest.of(0, 20));
    assertThat(page.getContent()).hasSize(20);

    // Total query count must be constant (1 article query + 1 count query + 1 tag batch query = 3
    // queries)
    // NOT 1 + 20 queries!
    long queryCount = statistics.getPrepareStatementCount();
    assertThat(queryCount).isLessThanOrEqualTo(4);
  }

  @Test
  void testStartupCleanupTransactionBoundary() {
    User author =
        new User(
            UUID.randomUUID(),
            "cleanauthor@example.com",
            "cleanauthor@example.com",
            "Clean Author",
            passwords.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    users.save(author);

    ArticleService.ArticleView view =
        articleService.create(
            author,
            "Old Article",
            "Old Content",
            PublicationStatus.PUBLISHED,
            null,
            Set.of("OrphanTag1", "OrphanTag2"));

    // Set deletedAt to 35 days ago
    Article article = articles.findById(view.id()).orElseThrow();
    article.delete();
    // Simulate expired deletedAt
    ReflectionTestUtils.setField(article, "deletedAt", Instant.now().minus(35, ChronoUnit.DAYS));
    articles.saveAndFlush(article);

    // Run cleanup executor
    articleCleanupExecutor.cleanup();

    // Article and orphan tags should be cleaned up
    assertThat(articles.findById(view.id())).isEmpty();
    assertThat(tags.findAll()).isEmpty();
  }

  @Test
  void testStartupCleanupRollbackOnFailure() {
    User author =
        new User(
            UUID.randomUUID(),
            "rollbackauthor@example.com",
            "rollbackauthor@example.com",
            "Rollback Author",
            passwords.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    users.save(author);

    ArticleService.ArticleView view =
        articleService.create(
            author,
            "Expired Article To Rollback",
            "Content",
            PublicationStatus.PUBLISHED,
            null,
            Set.of("RollbackTag1"));

    Article article = articles.findById(view.id()).orElseThrow();
    article.delete();
    ReflectionTestUtils.setField(article, "deletedAt", Instant.now().minus(35, ChronoUnit.DAYS));
    articles.saveAndFlush(article);

    // Inject failure into TagRepository during cleanup via standard dynamic proxy
    TagRepository failingTags =
        (TagRepository)
            Proxy.newProxyInstance(
                TagRepository.class.getClassLoader(),
                new Class<?>[] {TagRepository.class},
                (proxy, method, methodArgs) -> {
                  if ("findCandidateOrphanTagNames".equals(method.getName())) {
                    throw new IllegalStateException("Simulated crash mid-cleanup transaction");
                  }
                  return method.invoke(tags, methodArgs);
                });

    // Unwrap CGLIB/JDK proxy to access target object
    ArticleCleanupExecutor targetExecutor = AopTestUtils.getTargetObject(articleCleanupExecutor);
    TagRepository originalTags =
        (TagRepository) ReflectionTestUtils.getField(targetExecutor, "tags");
    ReflectionTestUtils.setField(targetExecutor, "tags", failingTags);

    try {
      assertThatThrownBy(() -> articleCleanupExecutor.cleanup())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Simulated crash mid-cleanup transaction");
    } finally {
      ReflectionTestUtils.setField(targetExecutor, "tags", originalTags);
    }

    // Verify atomic ROLLBACK: Article and its tags MUST still exist in PostgreSQL
    Article rolledBackArticle = articles.findById(view.id()).orElse(null);
    assertThat(rolledBackArticle).isNotNull();
    assertThat(rolledBackArticle.getDeletedAt()).isNotNull();
    assertThat(tags.findByNameIgnoreCase("RollbackTag1")).isPresent();
  }

  @Test
  @Timeout(15)
  void testMultiTagDeadlockFreeUnderCollationAndUnicode() throws Exception {
    User author =
        new User(
            UUID.randomUUID(),
            "unicodetest@example.com",
            "unicodetest@example.com",
            "Unicode Author",
            passwords.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    users.save(author);

    // Pre-create orphan tags in DB that are initially unreferenced by any article
    Tag tagCafe = tags.saveAndFlush(new Tag(UUID.randomUUID(), "café"));
    Tag tagResume = tags.saveAndFlush(new Tag(UUID.randomUUID(), "résumé"));
    Tag tagUber = tags.saveAndFlush(new Tag(UUID.randomUUID(), "Über"));
    Tag tagNlp = tags.saveAndFlush(new Tag(UUID.randomUUID(), "自然語言處理"));

    // Canonical keys sorted deterministically by Java String natural ordering (UTF-16 lexical
    // ordering):
    // "café" -> "résumé" -> "über" -> "自然語言處理"
    Set<String> tagSet1 = Set.of("café", "résumé", "Über", "自然語言處理");
    Set<String> tagSet2 = Set.of("CAFÉ", "RÉSUMÉ", "über", "架構設計");

    CountDownLatch cleanupDiscoveredCandidates = new CountDownLatch(1);
    CountDownLatch articleStartedLocking = new CountDownLatch(1);
    CountDownLatch cleanupAttemptedFirstLock = new CountDownLatch(1);
    AtomicReference<List<String>> discoveredCandidatesRef = new AtomicReference<>();

    ArticleCleanupExecutor targetExecutor = AopTestUtils.getTargetObject(articleCleanupExecutor);
    TagRepository originalCleanupTags =
        (TagRepository) ReflectionTestUtils.getField(targetExecutor, "tags");

    ArticleService targetArticleService = AopTestUtils.getTargetObject(articleService);
    TagRepository originalArticleTags =
        (TagRepository) ReflectionTestUtils.getField(targetArticleService, "tags");

    TagRepository proxyCleanupTags =
        (TagRepository)
            Proxy.newProxyInstance(
                TagRepository.class.getClassLoader(),
                new Class<?>[] {TagRepository.class},
                (proxy, method, args) -> {
                  if ("findCandidateOrphanTagNames".equals(method.getName())) {
                    @SuppressWarnings("unchecked")
                    List<String> result = (List<String>) method.invoke(originalCleanupTags, args);
                    discoveredCandidatesRef.set(result);
                    cleanupDiscoveredCandidates.countDown();
                    // Wait until article thread starts acquiring the first lock
                    articleStartedLocking.await(5, TimeUnit.SECONDS);
                    return result;
                  }
                  if ("lockNormalizedName".equals(method.getName()) && "café".equals(args[0])) {
                    cleanupAttemptedFirstLock.countDown();
                  }
                  return method.invoke(originalCleanupTags, args);
                });

    TagRepository proxyArticleTags =
        (TagRepository)
            Proxy.newProxyInstance(
                TagRepository.class.getClassLoader(),
                new Class<?>[] {TagRepository.class},
                (proxy, method, args) -> {
                  if ("lockNormalizedName".equals(method.getName()) && "café".equals(args[0])) {
                    // Acquire first lock in article transaction
                    Object result = method.invoke(originalArticleTags, args);
                    articleStartedLocking.countDown();
                    // Ensure cleanup thread reaches and attempts the first lock concurrently
                    cleanupAttemptedFirstLock.await(5, TimeUnit.SECONDS);
                    return result;
                  }
                  return method.invoke(originalArticleTags, args);
                });

    ReflectionTestUtils.setField(targetExecutor, "tags", proxyCleanupTags);
    ReflectionTestUtils.setField(targetArticleService, "tags", proxyArticleTags);

    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      // Thread 1: creates article reusing orphan tags (canonical order: café -> résumé -> über ->
      // 自然語言處理)
      Callable<ArticleService.ArticleView> t1 =
          () -> {
            cleanupDiscoveredCandidates.await(5, TimeUnit.SECONDS);
            return articleService.create(
                author,
                "Article Concurrent 1",
                "Content 1",
                PublicationStatus.PUBLISHED,
                null,
                tagSet1);
          };

      // Thread 2: creates article with case variants and an additional tag concurrently
      Callable<ArticleService.ArticleView> t2 =
          () -> {
            cleanupDiscoveredCandidates.await(5, TimeUnit.SECONDS);
            return articleService.create(
                author,
                "Article Concurrent 2",
                "Content 2",
                PublicationStatus.PUBLISHED,
                null,
                tagSet2);
          };

      // Thread 3: maintenance cleanup processes orphan tag candidates
      Callable<Void> t3 =
          () -> {
            articleCleanupExecutor.cleanup();
            return null;
          };

      Future<ArticleService.ArticleView> f1 = executor.submit(t1);
      Future<ArticleService.ArticleView> f2 = executor.submit(t2);
      Future<Void> f3 = executor.submit(t3);

      ArticleService.ArticleView r1 = f1.get(10, TimeUnit.SECONDS);
      ArticleService.ArticleView r2 = f2.get(10, TimeUnit.SECONDS);
      f3.get(10, TimeUnit.SECONDS);

      // 1. Verify cleanup candidate discovery actually saw the orphan tags
      assertThat(discoveredCandidatesRef.get()).contains("café", "résumé", "Über", "自然語言處理");

      // 2. Verify articles were created successfully without deadlock
      assertThat(r1).isNotNull();
      assertThat(r2).isNotNull();
      assertThat(r1.tagNames()).containsExactlyInAnyOrder("café", "résumé", "Über", "自然語言處理");
      assertThat(r2.tagNames()).contains("架構設計");

      // 3. Verify reused tags were not deleted by cleanup
      Tag persistedCafe = tags.findByNameIgnoreCase("café").orElseThrow();
      Tag persistedResume = tags.findByNameIgnoreCase("résumé").orElseThrow();
      Tag persistedUber = tags.findByNameIgnoreCase("über").orElseThrow();
      Tag persistedNlp = tags.findByNameIgnoreCase("自然語言處理").orElseThrow();

      assertThat(persistedCafe.getId()).isEqualTo(tagCafe.getId());
      assertThat(persistedResume.getId()).isEqualTo(tagResume.getId());
      assertThat(persistedUber.getId()).isEqualTo(tagUber.getId());
      assertThat(persistedNlp.getId()).isEqualTo(tagNlp.getId());

      // 4. Verify no duplicate tag entities created for case-insensitive variants (total distinct
      // tags = 5)
      List<Tag> allTags = tags.findAll();
      assertThat(allTags).hasSize(5);

      // 5. Verify article_tags relationships are accurately linked in DB
      assertThat(articles.countByTagsId(persistedCafe.getId())).isEqualTo(2);
      assertThat(articles.countByTagsId(persistedResume.getId())).isEqualTo(2);
      assertThat(articles.countByTagsId(persistedUber.getId())).isEqualTo(2);
      assertThat(articles.countByTagsId(persistedNlp.getId())).isEqualTo(1);

      Tag persistedArch = tags.findByNameIgnoreCase("架構設計").orElseThrow();
      assertThat(articles.countByTagsId(persistedArch.getId())).isEqualTo(1);
    } finally {
      executor.shutdown();
      ReflectionTestUtils.setField(targetExecutor, "tags", originalCleanupTags);
      ReflectionTestUtils.setField(targetArticleService, "tags", originalArticleTags);
    }
  }
}
