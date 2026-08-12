package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.publishing.application.ArticleCleanupService;
import com.blogadmin.publishing.domain.article.Article;
import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.tag.Tag;
import com.blogadmin.publishing.domain.tag.TagRepository;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PublishingIssuesIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired UserRepository users;
  @Autowired TagRepository tags;
  @Autowired ArticleRepository articles;
  @Autowired PasswordEncoder passwords;
  @Autowired ArticleCleanupService cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationRunner startupCleanup;

  @BeforeEach
  void clearPublishingData() {
    jdbc.update("DELETE FROM article_tags");
    jdbc.update("DELETE FROM articles");
    jdbc.update("DELETE FROM tags");
    jdbc.update("DELETE FROM auth_rate_limit_events");
  }

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @Test
  void deletedListRestoreAndCleanup() {
    user(UserRole.AUTHOR, "a");
    user(UserRole.AUTHOR, "b");
    user(UserRole.ADMIN, "adm");
    UUID tagId = UUID.randomUUID();
    tags.saveAndFlush(new Tag(tagId, "restore-tag"));
    String a = login("a"), b = login("b"), admin = login("adm");
    Map<String, Object> one =
        create(
            a,
            Map.of("title", "one", "content", "c", "status", "PUBLISHED", "tagIds", Set.of(tagId)));
    Instant createdAt =
        articles.findById(UUID.fromString((String) one.get("id"))).orElseThrow().getCreatedAt();
    Instant publishedAt =
        Instant.parse(one.get("publishedAt").toString())
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Map<String, Object> two =
        create(
            b,
            Map.of("title", "two", "content", "c", "status", "PUBLISHED", "tagIds", Set.of(tagId)));
    delete(a, one);
    delete(b, two);
    assertThat(
            ((Map<String, Object>) get("/api/v1/articles/deleted", a).getBody().get("page"))
                .get("totalElements"))
        .isEqualTo(1);
    assertThat(
            ((Map<String, Object>) get("/api/v1/articles/deleted", admin).getBody().get("page"))
                .get("totalElements"))
        .isEqualTo(2);
    ResponseEntity<Map> restored =
        exchange(
            "/api/v1/articles/" + one.get("id") + "/restore", HttpMethod.POST, a, null, Map.class);
    assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(restored.getBody()).containsEntry("status", "PUBLISHED");
    Article restoredArticle =
        articles.findById(UUID.fromString((String) one.get("id"))).orElseThrow();
    assertThat(restoredArticle.getCreatedAt()).isEqualTo(createdAt);
    assertThat(restoredArticle.getPublishedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        .isEqualTo(publishedAt);
    assertThat(restoredArticle.getTags()).extracting(Tag::getId).containsExactly(tagId);
    assertThat(
            exchange(
                    "/api/v1/articles/" + two.get("id") + "/restore",
                    HttpMethod.POST,
                    a,
                    null,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    Article expired = articles.findById(UUID.fromString((String) two.get("id"))).orElseThrow();
    jdbc.update(
        "UPDATE articles SET deleted_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(31, java.time.temporal.ChronoUnit.DAYS)),
        expired.getId());
    cleanup.cleanup();
    assertThat(articles.findById(expired.getId())).isEmpty();
    assertThat(tags.findById(tagId)).isPresent();
    Map<String, Object> adminArticle = create(a, Map.of("title", "admin", "content", "c"));
    delete(a, adminArticle);
    assertThat(
            exchange(
                    "/api/v1/articles/" + adminArticle.get("id") + "/restore",
                    HttpMethod.POST,
                    admin,
                    null,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void tagsAreTrimmedReusedAndCleanedWhenUnreferenced() {
    user(UserRole.AUTHOR, "tagger");
    String token = login("tagger");
    Map<String, Object> first =
        create(token, Map.of("title", "first", "content", "x", "tagNames", Set.of("  Mixed  ")));
    Map<String, Object> second =
        create(token, Map.of("title", "second", "content", "x", "tagNames", Set.of("mixed")));
    assertThat(tags.findAll())
        .singleElement()
        .satisfies(tag -> assertThat(tag.getName()).isEqualTo("Mixed"));
    long version =
        ((Number)
                exchange(
                        "/api/v1/articles/" + first.get("id"),
                        HttpMethod.GET,
                        token,
                        null,
                        Map.class)
                    .getBody()
                    .get("version"))
            .longValue();
    assertThat(
            exchange(
                    "/api/v1/articles/" + first.get("id"),
                    HttpMethod.PUT,
                    token,
                    Map.of(
                        "title", "first", "content", "x", "status", "DRAFT", "version", version,
                        "tagIds", Set.of()),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    long secondVersion =
        ((Number)
                exchange(
                        "/api/v1/articles/" + second.get("id"),
                        HttpMethod.GET,
                        token,
                        null,
                        Map.class)
                    .getBody()
                    .get("version"))
            .longValue();
    assertThat(
            exchange(
                    "/api/v1/articles/" + second.get("id"),
                    HttpMethod.PUT,
                    token,
                    Map.of(
                        "title", "second",
                        "content", "x",
                        "status", "DRAFT",
                        "version", secondVersion,
                        "tagIds", Set.of()),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    delete(token, second);
    assertThat(tags.findAll()).isEmpty();
    delete(token, first);
    tags.flush();
    jdbc.update(
        "UPDATE articles SET deleted_at = ? WHERE id IN (?, ?)",
        Timestamp.from(Instant.now().minus(31, java.time.temporal.ChronoUnit.DAYS)),
        UUID.fromString((String) first.get("id")),
        UUID.fromString((String) second.get("id")));
    cleanup.cleanup();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isZero();
  }

  @Test
  @DirtiesContext
  void publicSortingDraftTransitionPlainTextAndScopedCors() throws Exception {
    user(UserRole.AUTHOR, "checks");
    String token = login("checks");
    Map<String, Object> old =
        create(token, Map.of("title", "old", "content", "plain", "status", "PUBLISHED"));
    Map<String, Object> newer =
        create(token, Map.of("title", "new", "content", "plain", "status", "PUBLISHED"));
    jdbc.update("DELETE FROM auth_rate_limit_events");
    ResponseEntity<Map> page =
        exchange("/api/v1/public/articles?page=0&size=1", HttpMethod.GET, null, null, Map.class);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    long version = ((Number) newer.get("version")).longValue();
    assertThat(
            exchange(
                    "/api/v1/articles/" + newer.get("id"),
                    HttpMethod.PUT,
                    token,
                    Map.of(
                        "title", "new", "content", "plain", "status", "DRAFT", "version", version),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/public/articles/" + newer.get("id"),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    "/api/v1/public/articles/" + UUID.randomUUID(),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getHeaders()
                .getCacheControl())
        .contains("no-cache");
    assertThat(
            exchange(
                    "/api/v1/articles",
                    HttpMethod.POST,
                    token,
                    Map.of("title", "html", "content", "<b>x</b>"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    HttpHeaders cors = new HttpHeaders();
    cors.setOrigin("https://example.test");
    ResponseEntity<Void> protectedOptions =
        rest.exchange(
            url("/api/v1/articles"), HttpMethod.OPTIONS, new HttpEntity<>(cors), Void.class);
    assertThat(protectedOptions.getHeaders().getAccessControlAllowOrigin()).isNull();
    assertThat(startupCleanup).isNotNull();
    Scheduled scheduled =
        ArticleCleanupService.class
            .getDeclaredMethod("scheduledCleanup")
            .getAnnotation(Scheduled.class);
    assertThat(scheduled.cron()).isEqualTo("0 0 3 * * *");
    assertThat(scheduled.zone()).isEqualTo("Asia/Taipei");
    assertThat(old).isNotNull();
  }

  @Test
  void publicApiVisibilityHeadersCorsAndRateLimit() {
    user(UserRole.AUTHOR, "pub");
    String token = login("pub");
    UUID tagId = UUID.randomUUID();
    tags.saveAndFlush(new Tag(tagId, "public-tag"));
    Map<String, Object> published =
        create(
            token,
            Map.of(
                "title",
                "public",
                "content",
                "plain",
                "status",
                "PUBLISHED",
                "tagIds",
                Set.of(tagId)));
    Map<String, Object> draft = create(token, Map.of("title", "draft", "content", "x"));
    Map<String, Object> deleted =
        create(token, Map.of("title", "deleted", "content", "x", "status", "PUBLISHED"));
    delete(token, deleted);
    ResponseEntity<Map> list =
        exchange(
            "/api/v1/public/articles?title=public&tagId=" + tagId,
            HttpMethod.GET,
            null,
            null,
            Map.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getHeaders().getCacheControl()).contains("no-cache");
    assertThat((Map<String, Object>) ((List<?>) list.getBody().get("content")).get(0))
        .containsKeys("createdAt", "updatedAt");
    assertThat(list.getBody().toString()).doesNotContain("owner").doesNotContain("version");
    assertThat(
            exchange(
                    "/api/v1/public/articles?tagId=" + UUID.randomUUID(),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getBody()
                .get("page"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extracting("totalElements")
        .isEqualTo(0);
    assertThat(
            exchange(
                    "/api/v1/public/articles/" + published.get("id"),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/public/articles/" + draft.get("id"),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    "/api/v1/public/articles/" + deleted.get("id"),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<Map> publicTags =
        exchange("/api/v1/public/tags", HttpMethod.GET, null, null, Map.class);
    assertThat(publicTags.getBody().get("content").toString()).contains("public-tag");
    assertThat(
            exchange(
                    "/api/v1/public/articles/" + draft.get("id"),
                    HttpMethod.GET,
                    null,
                    null,
                    Map.class)
                .getHeaders()
                .getCacheControl())
        .contains("no-cache");
    HttpHeaders cors = new HttpHeaders();
    cors.setOrigin("https://example.test");
    ResponseEntity<Void> options =
        rest.exchange(
            url("/api/v1/public/articles"), HttpMethod.OPTIONS, new HttpEntity<>(cors), Void.class);
    assertThat(options.getHeaders().getAccessControlAllowOrigin()).isEqualTo("*");
    assertThat(options.getHeaders().getAccessControlAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    HttpStatusCode last = null;
    for (int i = 0; i < 61; i++)
      last =
          exchange("/api/v1/public/articles", HttpMethod.GET, null, null, Map.class)
              .getStatusCode();
    assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(
            exchange("/api/v1/public/articles", HttpMethod.GET, null, null, Map.class)
                .getHeaders()
                .getCacheControl())
        .contains("no-cache");
  }

  @Test
  void plainTextComparisonOperatorsAreAccepted() {
    user(UserRole.AUTHOR, "comparison");
    ResponseEntity<Map> created =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            login("comparison"),
            Map.of("title", "comparison", "content", "1 < 2 and 3 > 2"),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void publicLimiterRemovesExpiredIdleIpKeys() throws Exception {
    com.blogadmin.publishing.application.ArticleService service =
        new com.blogadmin.publishing.application.ArticleService(articles, tags);
    Field field = service.getClass().getDeclaredField("publicLimits");
    field.setAccessible(true);
    Map<String, Deque<Instant>> limits = (Map<String, Deque<Instant>>) field.get(service);
    limits.put("old-ip", new ArrayDeque<>(Set.of(Instant.now().minusSeconds(61))));
    service.publicArticles(
        "", null, org.springframework.data.domain.PageRequest.of(0, 1), "new-ip");
    assertThat(limits).doesNotContainKey("old-ip");
  }

  private Map<String, Object> create(String t, Object body) {
    return exchange("/api/v1/articles", HttpMethod.POST, t, body, Map.class).getBody();
  }

  private void delete(String t, Map<String, Object> a) {
    exchange("/api/v1/articles/" + a.get("id"), HttpMethod.DELETE, t, null, Void.class);
  }

  private void user(UserRole role, String n) {
    User u =
        new User(
            UUID.randomUUID(),
            n + "@example.com",
            n + "@example.com",
            n,
            passwords.encode("safe-password"),
            "zh-TW");
    u.verify(Instant.now());
    u.changeRole(role);
    users.saveAndFlush(u);
  }

  private String login(String n) {
    return rest.postForEntity(
            url("/api/v1/auth/login"),
            Map.of("email", n + "@example.com", "password", "safe-password"),
            Map.class)
        .getBody()
        .get("accessToken")
        .toString();
  }

  private ResponseEntity<Map> get(String p, String t) {
    return exchange(p, HttpMethod.GET, t, null, Map.class);
  }

  private <T> ResponseEntity<T> exchange(String p, HttpMethod m, String t, Object b, Class<T> c) {
    HttpHeaders h = new HttpHeaders();
    if (t != null) h.setBearerAuth(t);
    h.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(url(p), m, new HttpEntity<>(b, h), c);
  }

  private String url(String p) {
    return "http://localhost:" + port + p;
  }
}
