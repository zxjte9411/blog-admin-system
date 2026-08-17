package com.blogadmin.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserIdentity;
import com.blogadmin.identity.domain.user.UserIdentityRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import com.blogadmin.test.SupabaseJwksTestFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class GoogleAuthenticationApiIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String DEFAULT_PASSWORD = "safe-password";

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AdminUserService adminUserService;
  @Autowired private InvitationRepository invitationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private UserIdentityRepository userIdentityRepository;
  @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
  @Autowired private RefreshSessionRepository refreshSessionRepository;
  @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private JavaMailSender mailSender;

  @DynamicPropertySource
  static void configureSupabaseProperties(DynamicPropertyRegistry registry) {
    SupabaseJwksTestFixture.configureDynamicProperties(registry);
  }

  @BeforeEach
  void cleanDatabase() {
    resetDatabase(jdbcTemplate);
    reset(mailSender);
  }

  @Test
  void googleLoginCreatesVerifiedEnabledAuthorWithoutEmailPasswordOrVerificationEmail() {
    String namedEmail = "new-google@example.com";
    String blankNameEmail = "blank-name@example.com";

    String tokenNamed =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), namedEmail, "Google Display Name", true);
    String tokenBlankName =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), blankNameEmail, null, true);

    assertThat(postGoogleLogin(tokenNamed).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(postGoogleLogin(tokenBlankName).getStatusCode()).isEqualTo(HttpStatus.OK);

    User named =
        userRepository.findAll().stream()
            .filter(user -> user.getEmail().equals(namedEmail))
            .findFirst()
            .orElseThrow();
    User blankName =
        userRepository.findAll().stream()
            .filter(user -> user.getEmail().equals(blankNameEmail))
            .findFirst()
            .orElseThrow();

    assertThat(named.getDisplayName()).isEqualTo("Google Display Name");
    assertThat(blankName.getDisplayName()).isBlank();
    assertThat(List.of(named, blankName))
        .allSatisfy(
            user -> {
              assertThat(user.getRole()).isEqualTo(UserRole.AUTHOR);
              assertThat(user.isEnabled()).isTrue();
              assertThat(user.getVerifiedAt()).isNotNull();
              assertThat(user.getPreferredLanguage()).isEqualTo("zh-TW");
              assertThat(user.getPasswordHash()).isNotBlank();
              assertThat(passwordEncoder.matches(DEFAULT_PASSWORD, user.getPasswordHash()))
                  .isFalse();
            });
    assertThat(named.getPasswordHash()).isNotEqualTo(blankName.getPasswordHash());
    assertThat(emailVerificationTokenRepository.count()).isZero();
  }

  @Test
  void googleLoginUsesSubjectAfterFirstBindingAndKeepsLocalEmail() {
    String originalEmail = "original@example.com";
    String subject = UUID.randomUUID().toString();
    UUID userId = createVerifiedUser();
    assertThat(getUserEmail(userId)).isNotEqualTo(originalEmail);

    User existing = userRepository.findById(userId).orElseThrow();
    existing.changeEmail(originalEmail);
    userRepository.saveAndFlush(existing);

    String firstToken = SupabaseJwksTestFixture.createValidGoogleToken(subject, originalEmail);
    String secondToken =
        SupabaseJwksTestFixture.createValidGoogleToken(subject, "changed@example.com");

    assertThat(postGoogleLogin(firstToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(postGoogleLogin(secondToken).getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(userRepository.findAll()).hasSize(1);
    assertThat(userRepository.findById(userId).orElseThrow().getEmail()).isEqualTo(originalEmail);
  }

  @Test
  void googleLoginRejectsASecondIdentityForAUserWithoutInvitationInvalidationCode() {
    UUID userId = createVerifiedUser();
    String email = getUserEmail(userId);
    userIdentityRepository.saveAndFlush(
        new UserIdentity(UUID.randomUUID(), userId, "google", "already-bound-subject"));

    ResponseEntity<Map> response =
        postGoogleLogin(SupabaseJwksTestFixture.createValidGoogleToken("different-subject", email));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).doesNotContainKey("code");
  }

  @Test
  void googleLoginRejectsUnacceptedJwtClaimsWithoutLeakingReason() {
    String subject = UUID.randomUUID().toString();
    String valid = SupabaseJwksTestFixture.createValidGoogleToken(subject, "claims@example.com");
    int signatureStart = valid.lastIndexOf('.') + 1;

    // Tampered signature
    assertGoogleLoginUnauthorized(
        valid.substring(0, signatureStart) + "!" + valid.substring(signatureStart + 1));
    // RS256 token rejected
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createRs256SignedToken(subject, "claims@example.com"));
    // Wrong issuer
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createToken(
            subject,
            "claims@example.com",
            "https://wrong.example",
            "authenticated",
            300,
            -1,
            "google",
            true,
            "Google User"));
    // Wrong audience
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "wrong",
            300,
            -1,
            "google",
            true,
            "Google User"));
    // Expired token
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            -1,
            -1,
            "google",
            true,
            "Google User"));
    // Not before future
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            300,
            301,
            "google",
            true,
            "Google User"));
    // Wrong provider
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createToken(
            subject,
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            300,
            -1,
            "email",
            true,
            "Google User"));
    // Empty subject
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createToken(
            "",
            "claims@example.com",
            "https://example.supabase.co/auth/v1",
            "authenticated",
            300,
            -1,
            "google",
            true,
            "Google User"));
    // Invalid email format
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createValidGoogleToken(subject, "not-an-email"));
  }

  @Test
  void googleLoginRejectsEs256TokenSignedByDifferentP256Key() {
    String wrongKeyToken =
        SupabaseJwksTestFixture.createAlternateKeySignedToken(
            UUID.randomUUID().toString(), "wrong-key@example.com");
    assertGoogleLoginUnauthorized(wrongKeyToken);
  }

  @Test
  void googleLoginRejectsValidRs256TokenEvenWhenJwksContainsMatchingRsaKey() {
    String rsaToken =
        SupabaseJwksTestFixture.createRs256SignedToken(
            UUID.randomUUID().toString(), "rsa@example.com");
    assertGoogleLoginUnauthorized(rsaToken);
  }

  @Test
  void googleLoginOnlyBindsExistingVerifiedEnabledUser() {
    UUID unverified = createUser(false, true);
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), getUserEmail(unverified)));

    UUID disabled = createUser(true, false);
    assertGoogleLoginUnauthorized(
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), getUserEmail(disabled)));

    assertThat(userIdentityRepository.count()).isZero();
  }

  @Test
  void googleLoginRedeemsMatchingInvitationWithoutPasswordAndInvitationIsOneTime() {
    String email = "google-invited@example.com";
    InvitationLink link = createInvitation(email);
    assertThat(link.invitation().getExpiresAt())
        .isBetween(
            Instant.now().plus(23, ChronoUnit.HOURS), Instant.now().plus(25, ChronoUnit.HOURS));

    Map<String, String> request =
        Map.of(
            "accessToken",
                SupabaseJwksTestFixture.createValidGoogleToken(UUID.randomUUID().toString(), email),
            "invitationToken", link.token());

    assertThat(
            restTemplate
                .postForEntity(url("/api/v1/auth/google"), request, Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    User user =
        userRepository.findAll().stream()
            .filter(candidate -> candidate.getNormalizedEmail().equals(email))
            .findFirst()
            .orElseThrow();
    assertThat(user.getVerifiedAt()).isNotNull();
    assertThat(user.isEnabled()).isTrue();
    assertThat(invitationRepository.findById(link.invitation().getId()).orElseThrow().getUsedAt())
        .isNotNull();

    // Redeeming the second time must be rejected
    ResponseEntity<Map> secondResponse =
        restTemplate.postForEntity(url("/api/v1/auth/google"), request, Map.class);
    assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(secondResponse.getBody()).containsEntry("code", "invitation_invalidated");
  }

  @Test
  void googleLoginRejectsInvitationWhenEmailDoesNotMatch() {
    InvitationLink link = createInvitation("invited@example.com");

    String tokenDifferentEmail =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), "different@example.com");
    assertGoogleLoginUnauthorized(tokenDifferentEmail, link.token());

    assertThat(invitationRepository.findById(link.invitation().getId()).orElseThrow().getUsedAt())
        .isNull();
    assertThat(
            userRepository.findAll().stream()
                .filter(user -> user.getNormalizedEmail().equals("different@example.com")))
        .isEmpty();
  }

  @Test
  void googleLoginRejectsInvitationWhenUserAlreadyExistsWithoutInvalidationCode() {
    UUID userId = createVerifiedUser();
    String email = getUserEmail(userId);
    String token = "existing-user-google-invitation";
    invitationRepository.saveAndFlush(
        new Invitation(
            UUID.randomUUID(),
            email,
            SupabaseJwksTestFixture.sha256(token),
            Instant.now().plus(1, ChronoUnit.DAYS)));

    ResponseEntity<Map> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/google"),
            Map.of(
                "accessToken",
                SupabaseJwksTestFixture.createValidGoogleToken(UUID.randomUUID().toString(), email),
                "invitationToken",
                token),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).doesNotContainKey("code");
  }

  @Test
  void googleLoginRejectsUnverifiedOrInvalidEmailForInvitation() {
    InvitationLink unverified = createInvitation("unverified-google@example.com");
    String unverifiedToken =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), "unverified-google@example.com", "Google User", false);
    assertGoogleLoginUnauthorized(unverifiedToken, unverified.token());
    assertThat(
            invitationRepository
                .findById(unverified.invitation().getId())
                .orElseThrow()
                .getUsedAt())
        .isNull();

    InvitationLink invalid = createInvitation("not-an-email");
    String invalidToken =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), "not-an-email");
    assertGoogleLoginUnauthorized(invalidToken, invalid.token());
    assertThat(
            invitationRepository.findById(invalid.invitation().getId()).orElseThrow().getUsedAt())
        .isNull();
  }

  @Test
  void googleLoginRejectsExpiredInvitation() {
    String token = "expired-google-invitation";
    Invitation invitation =
        invitationRepository.saveAndFlush(
            new Invitation(
                UUID.randomUUID(),
                "expired-google@example.com",
                SupabaseJwksTestFixture.sha256(token),
                Instant.now().minusSeconds(1)));

    String supabaseToken =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), invitation.getEmail());
    ResponseEntity<Map> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/google"),
            Map.of("accessToken", supabaseToken, "invitationToken", token),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("code", "invitation_invalidated");
    assertThat(invitationRepository.findById(invitation.getId()).orElseThrow().getUsedAt())
        .isNull();
  }

  @Test
  void googleLoginMarksAnInvalidatedInvitationWithAStableErrorCode() {
    String token = "missing-google-invitation";
    String supabaseToken =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), "google-invited@example.com");

    ResponseEntity<Map> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/google"),
            Map.of("accessToken", supabaseToken, "invitationToken", token),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).containsEntry("code", "invitation_invalidated");
  }

  @Test
  void googleLoginRejectsInvitationWhenDisplayNameIsInvalid() {
    InvitationLink blankName = createInvitation("blank-google-name@example.com");
    String blankNameToken =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), blankName.invitation().getEmail(), "  ", true);
    assertGoogleLoginUnauthorized(blankNameToken, blankName.token());
    assertThat(
            invitationRepository.findById(blankName.invitation().getId()).orElseThrow().getUsedAt())
        .isNull();

    InvitationLink longName = createInvitation("long-google-name@example.com");
    String longNameToken =
        SupabaseJwksTestFixture.createValidGoogleToken(
            UUID.randomUUID().toString(), longName.invitation().getEmail(), "x".repeat(101), true);
    assertGoogleLoginUnauthorized(longNameToken, longName.token());
    assertThat(
            invitationRepository.findById(longName.invitation().getId()).orElseThrow().getUsedAt())
        .isNull();
  }

  @Test
  void googleLoginUserCanResetPasswordWithoutAcceptingInvalidPassword() {
    String email = "google-reset@example.com";
    String googleToken =
        SupabaseJwksTestFixture.createValidGoogleToken(UUID.randomUUID().toString(), email);

    assertThat(postGoogleLogin(googleToken).getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(
            restTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets"), Map.of("email", email), Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    ArgumentCaptor<SimpleMailMessage> mailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender, atLeastOnce()).send(mailCaptor.capture());
    SimpleMailMessage resetMail = mailCaptor.getValue();
    assertThat(resetMail.getTo()).containsExactly(email);
    String resetToken = extractTokenFromMail(resetMail.getText());

    assertThat(
            restTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets/" + resetToken),
                    Map.of("password", "password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(
            restTemplate
                .postForEntity(
                    url("/api/v1/auth/password-resets/" + resetToken),
                    Map.of("password", "reset-password"),
                    Void.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(
            restTemplate
                .postForEntity(
                    url("/api/v1/auth/login"),
                    Map.of("email", email, "password", "reset-password"),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private UUID createVerifiedUser() {
    return createUser(true, true);
  }

  private UUID createUser(boolean verified, boolean enabled) {
    UUID id = UUID.randomUUID();
    String email = "user-" + id + "@example.com";
    User user =
        userRepository.save(
            new User(id, email, email, "User", passwordEncoder.encode(DEFAULT_PASSWORD), "zh-TW"));
    if (verified) user.verify(Instant.now());
    if (!enabled) user.disable();
    userRepository.saveAndFlush(user);
    return id;
  }

  private String getUserEmail(UUID userId) {
    return userRepository.findById(userId).orElseThrow().getEmail();
  }

  private ResponseEntity<Map> postGoogleLogin(String token) {
    return restTemplate.postForEntity(
        url("/api/v1/auth/google"), Map.of("accessToken", token), Map.class);
  }

  private void assertGoogleLoginUnauthorized(String token) {
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/google"), Map.of("accessToken", token), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).doesNotContain("signature", "issuer", "audience", "provider");
  }

  private void assertGoogleLoginUnauthorized(String accessToken, String invitationToken) {
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            url("/api/v1/auth/google"),
            Map.of("accessToken", accessToken, "invitationToken", invitationToken),
            String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).doesNotContain("invitation_invalidated");
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
