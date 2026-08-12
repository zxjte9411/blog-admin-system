package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.blogadmin.identity.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired RefreshSessionRepository sessions;
  @Autowired PasswordResetTokenRepository resetTokens;
  @Autowired EmailChangeTokenRepository emailTokens;
  @MockBean JavaMailSender mail;

  @BeforeEach
  void clear() {
    sessions.deleteAll();
    resetTokens.deleteAll();
    emailTokens.deleteAll();
    users.deleteAll();
    reset(mail);
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @Test
  void profileAndPasswordChangeAreExposed() {
    User u = user();
    String access = login(u);
    assertThat(
            exchange(
                    "/api/v1/account/profile",
                    HttpMethod.PATCH,
                    access,
                    Map.of("displayName", "New", "preferredLanguage", "en"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(users.findById(u.getId()).orElseThrow().getDisplayName()).isEqualTo("New");
    assertThat(users.findById(u.getId()).orElseThrow().getPreferredLanguage()).isEqualTo("en");
    assertThat(
            exchange(
                    "/api/v1/account/password",
                    HttpMethod.PUT,
                    access,
                    Map.of(
                        "currentPassword",
                        "safe-password",
                        "newPassword",
                        "new-safe-password",
                        "logoutCurrentSession",
                        true),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            passwords.matches(
                "new-safe-password", users.findById(u.getId()).orElseThrow().getPasswordHash()))
        .isTrue();
  }

  @Test
  void passwordResetResendAndEmailChangeUseOneTimeLocalizedTokens() {
    User u = user();
    String access = login(u);
    assertThat(
            rest.postForEntity(
                    url("/api/v1/auth/password-resets"), Map.of("email", u.getEmail()), Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    String first = tokenFromLastMail();
    rest.postForEntity(
        url("/api/v1/auth/password-resets"), Map.of("email", u.getEmail()), Void.class);
    String second = tokenFromLastMail();
    assertThat(second).isNotEqualTo(first);
    assertThat(
            rest.postForEntity(
                    url("/api/v1/auth/password-resets/" + first),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            rest.postForEntity(
                    url("/api/v1/auth/password-resets/" + first),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            rest.postForEntity(
                    url("/api/v1/auth/password-resets/" + second),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    access = login(u, "reset-password");
    assertThat(
            exchange(
                    "/api/v1/account/email",
                    HttpMethod.POST,
                    access,
                    Map.of("email", "new@example.com"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(tokenFromLastMail()).isNotBlank();
    ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, atLeastOnce()).send(c.capture());
    assertThat(c.getAllValues().get(c.getAllValues().size() - 1).getTo())
        .containsExactly("new@example.com");
  }

  @Test
  void emailCollisionReturnsConflict() {
    User u = user();
    user();
    String access = login(u);
    assertThat(
            exchange(
                    "/api/v1/account/email",
                    HttpMethod.POST,
                    access,
                    Map.of("email", users.findAll().get(1).getEmail()),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(users.findById(u.getId()).orElseThrow().getEmail()).isEqualTo(u.getEmail());
  }

  private User user() {
    UUID id = UUID.randomUUID();
    String e = id + "@example.com";
    User u = users.save(new User(id, e, e, "User", passwords.encode("safe-password"), "zh-TW"));
    u.verify(Instant.now());
    return users.saveAndFlush(u);
  }

  private String login(User u) {
    return login(u, "safe-password");
  }

  private String login(User u, String password) {
    return ((Map)
            rest.postForEntity(
                    url("/api/v1/auth/login"),
                    Map.of("email", u.getEmail(), "password", password),
                    Map.class)
                .getBody())
        .get("accessToken")
        .toString();
  }

  private String tokenFromLastMail() {
    ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, atLeastOnce()).send(c.capture());
    String text = c.getAllValues().get(c.getAllValues().size() - 1).getText();
    return text.substring(text.indexOf("token=") + 6).split("[ &)]")[0];
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, String access, Object body, Class<T> type) {
    HttpHeaders h = new HttpHeaders();
    if (access != null) h.setBearerAuth(access);
    h.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(url(path), method, new HttpEntity<>(body, h), type);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
