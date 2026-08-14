package com.blogadmin.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.session.RefreshSession;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class SessionManagementApiIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String DEFAULT_PASSWORD = "safe-password";

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  @BeforeEach
  void cleanDatabase() {
    resetDatabase(jdbcTemplate);
  }

  @Test
  void refreshRotationPreservesSessionIdentityAndUpdatesLastUsedAt() {
    UUID userId = createVerifiedUser();
    ResponseEntity<Map> loginResponse =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    String refreshCookie = extractRefreshTokenCookie(loginResponse);

    RefreshSession before = refreshSessionRepository.findAll().get(0);
    postWithCookie("/api/v1/auth/refresh", refreshCookie, Map.class);
    RefreshSession after = refreshSessionRepository.findById(before.getId()).orElseThrow();

    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    assertThat(after.getLastUsedAt()).isAfterOrEqualTo(before.getLastUsedAt());
  }

  @Test
  void refreshTokenWithMismatchedAccessTokenVersionIsPermanentlyRejected() {
    UUID userId = createVerifiedUser();
    ResponseEntity<Map> loginResponse =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    String refreshCookie = extractRefreshTokenCookie(loginResponse);

    RefreshSession sessionBefore = refreshSessionRepository.findAll().get(0);
    assertThat(sessionBefore.getUserAccessTokenVersion()).isEqualTo(0);
    assertThat(sessionBefore.getRevokedAt()).isNull();

    // Bump user accessTokenVersion via role change (or password change)
    User user = userRepository.findById(userId).orElseThrow();
    user.changeRole(UserRole.ADMIN);
    userRepository.saveAndFlush(user);
    entityManager.clear();

    // Refresh request must be rejected with 401 UNAUTHORIZED
    ResponseEntity<Map> refreshResponse =
        postWithCookie("/api/v1/auth/refresh", refreshCookie, Map.class);
    assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    // Verify DB state: version mismatch invalidates the session via epoch comparison
    entityManager.clear();
    RefreshSession reloadedSession =
        refreshSessionRepository.findById(sessionBefore.getId()).orElseThrow();
    assertThat(reloadedSession.getUserAccessTokenVersion()).isEqualTo(0);
    assertThat(reloadedSession.getRevokedAt())
        .as("Revocation is enforced via userAccessTokenVersion epoch mismatch rather than DB write")
        .isNull();

    // Subsequent refresh attempts must consistently and permanently fail
    ResponseEntity<Map> secondRefreshResponse =
        postWithCookie("/api/v1/auth/refresh", refreshCookie, Map.class);
    assertThat(secondRefreshResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    // Active session list query strictly excludes mismatched versions
    List<RefreshSession> activeSessions =
        refreshSessionRepository
            .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndUserAccessTokenVersionEqualsOrderByCreatedAtDesc(
                userId, Instant.now(), user.getAccessTokenVersion());
    assertThat(activeSessions).isEmpty();
  }

  @Test
  void adminAuthorizationUsesRoleAndRevokesSessionsOnRoleDemotion() throws Exception {
    UUID userId = createVerifiedUser();
    String authorToken = loginAndGetAccessToken(userId);

    // Initial author cannot access admin endpoint
    assertThat(getWithBearerToken("/api/v1/admin/probe", authorToken).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    // Promote to admin
    User admin = userRepository.findById(userId).orElseThrow();
    admin.changeRole(UserRole.ADMIN);
    userRepository.saveAndFlush(admin);
    entityManager.clear();

    ResponseEntity<Map> adminLogin =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    String adminCookie = extractRefreshTokenCookie(adminLogin);
    String adminToken = (String) adminLogin.getBody().get("accessToken");

    // Endpoint exists/authorized (probe returns 404 because /api/v1/admin/probe doesn't exist, but
    // NOT 403 Forbidden)
    assertThat(getWithBearerToken("/api/v1/admin/probe", adminToken).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    // Demote back to author
    admin.changeRole(UserRole.AUTHOR);
    userRepository.saveAndFlush(admin);

    // Previous admin session and token are now invalid
    assertThat(postWithCookie("/api/v1/auth/refresh", adminCookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(getWithBearerToken("/api/v1/admin/probe", adminToken).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    // New login as author
    String newAuthorToken = loginAndGetAccessToken(userId);
    assertThat(getWithBearerToken("/api/v1/admin/probe", newAuthorToken).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<String> sessionsResponse =
        getWithBearerToken("/api/v1/auth/sessions", newAuthorToken);
    List<?> sessions = objectMapper.readValue(sessionsResponse.getBody(), List.class);
    assertThat(sessions).hasSize(1);
  }

  @Test
  void sessionsShowCurrentWithoutRefreshToken() throws Exception {
    UUID userId = createVerifiedUser();
    ResponseEntity<Map> firstLogin =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    assertThat(firstLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    String firstAccessToken = (String) firstLogin.getBody().get("accessToken");
    String firstCookie = extractRefreshTokenCookie(firstLogin);

    ResponseEntity<Map> secondLogin =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    String secondCookie = extractRefreshTokenCookie(secondLogin);

    ResponseEntity<String> response = getWithBearerToken("/api/v1/auth/sessions", firstAccessToken);
    List<?> sessions = objectMapper.readValue(response.getBody(), List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sessions).hasSize(2);
    assertThat(sessions)
        .allSatisfy(session -> assertThat(session.toString()).doesNotContain("token"));
    assertThat(sessions.stream().filter(s -> Boolean.TRUE.equals(((Map<?, ?>) s).get("current"))))
        .hasSize(1);
    assertThat(secondCookie).isNotEqualTo(firstCookie);
  }

  @Test
  void otherSessionCanBeDeletedButCurrentCannot() throws Exception {
    UUID userId = createVerifiedUser();
    ResponseEntity<Map> firstLogin =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    String firstCookie = extractRefreshTokenCookie(firstLogin);
    String firstAccessToken = (String) firstLogin.getBody().get("accessToken");

    ResponseEntity<Map> secondLogin =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    String secondCookie = extractRefreshTokenCookie(secondLogin);
    String secondAccessToken = (String) secondLogin.getBody().get("accessToken");

    ResponseEntity<String> sessionsResponse =
        exchangeWithAuth(
            "/api/v1/auth/sessions",
            HttpMethod.GET,
            firstAccessToken,
            firstCookie,
            null,
            String.class);
    List<?> sessions = objectMapper.readValue(sessionsResponse.getBody(), List.class);
    UUID otherSessionId = UUID.fromString((String) ((Map<?, ?>) sessions.get(0)).get("id"));
    UUID currentSessionId = UUID.fromString((String) ((Map<?, ?>) sessions.get(1)).get("id"));

    // Revoke the other session -> 204
    ResponseEntity<Void> deleteOther =
        exchangeWithAuth(
            "/api/v1/auth/sessions/" + otherSessionId,
            HttpMethod.DELETE,
            firstAccessToken,
            firstCookie,
            null,
            Void.class);
    assertThat(deleteOther.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    // Other session cannot refresh or access endpoints anymore
    assertThat(postWithCookie("/api/v1/auth/refresh", secondCookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(getWithBearerToken("/api/v1/auth/sessions", secondAccessToken).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    // Attempting to delete current session via this endpoint returns 404 (logout endpoint is used
    // instead)
    ResponseEntity<Void> deleteCurrent =
        exchangeWithAuth(
            "/api/v1/auth/sessions/" + currentSessionId,
            HttpMethod.DELETE,
            firstAccessToken,
            firstCookie,
            null,
            Void.class);
    assertThat(deleteCurrent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private UUID createVerifiedUser() {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.com";
    User user =
        userRepository.save(
            new User(id, email, email, "User", passwordEncoder.encode(DEFAULT_PASSWORD), "zh-TW"));
    user.verify(Instant.now());
    return userRepository.saveAndFlush(user).getId();
  }

  private String getUserEmail(UUID userId) {
    return userRepository.findById(userId).orElseThrow().getEmail();
  }

  private Map<String, String> loginPayload(UUID userId) {
    return Map.of("email", getUserEmail(userId), "password", DEFAULT_PASSWORD);
  }

  private String loginAndGetAccessToken(UUID userId) {
    return (String)
        restTemplate
            .postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class)
            .getBody()
            .get("accessToken");
  }

  private <T> ResponseEntity<T> postWithCookie(String path, String cookie, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, "refresh_token=" + cookie);
    return restTemplate.exchange(
        url(path), HttpMethod.POST, new HttpEntity<>(null, headers), responseType);
  }

  private ResponseEntity<String> getWithBearerToken(String path, String bearerToken) {
    return exchangeWithAuth(path, HttpMethod.GET, bearerToken, null, null, String.class);
  }

  private <T> ResponseEntity<T> exchangeWithAuth(
      String path,
      HttpMethod method,
      String bearerToken,
      String cookie,
      Object body,
      Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (bearerToken != null) {
      headers.setBearerAuth(bearerToken);
    }
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, "refresh_token=" + cookie);
    }
    return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), responseType);
  }

  private String extractRefreshTokenCookie(ResponseEntity<?> response) {
    String cookieHeader =
        response.getHeaders().values().stream()
            .flatMap(List::stream)
            .filter(header -> header.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Missing refresh_token cookie in: " + response.getHeaders()));
    return cookieHeader.substring(cookieHeader.indexOf('=') + 1, cookieHeader.indexOf(';'));
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
