package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserIdentityRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationApiIntegrationTest {
  private static final String PASSWORD = "safe-password";
  private static final KeyPair GOOGLE_KEY = keyPair();
  private static final HttpServer JWKS_SERVER = jwksServer();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PasswordEncoder passwords;
  @Autowired private UserRepository users;
  @Autowired private UserIdentityRepository identities;
  @Autowired private PasswordResetTokenRepository resetTokens;
  @Autowired private RefreshSessionRepository sessions;
  @Autowired private EmailVerificationTokenRepository tokens;
  @Autowired private RateLimitEventRepository limits;
  @MockitoBean private JavaMailSender mail;
  @PersistenceContext private EntityManager entityManager;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
    registry.add("app.security.supabase.issuer", () -> "https://example.supabase.co/auth/v1");
    registry.add("app.security.supabase.audience", () -> "authenticated");
    registry.add(
        "app.security.supabase.jwks-url",
        () -> "http://localhost:" + JWKS_SERVER.getAddress().getPort());
  }

  @AfterAll
  static void stopJwksServer() {
    JWKS_SERVER.stop(0);
  }

  @Test
  void googleLoginCreatesVerifiedEnabledAuthorWithoutEmailPasswordOrVerificationEmail() {
    String namedEmail = "new-google@example.com";
    String blankNameEmail = "blank-name@example.com";

    assertThat(
            post(
                    "/api/v1/auth/google",
                    Map.of(
                        "accessToken",
                        supabaseToken(
                            UUID.randomUUID().toString(), namedEmail, "Google Display Name", true)),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            post(
                    "/api/v1/auth/google",
                    Map.of(
                        "accessToken",
                        supabaseToken(UUID.randomUUID().toString(), blankNameEmail, null, true)),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    User named =
        users.findAll().stream()
            .filter(user -> user.getEmail().equals(namedEmail))
            .findFirst()
            .orElseThrow();
    User blankName =
        users.findAll().stream()
            .filter(user -> user.getEmail().equals(blankNameEmail))
            .findFirst()
            .orElseThrow();
    assertThat(named.getDisplayName()).isEqualTo("Google Display Name");
    assertThat(blankName.getDisplayName()).isBlank();
    assertThat(List.of(named, blankName))
        .allSatisfy(
            user -> {
              assertThat(user.getRole()).isEqualTo(UserRole.AUTHOR);
              assertThat(user.isEnabled()).isTrue();
              assertThat(user.getVerifiedAt()).isNotNull();
              assertThat(user.getPreferredLanguage()).isEqualTo("zh-TW");
              assertThat(user.getPasswordHash()).isNotBlank();
              assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isFalse();
            });
    assertThat(named.getPasswordHash()).isNotEqualTo(blankName.getPasswordHash());
    assertThat(tokens.count()).isZero();
  }

  @Test
  void googleLoginUsesSubjectAfterFirstBindingAndKeepsLocalEmail() {
    String originalEmail = "original@example.com";
    String subject = UUID.randomUUID().toString();
    UUID userId = createUser(true, true);
    assertThat(email(userId)).isNotEqualTo(originalEmail);

    User existing = users.findById(userId).orElseThrow();
    existing.changeEmail(originalEmail);
    users.saveAndFlush(existing);

    assertThat(
            post(
                    "/api/v1/auth/google",
                    Map.of("accessToken", supabaseToken(subject, originalEmail)),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            post(
                    "/api/v1/auth/google",
                    Map.of("accessToken", supabaseToken(subject, "changed@example.com")),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    assertThat(users.findAll()).hasSize(1);
    assertThat(users.findById(userId).orElseThrow().getEmail()).isEqualTo(originalEmail);
  }

  @Test
  void googleLoginRejectsUnacceptedJwtClaimsWithoutLeakingReason() {
    String subject = UUID.randomUUID().toString();
    String valid = supabaseToken(subject, "claims@example.com");
    int signatureStart = valid.lastIndexOf('.') + 1;
    assertGoogleUnauthorized(
        valid.substring(0, signatureStart) + "A" + valid.substring(signatureStart + 1));
    assertGoogleUnauthorized(
        supabaseToken(
            subject,
            "claims@example.com",
            "https://wrong.example",
            "authenticated",
            300,
            -1,
            "google",
            true));
    assertGoogleUnauthorized(
        supabaseToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "wrong",
            300,
            -1,
            "google",
            true));
    assertGoogleUnauthorized(
        supabaseToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            -1,
            -1,
            "google",
            true));
    assertGoogleUnauthorized(
        supabaseToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            300,
            301,
            "google",
            true));
    assertGoogleUnauthorized(
        supabaseToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            300,
            -1,
            "email",
            true));
    assertGoogleUnauthorized(
        supabaseToken(
            "",
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            300,
            -1,
            "google",
            true));
  }

  @Test
  void googleLoginOnlyBindsExistingVerifiedEnabledUser() {
    UUID unverified = createUser(false, true);
    assertGoogleUnauthorized(supabaseToken(UUID.randomUUID().toString(), email(unverified)));
    UUID disabled = createUser(true, false);
    assertGoogleUnauthorized(supabaseToken(UUID.randomUUID().toString(), email(disabled)));
    assertThat(identities.count()).isZero();
  }

  @Test
  void googleLoginUserCanResetPasswordWithoutAcceptingInvalidPassword() {
    String email = "google-reset@example.com";
    assertThat(
            post(
                    "/api/v1/auth/google",
                    Map.of("accessToken", supabaseToken(UUID.randomUUID().toString(), email)),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    assertThat(
            post("/api/v1/auth/password-resets", Map.of("email", email), Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, atLeastOnce()).send(mailCaptor.capture());
    SimpleMailMessage resetMail = mailCaptor.getValue();
    assertThat(resetMail.getTo()).containsExactly(email);
    String resetToken = resetToken(resetMail.getText());

    assertThat(
            post(
                    "/api/v1/auth/password-resets/" + resetToken,
                    Map.of("password", "password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            post(
                    "/api/v1/auth/password-resets/" + resetToken,
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            post(
                    "/api/v1/auth/login",
                    Map.of("email", email, "password", "reset-password"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @BeforeEach
  void clearDatabase() {
    sessions.deleteAll();
    resetTokens.deleteAll();
    tokens.deleteAll();
    limits.deleteAll();
    identities.deleteAll();
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

  private String resetToken(String text) {
    return text.substring(text.indexOf("token=") + 6).split("[ &)]")[0];
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

  private static KeyPair keyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static HttpServer jwksServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      String modulus =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  ((java.security.interfaces.RSAPublicKey) GOOGLE_KEY.getPublic())
                      .getModulus()
                      .toByteArray());
      String exponent =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  ((java.security.interfaces.RSAPublicKey) GOOGLE_KEY.getPublic())
                      .getPublicExponent()
                      .toByteArray());
      server.createContext(
          "/",
          exchange -> {
            byte[] body =
                ("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"google-test\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""
                        + modulus
                        + "\",\"e\":\""
                        + exponent
                        + "\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      return server;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String supabaseToken(String subject, String email) {
    return supabaseToken(
        subject,
        email,
        "https://example.supabase.co/auth/v1",
        "authenticated",
        300,
        -1,
        "google",
        true);
  }

  private static String supabaseToken(
      String subject, String email, String displayName, boolean emailVerified) {
    return supabaseToken(
        subject,
        email,
        "https://example.supabase.co/auth/v1",
        "authenticated",
        300,
        -1,
        "google",
        emailVerified,
        displayName);
  }

  private static String supabaseToken(
      String subject,
      String email,
      String issuer,
      String audience,
      long expiresIn,
      long notBeforeOffset,
      String provider,
      boolean emailVerified) {
    return supabaseToken(
        subject,
        email,
        issuer,
        audience,
        expiresIn,
        notBeforeOffset,
        provider,
        emailVerified,
        "Google User");
  }

  private static String supabaseToken(
      String subject,
      String email,
      String issuer,
      String audience,
      long expiresIn,
      long notBeforeOffset,
      String provider,
      boolean emailVerified,
      String displayName) {
    long now = Instant.now().getEpochSecond();
    String header = base64("{\"alg\":\"RS256\",\"kid\":\"google-test\",\"typ\":\"JWT\"}");
    String payload =
        base64(
            "{\"iss\":\""
                + issuer
                + "\",\"aud\":\""
                + audience
                + "\",\"sub\":\""
                + subject
                + "\",\"email\":\""
                + email
                + "\",\"email_verified\":"
                + emailVerified
                + ",\"app_metadata\":{\"provider\":\""
                + provider
                + "\"},\"user_metadata\":"
                + (displayName == null ? "{}" : "{\"name\":\"" + displayName + "\"}")
                + ",\"nbf\":"
                + (now + notBeforeOffset)
                + ",\"exp\":"
                + (now + expiresIn)
                + "}");
    try {
      String signingInput = header + "." + payload;
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(GOOGLE_KEY.getPrivate());
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String base64(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private void assertGoogleUnauthorized(String token) {
    ResponseEntity<String> response =
        post("/api/v1/auth/google", Map.of("accessToken", token), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).doesNotContain("signature", "issuer", "audience", "provider");
  }
}
