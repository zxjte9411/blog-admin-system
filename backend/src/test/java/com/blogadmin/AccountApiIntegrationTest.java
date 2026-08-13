package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.blogadmin.identity.domain.emailchange.*;
import com.blogadmin.identity.domain.invitation.*;
import com.blogadmin.identity.domain.password.*;
import com.blogadmin.identity.domain.ratelimit.*;
import com.blogadmin.identity.domain.session.*;
import com.blogadmin.identity.domain.user.*;
import com.blogadmin.identity.domain.verification.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
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
  @MockitoBean JavaMailSender mail;

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
  void currentAccountReturnsFrontendIdentityAndRoleWithoutSensitiveFields() {
    User user = user();
    user.changeRole(UserRole.ADMIN);
    user.updateProfile("Current User", "en");
    users.saveAndFlush(user);
    String access = login(user);

    ResponseEntity<Map> response =
        exchange("/api/v1/account/me", HttpMethod.GET, access, null, Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "id",
                user.getId().toString(),
                "displayName",
                "Current User",
                "preferredLanguage",
                "en",
                "role",
                "ADMIN"));
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

  @Test
  void emailChangeKeepsOldAccessTokenAndSendsBilingualNotifications() {
    User u = user();
    String oldEmail = u.getEmail();
    String newEmail = "new-email@example.com";
    String access = login(u);

    assertThat(
            exchange(
                    "/api/v1/account/email",
                    HttpMethod.POST,
                    access,
                    Map.of("email", newEmail),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            exchange(
                    "/api/v1/account/profile",
                    HttpMethod.PATCH,
                    access,
                    Map.of("displayName", "Still signed in", "preferredLanguage", "zh-TW"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, atLeastOnce()).send(sent.capture());
    SimpleMailMessage request =
        sent.getAllValues().stream()
            .filter(m -> newEmail.equals(m.getTo()[0]) && m.getText().contains("confirm-email"))
            .findFirst()
            .orElseThrow();
    String token = tokenFrom(request);

    assertThat(
            rest.postForEntity(url("/api/v1/auth/email-changes/" + token), null, Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(users.findById(u.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);

    verify(mail, times(3)).send(sent.capture());
    List<SimpleMailMessage> notifications =
        sent.getAllValues().stream()
            .filter(m -> m.getText().contains("Your email was changed."))
            .toList();
    assertThat(notifications).hasSize(2);
    assertThat(notifications)
        .extracting(m -> m.getTo()[0])
        .containsExactlyInAnyOrder(oldEmail, newEmail);
    assertThat(notifications)
        .allSatisfy(
            m -> assertThat(m.getText()).contains("Your email was changed.", "您的 Email 已變更。"));
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
    return tokenFrom(text);
  }

  private String tokenFrom(SimpleMailMessage message) {
    return tokenFrom(message.getText());
  }

  private String tokenFrom(String text) {
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
