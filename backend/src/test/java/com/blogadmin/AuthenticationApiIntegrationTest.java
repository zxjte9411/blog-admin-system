package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class AuthenticationApiIntegrationTest {
  private static final String PASSWORD = "safe-password";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PasswordEncoder passwords;
  @Autowired private UserRepository users;
  @Autowired private RefreshSessionRepository sessions;
  @Autowired private EmailVerificationTokenRepository tokens;
  @Autowired private RateLimitEventRepository limits;
  @PersistenceContext private EntityManager entityManager;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @BeforeEach
  void clearDatabase() {
    sessions.deleteAll();
    tokens.deleteAll();
    limits.deleteAll();
    users.deleteAll();
  }

  @Test
  void loginAndRefreshExposeSecureRotatingCookie() {
    UUID user = createUser(true, true);
    ResponseEntity<Map> login = post("/api/v1/auth/login", loginRequest(user), Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String oldCookie = cookie(login);
    String oldAccessToken = (String) login.getBody().get("accessToken");
    assertThat(login.getBody()).containsKeys("accessToken", "accessTokenExpiresAt");
    assertThat(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .contains("HttpOnly", "Secure", "Max-Age=604800");

    ResponseEntity<Map> refresh = postWithCookie("/api/v1/auth/refresh", oldCookie, Map.class);
    assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refresh.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Secure");
    assertThat(cookie(refresh)).isNotEqualTo(oldCookie);
    assertThat(postWithCookie("/api/v1/auth/refresh", oldCookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            exchange(
                    "/api/v1/auth/sessions",
                    HttpMethod.GET,
                    headers(oldAccessToken, null),
                    null,
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void invalidPasswordUnverifiedAndDisabledUsersCannotLogin() {
    UUID verified = createUser(true, true);
    assertThat(
            post("/api/v1/auth/login", loginRequest(verified, "wrong"), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    UUID unverified = createUser(false, true);
    assertThat(post("/api/v1/auth/login", loginRequest(unverified), String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    UUID disabled = createUser(true, false);
    assertThat(post("/api/v1/auth/login", loginRequest(disabled), String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(verified).isNotEqualTo(unverified).isNotEqualTo(disabled);
  }

  @Test
  void logoutRevokesRefreshCookie() {
    UUID user = createUser(true, true);
    ResponseEntity<Map> login = post("/api/v1/auth/login", loginRequest(user), Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cookie = cookie(login);
    String accessToken = (String) login.getBody().get("accessToken");
    ResponseEntity<Void> logout = postWithCookie("/api/v1/auth/logout", cookie, Void.class);
    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(postWithCookie("/api/v1/auth/refresh", cookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            exchange(
                    "/api/v1/auth/sessions",
                    HttpMethod.GET,
                    headers(accessToken, null),
                    null,
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void refreshRotationPreservesSessionIdentityAndUpdatesLastUsedAt() {
    UUID user = createUser(true, true);
    ResponseEntity<Map> login = post("/api/v1/auth/login", loginRequest(user), Map.class);
    String refreshCookie = cookie(login);
    var before = sessions.findAll().get(0);
    postWithCookie("/api/v1/auth/refresh", refreshCookie, Map.class);
    var after = sessions.findById(before.getId()).orElseThrow();
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    assertThat(after.getLastUsedAt()).isAfterOrEqualTo(before.getLastUsedAt());
  }

  @Test
  void adminAuthorizationUsesRole() throws Exception {
    UUID user = createUser(true, true);
    String authorAfterRoleToken =
        (String)
            post("/api/v1/auth/login", loginRequest(user), Map.class).getBody().get("accessToken");
    assertThat(
            exchange(
                    "/api/v1/admin/probe",
                    HttpMethod.GET,
                    headers(authorAfterRoleToken, null),
                    null,
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    User admin = users.findById(user).orElseThrow();
    admin.changeRole(UserRole.ADMIN);
    users.saveAndFlush(admin);
    entityManager.clear();
    ResponseEntity<Map> adminLogin = post("/api/v1/auth/login", loginRequest(user), Map.class);
    assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    String adminCookie = cookie(adminLogin);
    String token = (String) adminLogin.getBody().get("accessToken");
    assertThat(
            exchange(
                    "/api/v1/admin/probe", HttpMethod.GET, headers(token, null), null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    admin.changeRole(UserRole.AUTHOR);
    users.saveAndFlush(admin);
    assertThat(postWithCookie("/api/v1/auth/refresh", adminCookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            exchange(
                    "/api/v1/admin/probe", HttpMethod.GET, headers(token, null), null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    String authorToken =
        (String)
            post("/api/v1/auth/login", loginRequest(user), Map.class).getBody().get("accessToken");
    assertThat(
            exchange(
                    "/api/v1/admin/probe",
                    HttpMethod.GET,
                    headers(authorToken, null),
                    null,
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    ResponseEntity<String> sessionsResponse =
        exchange(
            "/api/v1/auth/sessions",
            HttpMethod.GET,
            headers(authorToken, null),
            null,
            String.class);
    List sessions = new ObjectMapper().readValue(sessionsResponse.getBody(), List.class);
    assertThat(sessions).hasSize(1);
  }

  @Test
  void sessionsShowCurrentWithoutRefreshToken() throws Exception {
    UUID user = createUser(true, true);
    ResponseEntity<Map> first = post("/api/v1/auth/login", loginRequest(user), Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    String firstCookie = cookie(first);
    String accessToken = (String) first.getBody().get("accessToken");
    String secondCookie = cookie(post("/api/v1/auth/login", loginRequest(user), Map.class));

    ResponseEntity<String> response =
        exchange(
            "/api/v1/auth/sessions",
            HttpMethod.GET,
            headers(accessToken, null),
            null,
            String.class);
    List body = new ObjectMapper().readValue(response.getBody(), List.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(body).hasSize(2);
    assertThat(body).allSatisfy(session -> assertThat(session.toString()).doesNotContain("token"));
    assertThat(body.stream().filter(s -> Boolean.TRUE.equals(((Map) s).get("current")))).hasSize(1);
    assertThat(secondCookie).isNotEqualTo(firstCookie);
  }

  @Test
  void otherSessionCanBeDeletedButCurrentCannot() throws Exception {
    UUID user = createUser(true, true);
    ResponseEntity<Map> first = post("/api/v1/auth/login", loginRequest(user), Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    String firstCookie = cookie(first);
    String accessToken = (String) first.getBody().get("accessToken");
    ResponseEntity<Map> otherLogin = post("/api/v1/auth/login", loginRequest(user), Map.class);
    String otherCookie = cookie(otherLogin);
    String otherAccessToken = (String) otherLogin.getBody().get("accessToken");
    ResponseEntity<String> sessionsResponse =
        exchange(
            "/api/v1/auth/sessions",
            HttpMethod.GET,
            headers(accessToken, firstCookie),
            null,
            String.class);
    List sessions = new ObjectMapper().readValue(sessionsResponse.getBody(), List.class);
    UUID otherId = UUID.fromString((String) ((Map) sessions.get(0)).get("id"));
    UUID currentId = UUID.fromString((String) ((Map) sessions.get(1)).get("id"));

    assertThat(
            exchange(
                    "/api/v1/auth/sessions/" + otherId,
                    HttpMethod.DELETE,
                    headers(accessToken, firstCookie),
                    null,
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(postWithCookie("/api/v1/auth/refresh", otherCookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            exchange(
                    "/api/v1/auth/sessions",
                    HttpMethod.GET,
                    headers(otherAccessToken, null),
                    null,
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            exchange(
                    "/api/v1/auth/sessions/" + currentId,
                    HttpMethod.DELETE,
                    headers(accessToken, firstCookie),
                    null,
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void bearerTokenIsRejectedImmediatelyWhenUserIsDisabled() {
    UUID user = createUser(true, true);
    String accessToken =
        (String)
            post("/api/v1/auth/login", loginRequest(user), Map.class).getBody().get("accessToken");
    User disabled = users.findById(user).orElseThrow();
    disabled.disable();
    users.saveAndFlush(disabled);
    entityManager.clear();
    assertThat(
            exchange(
                    "/api/v1/auth/sessions",
                    HttpMethod.GET,
                    headers(accessToken, null),
                    null,
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private UUID createUser(boolean verified, boolean enabled) {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.com";
    User user = users.save(new User(id, email, email, "User", passwords.encode(PASSWORD), "zh-TW"));
    if (verified) user.verify(Instant.now());
    if (!enabled) user.disable();
    users.saveAndFlush(user);
    return id;
  }

  private Map<String, String> loginRequest(UUID user) {
    return loginRequest(user, PASSWORD);
  }

  private Map<String, String> loginRequest(UUID user, String password) {
    return Map.of("email", email(user), "password", password);
  }

  private String email(UUID user) {
    return users.findById(user).orElseThrow().getEmail();
  }

  private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
    return exchange(path, HttpMethod.POST, headers(null, null), body, type);
  }

  private <T> ResponseEntity<T> postWithCookie(String path, String cookie, Class<T> type) {
    return exchange(path, HttpMethod.POST, headers(null, cookie), null, type);
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, HttpHeaders headers, Object body, Class<T> type) {
    return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), type);
  }

  private HttpHeaders headers(String bearer, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (bearer != null) headers.setBearerAuth(bearer);
    if (cookie != null) headers.add(HttpHeaders.COOKIE, "refresh_token=" + cookie);
    return headers;
  }

  private String cookie(ResponseEntity<?> response) {
    String value =
        response.getHeaders().values().stream()
            .flatMap(List::stream)
            .filter(header -> header.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(() -> new AssertionError(response.getHeaders()));
    return value.substring(value.indexOf('=') + 1, value.indexOf(';'));
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
