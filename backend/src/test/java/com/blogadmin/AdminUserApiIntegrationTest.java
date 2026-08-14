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
import java.util.Locale;
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
  void adminUserListSearchesTrimmedCaseInsensitiveTextWithoutChangingOtherFilters() {
    User admin = user(UserRole.ADMIN, true);
    user("Alice@example.com", "No query match", UserRole.AUTHOR, true);
    user("name@example.com", "Alice Display", UserRole.AUTHOR, true);
    user("alice-admin@example.com", "Alice Admin", UserRole.ADMIN, true);
    user("alice-disabled@example.com", "Alice Disabled", UserRole.AUTHOR, false);
    String token = login(admin);

    for (String query : new String[] {"", "&q=", "&q=++"}) {
      ResponseEntity<Object[]> response =
          exchange(
              "/api/v1/admin/users?role=AUTHOR&enabled=true" + query,
              HttpMethod.GET,
              token,
              null,
              Object[].class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).hasSize(2);
    }

    ResponseEntity<Map[]> search =
        exchange(
            "/api/v1/admin/users?role=AUTHOR&enabled=true&q=+aLiCe+",
            HttpMethod.GET,
            token,
            null,
            Map[].class);
    assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(search.getBody()).hasSize(2);
    assertThat(search.getBody()[0].get("email")).isIn("Alice@example.com", "name@example.com");
    assertThat(search.getBody()[1].get("email"))
        .isIn("Alice@example.com", "name@example.com")
        .isNotEqualTo(search.getBody()[0].get("email"));
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

  @Test
  void reads_the_current_password_minimum_length() {
    User admin = user(UserRole.ADMIN, true);
    String token = login(admin);
    ResponseEntity<Map> response =
        exchange(
            "/api/v1/admin/settings/password-minimum-length",
            HttpMethod.GET,
            token,
            null,
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("value", 8);
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
    return user(email, "User", role, true, verified);
  }

  private User user(String email, String displayName, UserRole role, boolean enabled) {
    return user(email, displayName, role, enabled, true);
  }

  private User user(
      String email, String displayName, UserRole role, boolean enabled, boolean verified) {
    User u =
        users.save(
            new User(
                UUID.randomUUID(),
                email,
                email.toLowerCase(Locale.ROOT),
                displayName,
                passwords.encode("safe-password"),
                "zh-TW"));
    u.changeRole(role);
    u.setEnabled(enabled);
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
