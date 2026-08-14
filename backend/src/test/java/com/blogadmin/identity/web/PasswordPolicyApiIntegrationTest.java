package com.blogadmin.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import java.time.Instant;
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

class PasswordPolicyApiIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String DEFAULT_PASSWORD = "safe-password";

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AdminUserService adminUserService;
  @Autowired private InvitationRepository invitationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private PasswordSettingRepository passwordSettingRepository;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private JavaMailSender mailSender;

  @BeforeEach
  void cleanDatabase() {
    resetDatabase(jdbcTemplate);
    reset(mailSender);
  }

  @Test
  void passwordPolicyRejectsCommonAndOverlongPasswordsAtAllFourEntrances() {
    assertPasswordRejectedAtAllFourEntrances("password123");
    assertPasswordRejectedAtAllFourEntrances("x".repeat(129));
  }

  @Test
  void passwordMinimumChangeAppliesToAllFourPasswordEntrances() {
    UUID adminId = createAdminUser();
    String adminToken = login(adminId);

    HttpHeaders adminHeaders = new HttpHeaders();
    adminHeaders.setBearerAuth(adminToken);
    adminHeaders.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<Map> updateResponse =
        restTemplate.exchange(
            url("/api/v1/admin/settings/password-minimum-length"),
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("value", 12), adminHeaders),
            Map.class);
    assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    assertPasswordRejectedAtAllFourEntrances("shortpass");
    assertPasswordAcceptedAtAllFourEntrances("valid-pass12");
  }

  private void assertPasswordRejectedAtAllFourEntrances(String password) {
    // 1. Registration entrance
    ResponseEntity<String> regResponse =
        restTemplate.postForEntity(
            url("/api/v1/auth/registrations"),
            Map.of(
                "email",
                "policy-" + UUID.randomUUID() + "@example.com",
                "displayName",
                "Policy User",
                "password",
                password),
            String.class);
    assertThat(regResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // 2. Invitation redeem entrance
    InvitationLink invitation = createInvitation("policy-" + UUID.randomUUID() + "@example.com");
    ResponseEntity<String> redeemResponse =
        restTemplate.postForEntity(
            url("/api/v1/auth/invitations/" + invitation.token() + "/redeem"),
            Map.of(
                "displayName", "Policy User",
                "password", password,
                "preferredLanguage", "zh-TW"),
            String.class);
    assertThat(redeemResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // 3. Password reset entrance
    UUID resetUser = createVerifiedUser();
    restTemplate.postForEntity(
        url("/api/v1/auth/password-resets"), Map.of("email", getUserEmail(resetUser)), Void.class);
    ArgumentCaptor<SimpleMailMessage> resetMail = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(resetMail.capture());
    String resetToken = extractTokenFromMail(resetMail.getValue().getText());

    ResponseEntity<String> resetResponse =
        restTemplate.postForEntity(
            url("/api/v1/auth/password-resets/" + resetToken),
            Map.of("password", password),
            String.class);
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // 4. Account password change entrance
    UUID changeUser = createVerifiedUser();
    String changeToken = login(changeUser);
    HttpHeaders changeHeaders = new HttpHeaders();
    changeHeaders.setBearerAuth(changeToken);
    changeHeaders.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> changeResponse =
        restTemplate.exchange(
            url("/api/v1/account/password"),
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "currentPassword", DEFAULT_PASSWORD,
                    "newPassword", password,
                    "logoutCurrentSession", false),
                changeHeaders),
            String.class);
    assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private void assertPasswordAcceptedAtAllFourEntrances(String password) {
    // 1. Registration entrance
    ResponseEntity<Void> regResponse =
        restTemplate.postForEntity(
            url("/api/v1/auth/registrations"),
            Map.of(
                "email",
                "policy-" + UUID.randomUUID() + "@example.com",
                "displayName",
                "Policy User",
                "password",
                password),
            Void.class);
    assertThat(regResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // 2. Invitation redeem entrance
    InvitationLink invitation = createInvitation("policy-" + UUID.randomUUID() + "@example.com");
    ResponseEntity<Map> redeemResponse =
        restTemplate.postForEntity(
            url("/api/v1/auth/invitations/" + invitation.token() + "/redeem"),
            Map.of(
                "displayName", "Policy User",
                "password", password,
                "preferredLanguage", "zh-TW"),
            Map.class);
    assertThat(redeemResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    // 3. Password reset entrance
    UUID resetUser = createVerifiedUser();
    restTemplate.postForEntity(
        url("/api/v1/auth/password-resets"), Map.of("email", getUserEmail(resetUser)), Void.class);
    ArgumentCaptor<SimpleMailMessage> resetMail = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(resetMail.capture());
    String resetToken = extractTokenFromMail(resetMail.getValue().getText());

    ResponseEntity<Void> resetResponse =
        restTemplate.postForEntity(
            url("/api/v1/auth/password-resets/" + resetToken),
            Map.of("password", password),
            Void.class);
    assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    // 4. Account password change entrance
    UUID changeUser = createVerifiedUser();
    String changeToken = login(changeUser);
    HttpHeaders changeHeaders = new HttpHeaders();
    changeHeaders.setBearerAuth(changeToken);
    changeHeaders.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<Void> changeResponse =
        restTemplate.exchange(
            url("/api/v1/account/password"),
            HttpMethod.PUT,
            new HttpEntity<>(
                Map.of(
                    "currentPassword", DEFAULT_PASSWORD,
                    "newPassword", password,
                    "logoutCurrentSession", false),
                changeHeaders),
            Void.class);
    assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  private UUID createVerifiedUser() {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.com";
    User user =
        userRepository.save(
            new User(id, email, email, "User", passwordEncoder.encode(DEFAULT_PASSWORD), "zh-TW"));
    user.verify(Instant.now());
    return userRepository.saveAndFlush(user).getId();
  }

  private UUID createAdminUser() {
    UUID id = UUID.randomUUID();
    String email = "admin-" + id + "@example.com";
    User user =
        userRepository.save(
            new User(id, email, email, "Admin", passwordEncoder.encode(DEFAULT_PASSWORD), "zh-TW"));
    user.verify(Instant.now());
    user.changeRole(UserRole.ADMIN);
    return userRepository.saveAndFlush(user).getId();
  }

  private String getUserEmail(UUID userId) {
    return userRepository.findById(userId).orElseThrow().getEmail();
  }

  private String login(UUID userId) {
    return (String)
        restTemplate
            .postForEntity(
                url("/api/v1/auth/login"),
                Map.of("email", getUserEmail(userId), "password", DEFAULT_PASSWORD),
                Map.class)
            .getBody()
            .get("accessToken");
  }

  private InvitationLink createInvitation(String email) {
    Invitation invitation = adminUserService.invite(email);
    ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(mailCaptor.capture());
    return new InvitationLink(invitation, extractTokenFromMail(mailCaptor.getValue().getText()));
  }

  private String extractTokenFromMail(String text) {
    return text.substring(text.indexOf("token=") + 6).split("[ &)]")[0];
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private record InvitationLink(Invitation invitation, String token) {}
}
