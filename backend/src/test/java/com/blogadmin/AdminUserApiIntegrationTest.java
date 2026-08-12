package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordSettingChangeRepository;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
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
class AdminUserApiIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired InvitationRepository invitations;
  @Autowired PasswordSettingRepository passwordSettings;
  @Autowired PasswordSettingChangeRepository passwordChanges;
  @Autowired EmailVerificationTokenRepository emailTokens;
  @Autowired RefreshSessionRepository sessions;
  @Autowired RateLimitEventRepository rateLimits;
  @PersistenceContext EntityManager entityManager;
  @MockitoBean JavaMailSender mail;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
  }

  @BeforeEach
  void clearDatabase() {
    sessions.deleteAll();
    emailTokens.deleteAll();
    rateLimits.deleteAll();
    invitations.deleteAll();
    jdbc.execute("TRUNCATE TABLE password_setting_changes");
    users.deleteAll();
    jdbc.update("UPDATE password_settings SET minimum_length = 8 WHERE id = TRUE");
    reset(mail);
  }

  @Test
  void adminCanListAndUpdateOtherUsersButNotThemselves() {
    User admin = user(UserRole.ADMIN, true);
    User target = user(UserRole.AUTHOR, true);
    String token = login(admin);
    ResponseEntity<Object[]> list =
        exchange(
            "/api/v1/admin/users?role=AUTHOR&enabled=true",
            HttpMethod.GET,
            token,
            null,
            Object[].class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).hasSize(1);
    assertThat(
            exchange(
                    "/api/v1/admin/users/" + target.getId(),
                    HttpMethod.PATCH,
                    token,
                    Map.of("role", "ADMIN", "enabled", false),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/admin/users/" + admin.getId(),
                    HttpMethod.PATCH,
                    token,
                    Map.of("enabled", false),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void cannotRemoveLastEnabledVerifiedAdminAndPasswordSettingIsAudited() {
    User admin = user(UserRole.ADMIN, true);
    String token = login(admin);
    assertThat(
            exchange(
                    "/api/v1/admin/users/" + admin.getId(),
                    HttpMethod.PATCH,
                    token,
                    Map.of("enabled", false),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            exchange(
                    "/api/v1/admin/settings/password-minimum-length",
                    HttpMethod.PUT,
                    token,
                    Map.of("value", 12),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/admin/settings/password-minimum-length/history",
                    HttpMethod.GET,
                    token,
                    null,
                    Object[].class)
                .getBody())
        .hasSize(1);
  }

  @Test
  void invitationResendRedeemIsOneTimeAndDoesNotExposeHash() {
    User admin = user(UserRole.ADMIN, true);
    String token = login(admin);
    String email = "invite@example.com";

    assertThat(
            exchange(
                    "/api/v1/admin/invitations",
                    HttpMethod.POST,
                    token,
                    Map.of("email", email),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    String first = sentToken();
    assertThat(
            exchange(
                    "/api/v1/admin/invitations",
                    HttpMethod.POST,
                    token,
                    Map.of("email", email),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    String second = sentToken();
    assertThat(second).isNotEqualTo(first);

    ResponseEntity<String> listing =
        exchange("/api/v1/admin/invitations", HttpMethod.GET, token, null, String.class);
    assertThat(listing.getBody())
        .doesNotContain("tokenHash")
        .doesNotContain(first)
        .doesNotContain(second);
    Map<String, String> redeem =
        Map.of(
            "displayName",
            "Invited User",
            "password",
            "invite-password",
            "preferredLanguage",
            "en");
    assertThat(
            exchange(
                    "/api/v1/auth/invitations/" + first + "/redeem",
                    HttpMethod.POST,
                    null,
                    redeem,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    "/api/v1/auth/invitations/" + second + "/redeem",
                    HttpMethod.POST,
                    null,
                    redeem,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/auth/invitations/" + second + "/redeem",
                    HttpMethod.POST,
                    null,
                    redeem,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void invitationForExistingUserReturnsConflictAndMailIsBilingual() {
    User admin = user(UserRole.ADMIN, true);
    User existing = user(UserRole.AUTHOR, true);
    String token = login(admin);
    ResponseEntity<Void> response =
        exchange(
            "/api/v1/admin/invitations",
            HttpMethod.POST,
            token,
            Map.of("email", existing.getEmail()),
            Void.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            exchange(
                    "/api/v1/admin/invitations",
                    HttpMethod.POST,
                    token,
                    Map.of("email", "bilingual@example.com"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(captor.capture());
    assertThat(captor.getValue().getSubject()).contains("Invitation", "邀請");
    assertThat(captor.getValue().getText())
        .contains("You are invited", "您收到邀請", "24 hours", "24 小時");
  }

  @Test
  void passwordMinimumAcceptsBoundsAndPersistsAudit() {
    User admin = user(UserRole.ADMIN, true);
    String token = login(admin);
    assertThat(
            exchange(
                    "/api/v1/admin/settings/password-minimum-length",
                    HttpMethod.PUT,
                    token,
                    Map.of("value", 8),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/admin/settings/password-minimum-length",
                    HttpMethod.PUT,
                    token,
                    Map.of("value", 128),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/admin/settings/password-minimum-length",
                    HttpMethod.PUT,
                    token,
                    Map.of("value", 7),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            exchange(
                    "/api/v1/admin/settings/password-minimum-length",
                    HttpMethod.PUT,
                    token,
                    Map.of("value", 129),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(passwordSettings.findById(true).orElseThrow().getMinimumLength()).isEqualTo(128);
    assertThat(passwordChanges.findAll()).hasSize(1);
    var audit = passwordChanges.findAll().get(0);
    assertThat(audit.getOperatorId()).isEqualTo(admin.getId());
    assertThat(audit.getPreviousValue()).isEqualTo(8);
    assertThat(audit.getNewValue()).isEqualTo(128);
    assertThat(audit.getChangedAt()).isNotNull();
  }

  private String sentToken() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
    String text = captor.getAllValues().get(captor.getAllValues().size() - 1).getText();
    return text.substring(text.indexOf("token=") + 6, text.indexOf(" (valid"));
  }

  private User user(UserRole role, boolean verified) {
    UUID id = UUID.randomUUID();
    String email = id + "@example.com";
    User u =
        users.save(new User(id, email, email, "User", passwords.encode("safe-password"), "zh-TW"));
    u.changeRole(role);
    if (verified) u.verify(Instant.now());
    return users.saveAndFlush(u);
  }

  private String login(User u) {
    return ((Map)
            rest.postForEntity(
                    url("/api/v1/auth/login"),
                    Map.of("email", u.getEmail(), "password", "safe-password"),
                    Map.class)
                .getBody())
        .get("accessToken")
        .toString();
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, String token, Object body, Class<T> type) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(url(path), method, new HttpEntity<>(body, h), type);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
