package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
  @Autowired private JdbcTemplate jdbc;
  @MockBean private JavaMailSender mail;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @Test
  void acceptsPublicRegistration() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var request =
        new HttpEntity<>(
            "{\"email\":\"User@Example.com\",\"displayName\":\"User\",\"password\":\"safe-password\"}",
            headers);

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/auth/registrations", request, Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void invalidRegistrationReturnsProblemWithFieldErrors() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var response =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/auth/registrations",
            new HttpEntity<>(
                "{\"email\":\"not-an-email\",\"displayName\":\"\",\"password\":\"short\"}",
                headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.getBody()).contains("fieldErrors").contains("email");
  }

  @Test
  void missingVerificationTokenReturnsProblemDetail() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var response =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/auth/email-verifications",
            new HttpEntity<>("{\"token\":\"missing\"}", headers),
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
        "{\"email\":\"" + email + "\",\"displayName\":\"Verify\",\"password\":\"safe-password\"}");
    var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, timeout(1000)).send(message.capture());
    String token = message.getValue().getText().replaceAll(".*token=", "");
    ResponseEntity<Void> first =
        post("/api/v1/auth/email-verifications", "{\"token\":\"" + token + "\"}");
    ResponseEntity<String> second =
        postText("/api/v1/auth/email-verifications", "{\"token\":\"" + token + "\"}");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void commonPasswordIsProblemFieldError() {
    ResponseEntity<String> response =
        postText(
            "/api/v1/auth/registrations",
            "{\"email\":\"common-"
                + System.nanoTime()
                + "@example.com\",\"displayName\":\"Common\",\"password\":\"password\"}");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("fieldErrors").contains("password");
  }

  private ResponseEntity<Void> post(String path, String body) {
    return restTemplate.postForEntity(url(path), new HttpEntity<>(body, headers()), Void.class);
  }

  private ResponseEntity<String> postText(String path, String body) {
    return restTemplate.postForEntity(url(path), new HttpEntity<>(body, headers()), String.class);
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
