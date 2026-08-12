package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.User;
import com.blogadmin.identity.domain.UserRepository;
import com.blogadmin.identity.domain.UserRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
