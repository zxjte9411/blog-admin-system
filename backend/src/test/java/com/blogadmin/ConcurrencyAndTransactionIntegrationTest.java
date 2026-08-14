package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    org.springframework.test.util.ReflectionTestUtils.setField(
        article, "deletedAt", Instant.now().minus(35, ChronoUnit.DAYS));
    articles.saveAndFlush(article);

    // Run cleanup executor
    articleCleanupExecutor.cleanup();

    // Article and orphan tags should be cleaned up
    assertThat(articles.findById(view.id())).isEmpty();
    assertThat(tags.findAll()).isEmpty();
  }
}
