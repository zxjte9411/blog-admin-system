package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.blogadmin.identity.application.RegistrationService;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationApiIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @MockitoBean private JavaMailSender mail;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @Test
  void acceptsPublicRegistration() {
    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/registrations"),
            new HttpEntity<>(
                json(
                    Map.of(
                        "email", "User@Example.com",
                        "displayName", "User",
                        "password", "safe-password")),
                headers()),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void invalidRegistrationReturnsProblemWithFieldErrors() {
    var response =
        restTemplate.postForEntity(
            url("/api/v1/auth/registrations"),
            new HttpEntity<>(
                json(Map.of("email", "not-an-email", "displayName", "", "password", "short")),
                headers()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.getBody()).contains("fieldErrors").contains("email");
  }

  @Test
  void missingVerificationTokenReturnsProblemDetail() {
    var response =
        restTemplate.postForEntity(
            url("/api/v1/auth/email-verifications"),
            new HttpEntity<>(json(Map.of("token", "missing")), headers()),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.getBody()).contains("Email verification token not found");
  }

  @Test
  void verificationIsNoContentAndTokenCannotBeReused() {
    String email = "verify-" + System.nanoTime() + "@example.com";
    post(
        "/api/v1/auth/registrations",
        Map.of("email", email, "displayName", "Verify", "password", "safe-password"));
    var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, timeout(1000)).send(message.capture());
    String token = message.getValue().getText().replaceAll(".*token=", "");
    ResponseEntity<Void> first = post("/api/v1/auth/email-verifications", Map.of("token", token));
    ResponseEntity<String> second =
        postText("/api/v1/auth/email-verifications", Map.of("token", token));
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void commonPasswordIsProblemFieldError() {
    ResponseEntity<String> response =
        postText(
            "/api/v1/auth/registrations",
            Map.of(
                "email", "common-" + System.nanoTime() + "@example.com",
                "displayName", "Common",
                "password", "password"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("fieldErrors").contains("password");
  }

  @Test
  void mailFailureIsLoggedWithoutSecretsAndRegistrationStillCommits() {
    String email = "mail-failure-" + System.nanoTime() + "@example.com";
    String password = "safe-password";
    users.saveAndFlush(
        new User(UUID.randomUUID(), email, email, "User", "stored-password", "zh-TW"));
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RegistrationService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    doThrow(new IllegalStateException("mail failed: " + email + " token=secret"))
        .when(mail)
        .send(any(SimpleMailMessage.class));

    try {
      ResponseEntity<Void> response =
          restTemplate.postForEntity(
              url("/api/v1/auth/email-verifications/resend"),
              new HttpEntity<>(json(Map.of("email", email)), headers()),
              Void.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from email_verification_tokens where user_id = "
                      + "(select id from users where email = ?)",
                  Integer.class,
                  email))
          .isEqualTo(1);
      assertThat(appender.list)
          .anySatisfy(
              event ->
                  assertThat(event.getFormattedMessage())
                      .contains("Identity email delivery failed")
                      .doesNotContain(email, password, "token=", "secret"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  private ResponseEntity<Void> post(String path, Object body) {
    return restTemplate.postForEntity(
        url(path), new HttpEntity<>(json(body), headers()), Void.class);
  }

  private ResponseEntity<String> postText(String path, Object body) {
    return restTemplate.postForEntity(
        url(path), new HttpEntity<>(json(body), headers()), String.class);
  }

  private String json(Object body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize test request", exception);
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private HttpHeaders headers() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
