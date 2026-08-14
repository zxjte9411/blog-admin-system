package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
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
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

class ConcurrencyAndTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private UserRepository userRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private EmailChangeTokenRepository emailChangeTokenRepository;
  @Autowired private InvitationRepository invitationRepository;
  @Autowired private ArticleRepository articleRepository;
  @MockitoSpyBean private TagRepository tagRepository;
  @Autowired private AccountService accountService;
  @Autowired private AdminUserService adminUserService;
  @Autowired private ArticleService articleService;
  @Autowired private ArticleCleanupExecutor articleCleanupExecutor;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private JavaMailSender mailSender;

  @BeforeEach
  void clear() {
    resetDatabase(jdbcTemplate);
    reset(mailSender);
    reset(tagRepository);
  }

  @Test
  @Timeout(15)
  void concurrentEmailChangeRequestAndConfirmDoesNotDeadlock() throws Exception {
    User user =
        new User(
            UUID.randomUUID(),
            "alice@example.com",
            "alice@example.com",
            "Alice",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    user.verify(Instant.now());
    userRepository.save(user);

    OpaqueToken.Issued token = OpaqueToken.generate();
    emailChangeTokenRepository.save(
        new EmailChangeToken(
            UUID.randomUUID(),
            user.getId(),
            "alice.new@example.com",
            token.digest(),
            Instant.now().plusSeconds(3600)));

    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicReference<Throwable> unexpectedErrorRef = new AtomicReference<>();

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
          } catch (AccountService.InvalidAccountException expectedIfTokenInvalidated) {
            // Expected race outcome if request task invalidates token first
          } catch (Throwable unexpected) {
            unexpectedErrorRef.set(unexpected);
            throw unexpected;
          }
          return null;
        };

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> f1 = executor.submit(requestTask);
      Future<Void> f2 = executor.submit(confirmTask);

      f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);
    }

    assertThat(unexpectedErrorRef.get()).isNull();

    // Verify DB invariant: email_change_tokens has at most 1 active token
    List<EmailChangeToken> active =
        emailChangeTokenRepository.findByUserIdAndUsedAtIsNull(user.getId());
    assertThat(active.size()).isLessThanOrEqualTo(1);
  }

  @Test
  @Timeout(15)
  void concurrentPasswordResetIssuancePreservesSingleActiveToken() throws Exception {
    User user =
        new User(
            UUID.randomUUID(),
            "bob@example.com",
            "bob@example.com",
            "Bob",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    user.verify(Instant.now());
    userRepository.save(user);

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

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> f1 = executor.submit(task1);
      Future<Void> f2 = executor.submit(task2);

      f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);
    }

    List<PasswordResetToken> active =
        passwordResetTokenRepository.findByUserIdAndUsedAtIsNull(user.getId());
    assertThat(active).hasSize(1);
  }

  private enum RedeemStatus {
    SUCCESS,
    EXPECTED_FAILURE_TOKEN_USED_OR_EXISTS,
    UNEXPECTED_FAILURE
  }

  private record RedeemOutcome(RedeemStatus status, Throwable exception) {}

  @Test
  @Timeout(15)
  void concurrentInvitationRedeemAllowsExactlyOneSuccess() throws Exception {
    OpaqueToken.Issued token = OpaqueToken.generate();
    Invitation invitation =
        new Invitation(
            UUID.randomUUID(),
            "invited@example.com",
            token.digest(),
            Instant.now().plusSeconds(3600));
    invitationRepository.save(invitation);

    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<RedeemOutcome> redeemTask1 =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            adminUserService.redeem(token.value(), "Invited User 1", "Password123!", "zh-TW");
            return new RedeemOutcome(RedeemStatus.SUCCESS, null);
          } catch (AdminUserService.InvalidInvitationException
              | AdminUserService.AlreadyExistsException e) {
            return new RedeemOutcome(RedeemStatus.EXPECTED_FAILURE_TOKEN_USED_OR_EXISTS, e);
          } catch (Throwable t) {
            return new RedeemOutcome(RedeemStatus.UNEXPECTED_FAILURE, t);
          }
        };

    Callable<RedeemOutcome> redeemTask2 =
        () -> {
          barrier.await(5, TimeUnit.SECONDS);
          try {
            adminUserService.redeem(token.value(), "Invited User 2", "Password123!", "zh-TW");
            return new RedeemOutcome(RedeemStatus.SUCCESS, null);
          } catch (AdminUserService.InvalidInvitationException
              | AdminUserService.AlreadyExistsException e) {
            return new RedeemOutcome(RedeemStatus.EXPECTED_FAILURE_TOKEN_USED_OR_EXISTS, e);
          } catch (Throwable t) {
            return new RedeemOutcome(RedeemStatus.UNEXPECTED_FAILURE, t);
          }
        };

    RedeemOutcome r1;
    RedeemOutcome r2;
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<RedeemOutcome> f1 = executor.submit(redeemTask1);
      Future<RedeemOutcome> f2 = executor.submit(redeemTask2);

      r1 = f1.get(10, TimeUnit.SECONDS);
      r2 = f2.get(10, TimeUnit.SECONDS);
    }

    // Neither task should have failed with an unexpected error
    assertThat(r1.status())
        .as("Redeem task 1 failed unexpectedly: %s", r1.exception())
        .isNotEqualTo(RedeemStatus.UNEXPECTED_FAILURE);
    assertThat(r2.status())
        .as("Redeem task 2 failed unexpectedly: %s", r2.exception())
        .isNotEqualTo(RedeemStatus.UNEXPECTED_FAILURE);

    // Exactly one redeem must succeed, the other must be expected rejection
    boolean oneSucceeded =
        (r1.status() == RedeemStatus.SUCCESS
                && r2.status() == RedeemStatus.EXPECTED_FAILURE_TOKEN_USED_OR_EXISTS)
            || (r2.status() == RedeemStatus.SUCCESS
                && r1.status() == RedeemStatus.EXPECTED_FAILURE_TOKEN_USED_OR_EXISTS);
    assertThat(oneSucceeded).isTrue();

    // Exactly one user must be created
    assertThat(userRepository.findAll()).hasSize(1);

    // Invitation must be marked used
    Invitation updated = invitationRepository.findById(invitation.getId()).orElseThrow();
    assertThat(updated.getUsedAt()).isNotNull();
  }

  @Test
  @Timeout(15)
  void concurrentTagGetOrCreatePreventsDuplicateTagEntities() throws Exception {
    User author =
        new User(
            UUID.randomUUID(),
            "author@example.com",
            "author@example.com",
            "Author",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    userRepository.save(author);

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

    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
      List<Future<ArticleService.ArticleView>> futures = executor.invokeAll(tasks);
      for (Future<ArticleService.ArticleView> f : futures) {
        ArticleService.ArticleView view = f.get(10, TimeUnit.SECONDS);
        assertThat(view).isNotNull();
      }
    }

    // Verify DB invariant: exactly 2 distinct tags exist
    List<Tag> allTags = tagRepository.findAll();
    assertThat(allTags).hasSize(2);

    // Verify all 4 articles are created and linked to the canonical tags
    var articlePage = articleService.list(author, "", null, null, PageRequest.of(0, 10));
    assertThat(articlePage.getContent()).hasSize(4);
    for (ArticleService.ArticleView a : articlePage.getContent()) {
      assertThat(a.tagNames()).hasSize(2);
    }
  }

  @Test
  @Timeout(15)
  void concurrentOrphanTagReuseVsCleanupMaintainsTagIntegrity() throws Exception {
    User author =
        new User(
            UUID.randomUUID(),
            "reusetest@example.com",
            "reusetest@example.com",
            "Reuse Author",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    userRepository.save(author);

    // Create an existing unreferenced (orphan) tag
    tagRepository.saveAndFlush(new Tag(UUID.randomUUID(), "OrphanReuseTag"));

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

    ArticleService.ArticleView created;
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<ArticleService.ArticleView> f1 = executor.submit(createTask);
      Future<Void> f2 = executor.submit(cleanupTask);

      created = f1.get(10, TimeUnit.SECONDS);
      f2.get(10, TimeUnit.SECONDS);
    }

    assertThat(created).isNotNull();
    assertThat(created.tagNames()).contains("OrphanReuseTag");

    // The tag must still exist and be properly linked
    Tag existingTag = tagRepository.findByNameIgnoreCase("OrphanReuseTag").orElseThrow();
    assertThat(articleRepository.countByTagsId(existingTag.getId())).isEqualTo(1);
  }

  @Test
  void articleQueryBatchFetchingPreventsNPlusOneQueries() {
    User author =
        new User(
            UUID.randomUUID(),
            "batchauthor@example.com",
            "batchauthor@example.com",
            "Batch Author",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    userRepository.save(author);

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

    // Total query count must be constant (article query + count query + tag batch query = 3
    // queries)
    long queryCount = statistics.getPrepareStatementCount();
    assertThat(queryCount).isLessThanOrEqualTo(4);
  }

  @Test
  void startupCleanupTransactionBoundaryCleansExpiredArticlesAndOrphanTags() {
    User author =
        new User(
            UUID.randomUUID(),
            "cleanauthor@example.com",
            "cleanauthor@example.com",
            "Clean Author",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    userRepository.save(author);

    ArticleService.ArticleView view =
        articleService.create(
            author,
            "Old Article",
            "Old Content",
            PublicationStatus.PUBLISHED,
            null,
            Set.of("OrphanTag1", "OrphanTag2"));

    // Set deletedAt to 35 days ago to simulate expired deleted article
    Article article = articleRepository.findById(view.id()).orElseThrow();
    article.delete();
    ReflectionTestUtils.setField(article, "deletedAt", Instant.now().minus(35, ChronoUnit.DAYS));
    articleRepository.saveAndFlush(article);

    // Run cleanup executor
    articleCleanupExecutor.cleanup();

    // Article and orphan tags should be cleaned up
    assertThat(articleRepository.findById(view.id())).isEmpty();
    assertThat(tagRepository.findAll()).isEmpty();
  }

  @Test
  void startupCleanupRollsBackEntireTransactionOnFailureWithoutModifyingState() {
    User author =
        new User(
            UUID.randomUUID(),
            "rollbackauthor@example.com",
            "rollbackauthor@example.com",
            "Rollback Author",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    userRepository.save(author);

    ArticleService.ArticleView view =
        articleService.create(
            author,
            "Expired Article To Rollback",
            "Content",
            PublicationStatus.PUBLISHED,
            null,
            Set.of("RollbackTag1"));

    Article article = articleRepository.findById(view.id()).orElseThrow();
    article.delete();
    ReflectionTestUtils.setField(article, "deletedAt", Instant.now().minus(35, ChronoUnit.DAYS));
    articleRepository.saveAndFlush(article);

    // Configure spy to fail during cleanup transaction
    doThrow(new IllegalStateException("Simulated crash mid-cleanup transaction"))
        .when(tagRepository)
        .findCandidateOrphanTagNames();

    try {
      assertThatThrownBy(() -> articleCleanupExecutor.cleanup())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Simulated crash mid-cleanup transaction");
    } finally {
      reset(tagRepository);
    }

    // Verify atomic ROLLBACK: Article and its tags MUST still exist in PostgreSQL
    Article rolledBackArticle = articleRepository.findById(view.id()).orElse(null);
    assertThat(rolledBackArticle).isNotNull();
    assertThat(rolledBackArticle.getDeletedAt()).isNotNull();
    assertThat(tagRepository.findByNameIgnoreCase("RollbackTag1")).isPresent();
  }

  @Test
  @Timeout(15)
  void multiTagDeadlockFreeUnderCollationAndUnicode() throws Exception {
    User author =
        new User(
            UUID.randomUUID(),
            "unicodetest@example.com",
            "unicodetest@example.com",
            "Unicode Author",
            passwordEncoder.encode("Password123!"),
            "zh-TW");
    author.verify(Instant.now());
    userRepository.save(author);

    // Pre-create orphan tags in DB that are initially unreferenced by any article
    Tag tagCafe = tagRepository.saveAndFlush(new Tag(UUID.randomUUID(), "café"));
    Tag tagResume = tagRepository.saveAndFlush(new Tag(UUID.randomUUID(), "résumé"));
    Tag tagUber = tagRepository.saveAndFlush(new Tag(UUID.randomUUID(), "Über"));
    Tag tagNlp = tagRepository.saveAndFlush(new Tag(UUID.randomUUID(), "自然語言處理"));

    Set<String> tagSet1 = Set.of("café", "résumé", "Über", "自然語言處理");
    Set<String> tagSet2 = Set.of("CAFÉ", "RÉSUMÉ", "über", "自然語言處理", "架構設計");

    CountDownLatch cleanupDiscoveredCandidates = new CountDownLatch(1);
    CountDownLatch articleStartedLocking = new CountDownLatch(1);
    CountDownLatch cleanupAttemptedFirstLock = new CountDownLatch(1);
    AtomicReference<List<String>> discoveredCandidatesRef = new AtomicReference<>();

    ArticleCleanupExecutor targetExecutor = AopTestUtils.getTargetObject(articleCleanupExecutor);
    TagRepository originalCleanupTags =
        (TagRepository) ReflectionTestUtils.getField(targetExecutor, "tagRepository");

    ArticleService targetArticleService = AopTestUtils.getTargetObject(articleService);
    TagRepository originalArticleTags =
        (TagRepository) ReflectionTestUtils.getField(targetArticleService, "tagRepository");

    TagRepository proxyCleanupTags =
        (TagRepository)
            Proxy.newProxyInstance(
                TagRepository.class.getClassLoader(),
                new Class<?>[] {TagRepository.class},
                (proxy, method, args) -> {
                  if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                  }
                  try {
                    if ("findCandidateOrphanTagNames".equals(method.getName())) {
                      @SuppressWarnings("unchecked")
                      List<String> result = (List<String>) method.invoke(originalCleanupTags, args);
                      discoveredCandidatesRef.set(result);
                      cleanupDiscoveredCandidates.countDown();
                      // Wait until article thread starts acquiring the first lock
                      awaitLatch(
                          articleStartedLocking,
                          5,
                          TimeUnit.SECONDS,
                          "article thread did not start acquiring first tag lock ('café')");
                      return result;
                    }
                    if ("lockNormalizedName".equals(method.getName()) && "café".equals(args[0])) {
                      cleanupAttemptedFirstLock.countDown();
                    }
                    return method.invoke(originalCleanupTags, args);
                  } catch (InvocationTargetException e) {
                    Throwable targetException = e.getTargetException();
                    if (targetException instanceof RuntimeException runtimeException) {
                      throw runtimeException;
                    }
                    if (targetException instanceof Error error) {
                      throw error;
                    }
                    throw new RuntimeException("Invocation target failed", targetException);
                  }
                });

    TagRepository proxyArticleTags =
        (TagRepository)
            Proxy.newProxyInstance(
                TagRepository.class.getClassLoader(),
                new Class<?>[] {TagRepository.class},
                (proxy, method, args) -> {
                  if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                  }
                  try {
                    if ("lockNormalizedName".equals(method.getName()) && "café".equals(args[0])) {
                      // Acquire first lock in article transaction
                      Object result = method.invoke(originalArticleTags, args);
                      articleStartedLocking.countDown();
                      // Ensure cleanup thread reaches and attempts the first lock concurrently
                      awaitLatch(
                          cleanupAttemptedFirstLock,
                          5,
                          TimeUnit.SECONDS,
                          "cleanup thread did not attempt first tag lock ('café') concurrently");
                      return result;
                    }
                    return method.invoke(originalArticleTags, args);
                  } catch (InvocationTargetException e) {
                    Throwable targetException = e.getTargetException();
                    if (targetException instanceof RuntimeException runtimeException) {
                      throw runtimeException;
                    }
                    if (targetException instanceof Error error) {
                      throw error;
                    }
                    throw new RuntimeException("Invocation target failed", targetException);
                  }
                });

    try {
      ReflectionTestUtils.setField(targetExecutor, "tagRepository", proxyCleanupTags);
      ReflectionTestUtils.setField(targetArticleService, "tagRepository", proxyArticleTags);

      try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
        Callable<ArticleService.ArticleView> t1 =
            () -> {
              awaitLatch(
                  cleanupDiscoveredCandidates,
                  5,
                  TimeUnit.SECONDS,
                  "cleanup thread did not complete candidate discovery before article 1 creation");
              return articleService.create(
                  author,
                  "Article Concurrent 1",
                  "Content 1",
                  PublicationStatus.PUBLISHED,
                  null,
                  tagSet1);
            };

        Callable<ArticleService.ArticleView> t2 =
            () -> {
              awaitLatch(
                  cleanupDiscoveredCandidates,
                  5,
                  TimeUnit.SECONDS,
                  "cleanup thread did not complete candidate discovery before article 2 creation");
              return articleService.create(
                  author,
                  "Article Concurrent 2",
                  "Content 2",
                  PublicationStatus.PUBLISHED,
                  null,
                  tagSet2);
            };

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
        Tag persistedCafe = tagRepository.findByNameIgnoreCase("café").orElseThrow();
        Tag persistedResume = tagRepository.findByNameIgnoreCase("résumé").orElseThrow();
        Tag persistedUber = tagRepository.findByNameIgnoreCase("über").orElseThrow();
        Tag persistedNlp = tagRepository.findByNameIgnoreCase("自然語言處理").orElseThrow();

        assertThat(persistedCafe.getId()).isEqualTo(tagCafe.getId());
        assertThat(persistedResume.getId()).isEqualTo(tagResume.getId());
        assertThat(persistedUber.getId()).isEqualTo(tagUber.getId());
        assertThat(persistedNlp.getId()).isEqualTo(tagNlp.getId());

        // 4. Verify no duplicate tag entities created for case-insensitive variants
        List<Tag> allTags = tagRepository.findAll();
        assertThat(allTags).hasSize(5);

        // 5. Verify article_tags relationships are accurately linked in DB
        assertThat(articleRepository.countByTagsId(persistedCafe.getId())).isEqualTo(2);
        assertThat(articleRepository.countByTagsId(persistedResume.getId())).isEqualTo(2);
        assertThat(articleRepository.countByTagsId(persistedUber.getId())).isEqualTo(2);
        assertThat(articleRepository.countByTagsId(persistedNlp.getId())).isEqualTo(2);

        Tag persistedArch = tagRepository.findByNameIgnoreCase("架構設計").orElseThrow();
        assertThat(articleRepository.countByTagsId(persistedArch.getId())).isEqualTo(1);
      }
    } finally {
      ReflectionTestUtils.setField(targetExecutor, "tagRepository", originalCleanupTags);
      ReflectionTestUtils.setField(targetArticleService, "tagRepository", originalArticleTags);
    }
  }

  private static void awaitLatch(
      CountDownLatch latch, long timeout, TimeUnit unit, String stageDescription)
      throws InterruptedException {
    boolean completed = latch.await(timeout, unit);
    if (!completed) {
      throw new IllegalStateException(
          "Synchronization timeout: "
              + stageDescription
              + " did not complete within "
              + timeout
              + " "
              + unit.name().toLowerCase(Locale.ROOT));
    }
  }
}
