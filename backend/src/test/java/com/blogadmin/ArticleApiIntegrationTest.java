package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.publishing.domain.tag.Tag;
import com.blogadmin.publishing.domain.tag.TagRepository;
import com.blogadmin.publishing.web.dto.ArticleView;
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
import org.springframework.http.MediaType;
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

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate testRestTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private TagRepository tagRepository;
  @Autowired private PasswordEncoder passwordEncoder;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @Test
  void authorCrudKeepsAttributionAndPublishesOnce() {
    UUID authorId = createUser(UserRole.AUTHOR, "author");
    String token = login("author");
    Map<String, Object> request = Map.of("title", "Hello", "content", "plain text");
    ResponseEntity<ArticleView> created =
        exchange("/api/v1/articles", HttpMethod.POST, token, request, ArticleView.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = created.getBody().id();
    assertThat(created.getBody().createdAt()).isNotNull();
    Instant createdAt = created.getBody().createdAt().truncatedTo(ChronoUnit.MILLIS);
    assertThat(created.getBody().authorAttribution()).isEqualTo("author");
    assertThat(created.getBody().status()).hasToString("DRAFT");

    Map<String, Object> publish =
        Map.of(
            "title",
            "Hello 2",
            "content",
            "plain text",
            "status",
            "PUBLISHED",
            "version",
            created.getBody().version());
    ResponseEntity<ArticleView> updated =
        exchange("/api/v1/articles/" + id, HttpMethod.PUT, token, publish, ArticleView.class);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody().publishedAt()).isNotNull();
    assertThat(updated.getBody().authorAttribution()).isEqualTo("author");
    assertThat(
            exchange("/api/v1/articles/" + id, HttpMethod.GET, token, null, ArticleView.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange("/api/v1/articles?title=Hello 2", HttpMethod.GET, token, null, Map.class)
                .getBody())
        .extracting(
            body ->
                Instant.parse(
                        ((List<Map<String, Object>>) ((Map<String, Object>) body).get("content"))
                            .get(0)
                            .get("createdAt")
                            .toString())
                    .truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(createdAt);
    assertThat(authorId).isNotNull();
  }

  @Test
  void publicArticleViewsIncludeArticleIdInListAndDetail() {
    createUser(UserRole.AUTHOR, "public-id");
    String token = login("public-id");
    ResponseEntity<Map> created =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            token,
            Map.of("title", "Public ID", "content", "Content", "status", "PUBLISHED"),
            Map.class);
    UUID id = UUID.fromString((String) created.getBody().get("id"));

    ResponseEntity<Map> list =
        exchange("/api/v1/public/articles", HttpMethod.GET, token, null, Map.class);
    List<Map<String, Object>> articles = (List<Map<String, Object>>) list.getBody().get("content");
    assertThat(articles)
        .anySatisfy(article -> assertThat(article).containsEntry("id", id.toString()));

    ResponseEntity<Map> detail =
        exchange("/api/v1/public/articles/" + id, HttpMethod.GET, token, null, Map.class);
    assertThat(detail.getBody()).containsEntry("id", id.toString());
  }

  @Test
  void authorizationFilteringAndOptimisticConflictAreEnforced() {
    createUser(UserRole.AUTHOR, "one");
    createUser(UserRole.AUTHOR, "two");
    String tokenOne = login("one");
    String tokenTwo = login("two");
    ResponseEntity<Map> article =
        exchange(
            "/api/v1/articles",
            HttpMethod.POST,
            tokenOne,
            Map.of("title", "A", "content", "C"),
            Map.class);
    String id = (String) article.getBody().get("id");
    assertThat(
            exchange(
                    "/api/v1/articles/" + id,
                    HttpMethod.PUT,
                    tokenTwo,
                    Map.of("title", "x", "content", "y", "status", "DRAFT", "version", 0),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            exchange(
                    "/api/v1/articles/" + id,
                    HttpMethod.PUT,
                    tokenOne,
                    Map.of("title", "x", "content", "y", "status", "DRAFT", "version", 99),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            exchange("/api/v1/articles?status=DRAFT", HttpMethod.GET, tokenTwo, null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void publishedAtIsImmutableAndDeleteHidesArticle() {
    createUser(UserRole.AUTHOR, "lifecycle");
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
            exchange("/api/v1/articles/" + id, HttpMethod.GET, token, null, String.class)
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
    createUser(UserRole.AUTHOR, "tagged");
    String token = login("tagged");
    UUID tagId = UUID.randomUUID();
    tagRepository.saveAndFlush(new Tag(tagId, "integration"));

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
    assertThat(pageOne.getBody()).containsKey("page");
    assertThat((Map<String, Object>) pageOne.getBody().get("page"))
        .containsEntry("totalElements", 2)
        .containsEntry("totalPages", 2)
        .containsEntry("size", 1)
        .containsEntry("number", 0);
    assertThat((List<Map<String, Object>>) pageOne.getBody().get("content"))
        .singleElement()
        .satisfies(
            article ->
                assertThat((List<String>) article.get("tagIds")).containsExactly(tagId.toString()));
    assertThat(pageTwo.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((Map<String, Object>) pageTwo.getBody().get("page")).containsEntry("number", 1);
    assertThat(pageOne.getBody().get("content")).isNotEqualTo(pageTwo.getBody().get("content"));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void adminCanUpdateAndDeleteAnotherAuthorsArticle() {
    createUser(UserRole.AUTHOR, "owned");
    createUser(UserRole.ADMIN, "admin");
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
            exchange("/api/v1/articles/" + id, HttpMethod.DELETE, adminToken, null, Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            exchange("/api/v1/articles/" + id, HttpMethod.GET, adminToken, null, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private UUID createUser(UserRole role, String name) {
    UUID id = UUID.randomUUID();
    User user =
        new User(
            id,
            name + "@example.com",
            name + "@example.com",
            name,
            passwordEncoder.encode("safe-password"),
            "zh-TW");
    user.verify(Instant.now());
    user.changeRole(role);
    userRepository.saveAndFlush(user);
    return id;
  }

  private String login(String name) {
    return testRestTemplate
        .postForEntity(
            url("/api/v1/auth/login"),
            Map.of("email", name + "@example.com", "password", "safe-password"),
            Map.class)
        .getBody()
        .get("accessToken")
        .toString();
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, String token, Object body, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return testRestTemplate.exchange(
        url(path), method, new HttpEntity<>(body, headers), responseType);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
