package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.User;
import com.blogadmin.identity.domain.UserRepository;
import com.blogadmin.identity.domain.UserRole;
import com.blogadmin.publishing.domain.Tag;
import com.blogadmin.publishing.domain.TagRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArticleApiIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired UserRepository users;
  @Autowired TagRepository tags;
  @Autowired PasswordEncoder passwords;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @Test
  void authorCrudKeepsAttributionAndPublishesOnce() {
    UUID author = user(UserRole.AUTHOR, "author");
    String token = login("author");
    Map<String, Object> request = Map.of("title", "Hello", "content", "plain text");
    ResponseEntity<Map> created =
        exchange("/api/v1/articles", HttpMethod.POST, token, request, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = UUID.fromString((String) created.getBody().get("id"));
    assertThat(created.getBody())
        .containsEntry("authorAttribution", "author")
        .containsEntry("status", "DRAFT");

    Map<String, Object> publish =
        Map.of(
            "title",
            "Hello 2",
            "content",
            "plain text",
            "status",
            "PUBLISHED",
            "version",
            created.getBody().get("version"));
    ResponseEntity<Map> updated =
        exchange("/api/v1/articles/" + id, HttpMethod.PUT, token, publish, Map.class);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    Object publishedAt = updated.getBody().get("publishedAt");
    assertThat(publishedAt).isNotNull();
    assertThat(updated.getBody()).containsEntry("authorAttribution", "author");
    assertThat(
            exchange("/api/v1/articles/" + id, HttpMethod.GET, token, null, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(author).isNotNull();
  }

  @Test
  void authorizationFilteringAndOptimisticConflictAreEnforced() {
    user(UserRole.AUTHOR, "one");
    user(UserRole.AUTHOR, "two");
    String one = login("one");
    String two = login("two");
    ResponseEntity<Map> article =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            one,
            Map.of("title", "A", "content", "C"),
            Map.class);
    String id = (String) article.getBody().get("id");
    assertThat(
            exchange(
                    "/api/v1/articles/" + id,
                    HttpMethod.PUT,
                    two,
                    Map.of("title", "x", "content", "y", "status", "DRAFT", "version", 0),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            exchange(
                    "/api/v1/articles/" + id,
                    HttpMethod.PUT,
                    one,
                    Map.of("title", "x", "content", "y", "status", "DRAFT", "version", 99),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            exchange("/api/v1/articles?status=DRAFT", HttpMethod.GET, two, null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void publishedAtIsImmutableAndDeleteHidesArticle() {
    user(UserRole.AUTHOR, "lifecycle");
    String token = login("lifecycle");
    ResponseEntity<Map> created =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            token,
            Map.of("title", "Filterable", "content", "C", "status", "PUBLISHED"),
            Map.class);
    String id = (String) created.getBody().get("id");
    Instant firstPublishedAt =
        Instant.parse(created.getBody().get("publishedAt").toString())
            .truncatedTo(ChronoUnit.MILLIS);
    long version = ((Number) created.getBody().get("version")).longValue();

    ResponseEntity<Map> changed =
        exchange(
            "/api/v1/articles/" + id,
            HttpMethod.PUT,
            token,
            Map.of(
                "title", "Filterable Changed",
                "content", "C2",
                "status", "PUBLISHED",
                "version", version),
            Map.class);
    Instant changedPublishedAt =
        Instant.parse(changed.getBody().get("publishedAt").toString())
            .truncatedTo(ChronoUnit.MILLIS);
    assertThat(changedPublishedAt).isEqualTo(firstPublishedAt);

    assertThat(
            exchange("/api/v1/articles/" + id, HttpMethod.DELETE, token, null, Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            exchange("/api/v1/articles/" + id, HttpMethod.GET, token, null, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<Map> filtered =
        exchange(
            "/api/v1/articles?title=Filterable&status=PUBLISHED",
            HttpMethod.GET,
            token,
            null,
            Map.class);
    assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(filtered.getBody().get("content").toString()).doesNotContain(id);
  }

  @Test
  void filtersArticlesByOneTagAndPaginatesResults() {
    user(UserRole.AUTHOR, "tagged");
    String token = login("tagged");
    UUID tagId = UUID.randomUUID();
    tags.saveAndFlush(new Tag(tagId, "integration"));

    ResponseEntity<Map> first =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            token,
            Map.of("title", "Tagged 1", "content", "C1", "tagIds", Set.of(tagId)),
            Map.class);
    ResponseEntity<Map> second =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            token,
            Map.of("title", "Tagged 2", "content", "C2", "tagIds", Set.of(tagId)),
            Map.class);
    exchange(
        "/api/v1/articles",
        HttpMethod.POST,
        token,
        Map.of("title", "Other", "content", "C3"),
        Map.class);

    ResponseEntity<Map> pageOne =
        exchange(
            "/api/v1/articles?tagId=" + tagId + "&page=0&size=1",
            HttpMethod.GET,
            token,
            null,
            Map.class);
    ResponseEntity<Map> pageTwo =
        exchange(
            "/api/v1/articles?tagId=" + tagId + "&page=1&size=1",
            HttpMethod.GET,
            token,
            null,
            Map.class);

    assertThat(pageOne.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pageOne.getBody())
        .containsEntry("totalElements", 2)
        .containsEntry("totalPages", 2)
        .containsEntry("size", 1)
        .containsEntry("number", 0);
    assertThat((List<Map>) pageOne.getBody().get("content"))
        .singleElement()
        .satisfies(
            article ->
                assertThat((List<String>) article.get("tagIds"))
                    .containsExactly(tagId.toString()));
    assertThat(pageTwo.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pageTwo.getBody()).containsEntry("number", 1);
    assertThat(pageOne.getBody().get("content"))
        .isNotEqualTo(pageTwo.getBody().get("content"));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void adminCanUpdateAndDeleteAnotherAuthorsArticle() {
    user(UserRole.AUTHOR, "owned");
    user(UserRole.ADMIN, "admin");
    String authorToken = login("owned");
    String adminToken = login("admin");
    ResponseEntity<Map> created =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            authorToken,
            Map.of("title", "Author article", "content", "C"),
            Map.class);
    String id = (String) created.getBody().get("id");

    ResponseEntity<Map> updated =
        exchange(
            "/api/v1/articles/" + id,
            HttpMethod.PUT,
            adminToken,
            Map.of(
                "title", "Admin updated",
                "content", "Updated content",
                "status", "DRAFT",
                "version", created.getBody().get("version")),
            Map.class);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody()).containsEntry("title", "Admin updated");

    assertThat(
            exchange(
                    "/api/v1/articles/" + id,
                    HttpMethod.DELETE,
                    adminToken,
                    null,
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            exchange("/api/v1/articles/" + id, HttpMethod.GET, adminToken, null, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private UUID user(UserRole role, String name) {
    UUID id = UUID.randomUUID();
    User u =
        new User(
            id,
            name + "@example.com",
            name + "@example.com",
            name,
            passwords.encode("safe-password"),
            "zh-TW");
    u.verify(Instant.now());
    u.changeRole(role);
    users.saveAndFlush(u);
    return id;
  }

  private String login(String name) {
    return rest.postForEntity(
            url("/api/v1/auth/login"),
            Map.of("email", name + "@example.com", "password", "safe-password"),
            Map.class)
        .getBody()
        .get("accessToken")
        .toString();
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, String token, Object body, Class<T> type) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return rest.exchange(url(path), method, new HttpEntity<>(body, headers), type);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
