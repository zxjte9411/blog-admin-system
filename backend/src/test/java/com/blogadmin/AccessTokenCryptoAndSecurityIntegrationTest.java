package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.blogadmin.identity.application.AccountService;
import com.blogadmin.identity.application.RegistrationService;
import com.blogadmin.identity.application.mail.IdentityEmailEventListener;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.web.security.AccessTokenSecurityConfig;
import com.blogadmin.identity.web.security.JwtToken;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessTokenCryptoAndSecurityIntegrationTest {
  private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JwtEncoder accessTokenEncoder;
  @Autowired private JwtDecoder accessTokenDecoder;
  @Autowired private JwtToken jwtToken;
  @Autowired private UserRepository users;
  @Autowired private RegistrationService registrationService;
  @Autowired private AccountService accountService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactionTemplate;
  @MockitoBean private JavaMailSender mail;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> SECRET);
  }

  @BeforeEach
  void setUp() {
    reset(mail);
    jdbc.update("DELETE FROM auth_rate_limit_events");
  }

  @Test
  void accessTokenCryptoConfigRejectsShortSecret() {
    assertThatThrownBy(() -> new AccessTokenSecurityConfig("short-secret"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 32 bytes");
  }

  @Test
  void validAccessTokenDecodesSuccessfully() {
    User user =
        new User(
            UUID.randomUUID(), "test@example.com", "test@example.com", "Tester", "hash", "zh-TW");
    UUID sessionId = UUID.randomUUID();
    JwtToken.Token token = jwtToken.create(user, sessionId, 1);

    var jwt = accessTokenDecoder.decode(token.value());
    assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
    assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
    assertThat(((Number) jwt.getClaim("ver")).intValue()).isEqualTo(1);
    assertThat(((Number) jwt.getClaim("uver")).intValue()).isEqualTo(0);
    assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
  }

  @Test
  void expiredAccessTokenFailsDecoderValidation() {
    User user =
        new User(
            UUID.randomUUID(), "test@example.com", "test@example.com", "Tester", "hash", "zh-TW");
    UUID sessionId = UUID.randomUUID();

    Instant past = Instant.now().minusSeconds(60);
    JWTClaimsSet claimsSet =
        new JWTClaimsSet.Builder()
            .subject(user.getId().toString())
            .claim("sid", sessionId.toString())
            .claim("ver", 1)
            .claim("uver", 0)
            .expirationTime(Date.from(past))
            .build();

    SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
    try {
      signedJWT.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    String expiredJwt = signedJWT.serialize();

    assertThatThrownBy(() -> accessTokenDecoder.decode(expiredJwt))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void accessTokenWithModifiedSignatureFailsDecoderValidation() {
    User user =
        new User(
            UUID.randomUUID(), "test@example.com", "test@example.com", "Tester", "hash", "zh-TW");
    UUID sessionId = UUID.randomUUID();
    JwtToken.Token token = jwtToken.create(user, sessionId, 1);

    String[] parts = token.value().split("\\.");
    String tampered = parts[0] + "." + parts[1] + ".invalidSignature123456789";

    assertThatThrownBy(() -> accessTokenDecoder.decode(tampered)).isInstanceOf(Exception.class);
  }

  @Test
  void accessTokenWithWrongKeyFailsDecoderValidation() {
    User user =
        new User(
            UUID.randomUUID(), "test@example.com", "test@example.com", "Tester", "hash", "zh-TW");
    UUID sessionId = UUID.randomUUID();

    JWTClaimsSet claimsSet =
        new JWTClaimsSet.Builder()
            .subject(user.getId().toString())
            .claim("sid", sessionId.toString())
            .claim("ver", 1)
            .claim("uver", 0)
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .build();

    SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
    try {
      signedJWT.sign(
          new MACSigner(
              "different-secret-that-is-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    String wrongKeyJwt = signedJWT.serialize();

    assertThatThrownBy(() -> accessTokenDecoder.decode(wrongKeyJwt)).isInstanceOf(Exception.class);
  }

  @Test
  void accessTokenWithWrongAlgorithmFailsDecoderValidation() throws Exception {
    User user =
        new User(
            UUID.randomUUID(), "test@example.com", "test@example.com", "Tester", "hash", "zh-TW");
    UUID sessionId = UUID.randomUUID();

    JWTClaimsSet claimsSet =
        new JWTClaimsSet.Builder()
            .subject(user.getId().toString())
            .claim("sid", sessionId.toString())
            .claim("ver", 1)
            .claim("uver", 0)
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .build();

    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
    kpg.initialize(2048);
    KeyPair kp = kpg.generateKeyPair();
    SignedJWT rsaJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsSet);
    rsaJwt.sign(new RSASSASigner(kp.getPrivate()));

    assertThatThrownBy(() -> accessTokenDecoder.decode(rsaJwt.serialize()))
        .isInstanceOf(Exception.class);

    PlainJWT plainJwt = new PlainJWT(claimsSet);
    assertThatThrownBy(() -> accessTokenDecoder.decode(plainJwt.serialize()))
        .isInstanceOf(Exception.class);
  }

  @Test
  void mailIsNotSentWhenTransactionRollsBack() {
    String email = "rollback-" + System.nanoTime() + "@example.com";
    try {
      transactionTemplate.execute(
          status -> {
            registrationService.register(
                email, "RollbackUser", "safe-password", "zh-TW", "127.0.0.1");
            throw new RuntimeException("Simulate failure triggering rollback");
          });
    } catch (RuntimeException ignored) {
    }

    verify(mail, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void mailIsSentAfterTransactionCommits() {
    String email = "commit-" + System.nanoTime() + "@example.com";
    registrationService.register(email, "CommitUser", "safe-password", "zh-TW", "127.0.0.1");

    verify(mail, timeout(2000)).send(any(SimpleMailMessage.class));
  }

  @Test
  void publicRouteAllowsInvalidBearerTokenWithoutFailing() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth("invalid-bearer-token-should-be-ignored-on-public-endpoint");

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/registrations"),
            new HttpEntity<>(
                Map.of(
                    "email", "public-test-" + System.nanoTime() + "@example.com",
                    "displayName", "PublicUser",
                    "password", "safe-password"),
                headers),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void protectedSessionRouteRejectsInvalidBearerTokenWith401() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth("invalid-bearer-token");

    ResponseEntity<String> response =
        restTemplate.exchange(
            url("/api/v1/auth/sessions"),
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
  }

  @Test
  void protectedSessionRouteRejectsMissingBearerTokenWith401() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        restTemplate.exchange(
            url("/api/v1/auth/sessions"),
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void emailMaskingFormatsProperly() {
    assertThat(IdentityEmailEventListener.maskEmail("johndoe@example.com"))
        .isEqualTo("j***e@example.com");
    assertThat(IdentityEmailEventListener.maskEmail("ab@example.com"))
        .isEqualTo("a***@example.com");
    assertThat(IdentityEmailEventListener.maskEmail(null)).isEqualTo("***");
    assertThat(IdentityEmailEventListener.maskEmail("")).isEqualTo("***");
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
