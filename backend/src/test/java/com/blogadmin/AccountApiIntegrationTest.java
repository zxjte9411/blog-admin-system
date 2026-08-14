package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.blogadmin.identity.domain.emailchange.EmailChangeTokenRepository;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class AccountApiIntegrationTest extends AbstractPostgresIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate testRestTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private EmailChangeTokenRepository emailChangeTokenRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private JavaMailSender mailSender;

  @BeforeEach
  void clear() {
    resetDatabase(jdbcTemplate);
    reset(mailSender);
  }

  @Test
  void profileAndPasswordChangeAreExposed() {
    User user = createUser();
    String accessToken = login(user);
    assertThat(
            exchange(
                    "/api/v1/account/profile",
                    HttpMethod.PATCH,
                    accessToken,
                    Map.of("displayName", "New", "preferredLanguage", "en"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(userRepository.findById(user.getId()).orElseThrow().getDisplayName())
        .isEqualTo("New");
    assertThat(userRepository.findById(user.getId()).orElseThrow().getPreferredLanguage())
        .isEqualTo("en");
    assertThat(
            exchange(
                    "/api/v1/account/password",
                    HttpMethod.PUT,
                    accessToken,
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
            passwordEncoder.matches(
                "new-safe-password",
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash()))
        .isTrue();
  }

  @Test
  void currentAccountReturnsFrontendIdentityAndRoleWithoutSensitiveFields() {
    User user = createUser();
    user.changeRole(UserRole.ADMIN);
    user.updateProfile("Current User", "en");
    userRepository.saveAndFlush(user);
    String accessToken = login(user);

    ResponseEntity<Map> response =
        exchange("/api/v1/account/me", HttpMethod.GET, accessToken, null, Map.class);

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
    User user = createUser();
    String accessToken = login(user);
    assertThat(
            testRestTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets"),
                    Map.of("email", user.getEmail()),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    String firstToken = tokenFromLastMail();
    testRestTemplate.postForEntity(
        url("/api/v1/auth/password-resets"), Map.of("email", user.getEmail()), Void.class);
    String secondToken = tokenFromLastMail();
    assertThat(secondToken).isNotEqualTo(firstToken);
    assertThat(
            testRestTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets/" + firstToken),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            testRestTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets/" + firstToken),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            testRestTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets/" + secondToken),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    accessToken = login(user, "reset-password");
    assertThat(
            exchange(
                    "/api/v1/account/email",
                    HttpMethod.POST,
                    accessToken,
                    Map.of("email", "new@example.com"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(tokenFromLastMail()).isNotBlank();
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(captor.capture());
    assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getTo())
        .containsExactly("new@example.com");
  }

  @Test
  void emailCollisionReturnsConflict() {
    User user = createUser();
    createUser();
    String accessToken = login(user);
    assertThat(
            exchange(
                    "/api/v1/account/email",
                    HttpMethod.POST,
                    accessToken,
                    Map.of("email", userRepository.findAll().get(1).getEmail()),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail())
        .isEqualTo(user.getEmail());
  }

  @Test
  void emailChangeKeepsOldAccessTokenAndSendsBilingualNotifications() {
    User user = createUser();
    String oldEmail = user.getEmail();
    String newEmail = "new-email@example.com";
    String accessToken = login(user);

    assertThat(
            exchange(
                    "/api/v1/account/email",
                    HttpMethod.POST,
                    accessToken,
                    Map.of("email", newEmail),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            exchange(
                    "/api/v1/account/profile",
                    HttpMethod.PATCH,
                    accessToken,
                    Map.of("displayName", "Still signed in", "preferredLanguage", "zh-TW"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(sent.capture());
    SimpleMailMessage requestMessage =
        sent.getAllValues().stream()
            .filter(
                message ->
                    newEmail.equals(message.getTo()[0])
                        && message.getText().contains("confirm-email"))
            .findFirst()
            .orElseThrow();
    String token = tokenFrom(requestMessage);

    assertThat(
            testRestTemplate
                .postForEntity(url("/api/v1/auth/email-changes/" + token), null, Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);

    verify(mailSender, times(3)).send(sent.capture());
    List<SimpleMailMessage> notifications =
        sent.getAllValues().stream()
            .filter(message -> message.getText().contains("Your email was changed."))
            .toList();
    assertThat(notifications).hasSize(2);
    assertThat(notifications)
        .extracting(message -> message.getTo()[0])
        .containsExactlyInAnyOrder(oldEmail, newEmail);
    assertThat(notifications)
        .allSatisfy(
            message ->
                assertThat(message.getText()).contains("Your email was changed.", "您的 Email 已變更。"));
  }

  private User createUser() {
    UUID id = UUID.randomUUID();
    String email = id + "@example.com";
    User user =
        userRepository.save(
            new User(id, email, email, "User", passwordEncoder.encode("safe-password"), "zh-TW"));
    user.verify(Instant.now());
    return userRepository.saveAndFlush(user);
  }

  private String login(User user) {
    return login(user, "safe-password");
  }

  private String login(User user, String password) {
    return ((Map)
            testRestTemplate
                .postForEntity(
                    url("/api/v1/auth/login"),
                    Map.of("email", user.getEmail(), "password", password),
                    Map.class)
                .getBody())
        .get("accessToken")
        .toString();
  }

  private String tokenFromLastMail() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(captor.capture());
    String text = captor.getAllValues().get(captor.getAllValues().size() - 1).getText();
    return tokenFrom(text);
  }

  private String tokenFrom(SimpleMailMessage message) {
    return tokenFrom(message.getText());
  }

  private String tokenFrom(String text) {
    return text.substring(text.indexOf("token=") + 6).split("[ &)]")[0];
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, String accessToken, Object body, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    if (accessToken != null) {
      headers.setBearerAuth(accessToken);
    }
    headers.setContentType(MediaType.APPLICATION_JSON);
    return testRestTemplate.exchange(
        url(path), method, new HttpEntity<>(body, headers), responseType);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
