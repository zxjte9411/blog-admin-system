package com.blogadmin.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
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

class UsernamePasswordAuthenticationApiIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String DEFAULT_PASSWORD = "safe-password";

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    resetDatabase(jdbcTemplate);
  }

  @Test
  void loginAndRefreshExposeSecureRotatingCookie() {
    UUID userId = createVerifiedUser();
    ResponseEntity<Map> loginResponse =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String initialCookie = extractRefreshTokenCookie(loginResponse);
    String initialAccessToken = (String) loginResponse.getBody().get("accessToken");
    assertThat(loginResponse.getBody()).containsKeys("accessToken", "accessTokenExpiresAt");
    assertThat(loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .contains("HttpOnly", "Secure", "Max-Age=604800");

    ResponseEntity<Map> refreshResponse =
        postWithCookie("/api/v1/auth/refresh", initialCookie, Map.class);
    assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refreshResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Secure");
    assertThat(extractRefreshTokenCookie(refreshResponse)).isNotEqualTo(initialCookie);

    // Old rotated cookie cannot be reused
    assertThat(postWithCookie("/api/v1/auth/refresh", initialCookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    // Old access token invalidated after rotation
    assertThat(getWithBearerToken("/api/v1/auth/sessions", initialAccessToken).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void invalidPasswordUnverifiedAndDisabledUsersCannotLogin() {
    UUID verified = createVerifiedUser();
    assertThat(
            restTemplate
                .postForEntity(
                    url("/api/v1/auth/login"),
                    Map.of("email", getUserEmail(verified), "password", "wrong-password"),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    UUID unverified = createUser(false, true);
    assertThat(
            restTemplate
                .postForEntity(url("/api/v1/auth/login"), loginPayload(unverified), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    UUID disabled = createUser(true, false);
    assertThat(
            restTemplate
                .postForEntity(url("/api/v1/auth/login"), loginPayload(disabled), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void logoutRevokesRefreshCookieAndInvalidatesSession() {
    UUID userId = createVerifiedUser();
    ResponseEntity<Map> loginResponse =
        restTemplate.postForEntity(url("/api/v1/auth/login"), loginPayload(userId), Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String cookie = extractRefreshTokenCookie(loginResponse);
    String accessToken = (String) loginResponse.getBody().get("accessToken");

    ResponseEntity<Void> logoutResponse = postWithCookie("/api/v1/auth/logout", cookie, Void.class);
    assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(postWithCookie("/api/v1/auth/refresh", cookie, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(getWithBearerToken("/api/v1/auth/sessions", accessToken).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private UUID createVerifiedUser() {
    return createUser(true, true);
  }

  private UUID createUser(boolean verified, boolean enabled) {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.com";
    User user =
        userRepository.save(
            new User(id, email, email, "User", passwordEncoder.encode(DEFAULT_PASSWORD), "zh-TW"));
    if (verified) user.verify(Instant.now());
    if (!enabled) user.disable();
    return userRepository.saveAndFlush(user).getId();
  }

  private String getUserEmail(UUID userId) {
    return userRepository.findById(userId).orElseThrow().getEmail();
  }

  private Map<String, String> loginPayload(UUID userId) {
    return Map.of("email", getUserEmail(userId), "password", DEFAULT_PASSWORD);
  }

  private <T> ResponseEntity<T> postWithCookie(String path, String cookie, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, "refresh_token=" + cookie);
    return restTemplate.exchange(
        url(path), HttpMethod.POST, new HttpEntity<>(null, headers), responseType);
  }

  private ResponseEntity<String> getWithBearerToken(String path, String bearerToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(bearerToken);
    return restTemplate.exchange(
        url(path), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
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
