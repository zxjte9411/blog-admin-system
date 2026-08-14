package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
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
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.Locale;
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

class AdminUserApiIntegrationTest extends AbstractPostgresIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate testRestTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private InvitationRepository invitationRepository;
  @Autowired private PasswordSettingRepository passwordSettingRepository;
  @Autowired private PasswordSettingChangeRepository passwordSettingChangeRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private RateLimitEventRepository rateLimitEventRepository;
  @MockitoBean private JavaMailSender mailSender;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clearDatabase() {
    resetDatabase(jdbcTemplate);
    reset(mailSender);
  }

  @Test
  void adminCanListAndUpdateOtherUsersButNotThemselves() {
    User admin = createUser(UserRole.ADMIN, true);
    User target = createUser(UserRole.AUTHOR, true);
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
    User admin = createUser(UserRole.ADMIN, true);
    createUser("Alice@example.com", "No query match", UserRole.AUTHOR, true);
    createUser("name@example.com", "Alice Display", UserRole.AUTHOR, true);
    createUser("alice-admin@example.com", "Alice Admin", UserRole.ADMIN, true);
    createUser("alice-disabled@example.com", "Alice Disabled", UserRole.AUTHOR, false);
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
    User admin = createUser(UserRole.ADMIN, true);
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
    User admin = createUser(UserRole.ADMIN, true);
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
    String firstToken = sentToken();
    assertThat(
            exchange(
                    "/api/v1/admin/invitations",
                    HttpMethod.POST,
                    token,
                    Map.of("email", email),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    String secondToken = sentToken();
    assertThat(secondToken).isNotEqualTo(firstToken);

    ResponseEntity<String> listing =
        exchange("/api/v1/admin/invitations", HttpMethod.GET, token, null, String.class);
    assertThat(listing.getBody())
        .doesNotContain("tokenHash")
        .doesNotContain(firstToken)
        .doesNotContain(secondToken);
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
                    "/api/v1/auth/invitations/" + firstToken + "/redeem",
                    HttpMethod.POST,
                    null,
                    redeem,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    "/api/v1/auth/invitations/" + secondToken + "/redeem",
                    HttpMethod.POST,
                    null,
                    redeem,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            exchange(
                    "/api/v1/auth/invitations/" + secondToken + "/redeem",
                    HttpMethod.POST,
                    null,
                    redeem,
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void invitationForExistingUserReturnsConflictAndMailIsBilingual() {
    User admin = createUser(UserRole.ADMIN, true);
    User existing = createUser(UserRole.AUTHOR, true);
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
    verify(mailSender).send(captor.capture());
    assertThat(captor.getValue().getSubject()).contains("Invitation", "邀請");
    assertThat(captor.getValue().getText())
        .contains("You are invited", "您收到邀請", "24 hours", "24 小時");
  }

  @Test
  void passwordMinimumAcceptsBoundsAndPersistsAudit() {
    User admin = createUser(UserRole.ADMIN, true);
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
    assertThat(passwordSettingRepository.findById(true).orElseThrow().getMinimumLength())
        .isEqualTo(128);
    assertThat(passwordSettingChangeRepository.findAll()).hasSize(1);
    var audit = passwordSettingChangeRepository.findAll().get(0);
    assertThat(audit.getOperatorId()).isEqualTo(admin.getId());
    assertThat(audit.getPreviousValue()).isEqualTo(8);
    assertThat(audit.getNewValue()).isEqualTo(128);
    assertThat(audit.getChangedAt()).isNotNull();
  }

  @Test
  void readsCurrentPasswordMinimumLength() {
    User admin = createUser(UserRole.ADMIN, true);
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
    verify(mailSender, atLeastOnce()).send(captor.capture());
    String text = captor.getAllValues().get(captor.getAllValues().size() - 1).getText();
    return text.substring(text.indexOf("token=") + 6, text.indexOf(" (valid"));
  }

  private User createUser(UserRole role, boolean verified) {
    UUID id = UUID.randomUUID();
    String email = id + "@example.com";
    return createUser(email, "User", role, true, verified);
  }

  private User createUser(String email, String displayName, UserRole role, boolean enabled) {
    return createUser(email, displayName, role, enabled, true);
  }

  private User createUser(
      String email, String displayName, UserRole role, boolean enabled, boolean verified) {
    User user =
        userRepository.save(
            new User(
                UUID.randomUUID(),
                email,
                email.toLowerCase(Locale.ROOT),
                displayName,
                passwordEncoder.encode("safe-password"),
                "zh-TW"));
    user.changeRole(role);
    user.setEnabled(enabled);
    if (verified) {
      user.verify(Instant.now());
    }
    return userRepository.saveAndFlush(user);
  }

  private String login(User user) {
    return ((Map)
            testRestTemplate
                .postForEntity(
                    url("/api/v1/auth/login"),
                    Map.of("email", user.getEmail(), "password", "safe-password"),
                    Map.class)
                .getBody())
        .get("accessToken")
        .toString();
  }

  private <T> ResponseEntity<T> exchange(
      String path, HttpMethod method, String token, Object body, Class<T> responseType) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return testRestTemplate.exchange(
        url(path), method, new HttpEntity<>(body, headers), responseType);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
