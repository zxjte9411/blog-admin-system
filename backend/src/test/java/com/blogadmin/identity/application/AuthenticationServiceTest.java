package com.blogadmin.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blogadmin.identity.domain.session.RefreshSession;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserIdentity;
import com.blogadmin.identity.domain.user.UserIdentityRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.web.security.SupabaseJwtVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationServiceTest {

  private UserRepository userRepository;
  private RefreshSessionRepository refreshSessionRepository;
  private PasswordEncoder passwordEncoder;
  private UserIdentityRepository userIdentityRepository;
  private SupabaseJwtVerifier supabaseJwtVerifier;
  private AdminUserService adminUserService;
  private AuthenticationService authenticationService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    refreshSessionRepository = mock(RefreshSessionRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    userIdentityRepository = mock(UserIdentityRepository.class);
    supabaseJwtVerifier = mock(SupabaseJwtVerifier.class);
    adminUserService = mock(AdminUserService.class);

    when(passwordEncoder.encode(any())).thenReturn("encoded-random-password");

    authenticationService =
        new AuthenticationService(
            userRepository,
            refreshSessionRepository,
            passwordEncoder,
            userIdentityRepository,
            supabaseJwtVerifier,
            adminUserService);
  }

  @Nested
  @DisplayName("Refresh Token flow")
  class RefreshTokenFlow {

    @Test
    void rejectsWhenRefreshTokenNotFound() {
      when(refreshSessionRepository.findByTokenHash(any())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> authenticationService.refresh("non-existent-token"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);
    }

    @Test
    void rejectsWhenSessionIsInactiveOrRevoked() {
      UUID sessionId = UUID.randomUUID();
      UUID userId = UUID.randomUUID();
      RefreshSession session = createTestSession(sessionId, userId, 0, 0, false, Instant.now());
      when(refreshSessionRepository.findByTokenHash(any())).thenReturn(Optional.of(session));

      assertThatThrownBy(() -> authenticationService.refresh("revoked-session-token"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);
    }

    @Test
    void rejectsWhenUserNotFound() {
      UUID sessionId = UUID.randomUUID();
      UUID userId = UUID.randomUUID();
      RefreshSession session = createTestSession(sessionId, userId, 0, 0, false, null);
      when(refreshSessionRepository.findByTokenHash(any())).thenReturn(Optional.of(session));
      when(userRepository.findById(userId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> authenticationService.refresh("valid-token"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);
    }

    @Test
    void rejectsWhenUserIsDisabledOrUnverified() {
      UUID sessionId = UUID.randomUUID();
      UUID userId = UUID.randomUUID();
      RefreshSession session = createTestSession(sessionId, userId, 0, 0, false, null);
      when(refreshSessionRepository.findByTokenHash(any())).thenReturn(Optional.of(session));

      User disabledUser = createTestUser(userId, UserRole.AUTHOR, false, Instant.now(), 0);
      when(userRepository.findById(userId)).thenReturn(Optional.of(disabledUser));
      assertThatThrownBy(() -> authenticationService.refresh("valid-token"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);

      User unverifiedUser = createTestUser(userId, UserRole.AUTHOR, true, null, 0);
      when(userRepository.findById(userId)).thenReturn(Optional.of(unverifiedUser));
      assertThatThrownBy(() -> authenticationService.refresh("valid-token"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);
    }

    @Test
    void revokesSessionAndRejectsWhenUserAccessTokenVersionMismatches() {
      UUID sessionId = UUID.randomUUID();
      UUID userId = UUID.randomUUID();

      // Session was created when user accessTokenVersion was 0, but user version bumped to 1
      RefreshSession session = createTestSession(sessionId, userId, 0, 0, false, null);
      User userWithBumpedVersion = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 1);

      when(refreshSessionRepository.findByTokenHash(any())).thenReturn(Optional.of(session));
      when(userRepository.findById(userId)).thenReturn(Optional.of(userWithBumpedVersion));

      assertThatThrownBy(() -> authenticationService.refresh("valid-token"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);

      // Verify that the session was revoked as a crucial security side-effect
      assertThat(session.getRevokedAt()).isNotNull();
      assertThat(session.active()).isFalse();
    }

    @Test
    void successfulRefreshRotatesTokenUpdatesLastUsedAtAndPersistsSession() {
      UUID sessionId = UUID.randomUUID();
      UUID userId = UUID.randomUUID();
      Instant initialCreation = Instant.now().minusSeconds(100);

      RefreshSession session =
          new RefreshSession(
              sessionId,
              userId,
              "initial-digest".getBytes(StandardCharsets.UTF_8),
              initialCreation,
              0);
      User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);

      when(refreshSessionRepository.findByTokenHash(any())).thenReturn(Optional.of(session));
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));

      AuthenticationService.Result result = authenticationService.refresh("current-token");

      assertThat(result.user()).isSameAs(user);
      assertThat(result.sessionId()).isEqualTo(sessionId);
      assertThat(result.refreshToken()).isNotBlank();
      assertThat(result.accessTokenVersion()).isEqualTo(1);

      assertThat(session.getAccessTokenVersion()).isEqualTo(1);
      assertThat(session.getLastUsedAt()).isAfterOrEqualTo(initialCreation);
      verify(refreshSessionRepository).save(session);
    }
  }

  @Nested
  @DisplayName("Google Login and Identity binding")
  class GoogleLoginFlow {

    @Test
    void rejectsWhenSupabaseVerifierThrowsInvalidToken() {
      when(supabaseJwtVerifier.verify("bad-jwt"))
          .thenThrow(new SupabaseJwtVerifier.InvalidTokenException());

      assertThatThrownBy(() -> authenticationService.googleLogin("bad-jwt"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);
    }

    @Test
    void googleLoginFallsBackToEmailLinkingWhenDanglingProviderIdentityExists() {
      String subject = "google-subject-dangling";
      String email = "dangling@example.com";
      UUID nonExistentUserId = UUID.randomUUID();

      when(supabaseJwtVerifier.verify("valid-jwt"))
          .thenReturn(new SupabaseJwtVerifier.Claims(subject, email, "Dangling User"));

      // Identity points to a user that was deleted
      UserIdentity danglingIdentity =
          new UserIdentity(UUID.randomUUID(), nonExistentUserId, "google", subject);
      when(userIdentityRepository.findByProviderAndSubject("google", subject))
          .thenReturn(Optional.of(danglingIdentity));
      when(userRepository.findById(nonExistentUserId)).thenReturn(Optional.empty());

      // No user exists with email -> fallback creates new user
      when(userRepository.findByNormalizedEmail(email)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0, User.class));

      AuthenticationService.Result result = authenticationService.googleLogin("valid-jwt");

      assertThat(result.user().getEmail()).isEqualTo(email);
      assertThat(result.user().isEnabled()).isTrue();
      assertThat(result.user().getVerifiedAt()).isNotNull();

      verify(userIdentityRepository)
          .save(
              argThat(
                  identity ->
                      identity.getProvider().equals("google")
                          && identity.getSubject().equals(subject)));
      verify(refreshSessionRepository).save(any(RefreshSession.class));
    }

    @Test
    void rejectsWhenExistingUserAlreadyHasGoogleIdentityBoundDuringNormalLogin() {
      String subject = "new-google-subject";
      String email = "existing@example.com";
      UUID userId = UUID.randomUUID();

      when(supabaseJwtVerifier.verify("valid-jwt"))
          .thenReturn(new SupabaseJwtVerifier.Claims(subject, email, "Existing User"));
      when(userIdentityRepository.findByProviderAndSubject("google", subject))
          .thenReturn(Optional.empty());

      User existingUser = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);
      when(userRepository.findByNormalizedEmail(email)).thenReturn(Optional.of(existingUser));

      // User already has an existing google identity bound
      when(userIdentityRepository.findByUserIdAndProvider(userId, "google"))
          .thenReturn(
              Optional.of(new UserIdentity(UUID.randomUUID(), userId, "google", "old-subject")));

      assertThatThrownBy(() -> authenticationService.googleLogin("valid-jwt"))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);

      verify(userIdentityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void rejectsWhenExistingUserAlreadyHasGoogleIdentityBoundDuringInvitationRedeem() {
      String subject = "invite-google-subject";
      String email = "invited@example.com";
      String invitationToken = "invitation-token-123";
      UUID userId = UUID.randomUUID();

      when(supabaseJwtVerifier.verify("valid-jwt"))
          .thenReturn(new SupabaseJwtVerifier.Claims(subject, email, "Invited User"));

      User redeemedUser = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);
      when(adminUserService.redeemGoogle(invitationToken, email, "Invited User"))
          .thenReturn(redeemedUser);

      // User already bound to a google identity
      when(userIdentityRepository.findByUserIdAndProvider(userId, "google"))
          .thenReturn(
              Optional.of(
                  new UserIdentity(UUID.randomUUID(), userId, "google", "pre-existing-subject")));

      assertThatThrownBy(() -> authenticationService.googleLogin("valid-jwt", invitationToken))
          .isInstanceOf(AuthenticationService.BadCredentialsException.class);

      verify(userIdentityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void handlesOverlongDisplayNameByResettingToEmptyString() {
      String subject = "long-name-subject";
      String email = "longname@example.com";
      String overlongName = "A".repeat(101);

      when(supabaseJwtVerifier.verify("valid-jwt"))
          .thenReturn(new SupabaseJwtVerifier.Claims(subject, email, overlongName));
      when(userIdentityRepository.findByProviderAndSubject("google", subject))
          .thenReturn(Optional.empty());
      when(userRepository.findByNormalizedEmail(email)).thenReturn(Optional.empty());
      when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0, User.class));

      AuthenticationService.Result result = authenticationService.googleLogin("valid-jwt");

      assertThat(result.user().getDisplayName()).isEmpty();
    }
  }

  @Nested
  @DisplayName("Revoke Other Sessions flow")
  class RevokeOtherSessionsFlow {

    @Test
    void rejectsWhenAttemptingToRevokeCurrentSession() {
      UUID currentSessionId = UUID.randomUUID();
      User user = createTestUser(UUID.randomUUID(), UserRole.AUTHOR, true, Instant.now(), 0);

      assertThatThrownBy(
              () -> authenticationService.revokeOther(user, currentSessionId, currentSessionId))
          .isInstanceOf(AuthenticationService.SessionNotFoundException.class);
    }

    @Test
    void rejectsWhenTargetSessionNotFoundOrBelongsToDifferentUser() {
      UUID currentSessionId = UUID.randomUUID();
      UUID targetSessionId = UUID.randomUUID();
      User user = createTestUser(UUID.randomUUID(), UserRole.AUTHOR, true, Instant.now(), 0);

      when(refreshSessionRepository.findById(targetSessionId)).thenReturn(Optional.empty());
      assertThatThrownBy(
              () -> authenticationService.revokeOther(user, targetSessionId, currentSessionId))
          .isInstanceOf(AuthenticationService.SessionNotFoundException.class);

      UUID anotherUserId = UUID.randomUUID();
      RefreshSession foreignSession =
          createTestSession(targetSessionId, anotherUserId, 0, 0, false, null);
      when(refreshSessionRepository.findById(targetSessionId))
          .thenReturn(Optional.of(foreignSession));

      assertThatThrownBy(
              () -> authenticationService.revokeOther(user, targetSessionId, currentSessionId))
          .isInstanceOf(AuthenticationService.SessionNotFoundException.class);
    }

    @Test
    void successfullyRevokesActiveOtherSession() {
      UUID currentSessionId = UUID.randomUUID();
      UUID targetSessionId = UUID.randomUUID();
      UUID userId = UUID.randomUUID();
      User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);

      RefreshSession targetSession = createTestSession(targetSessionId, userId, 0, 0, false, null);
      when(refreshSessionRepository.findById(targetSessionId))
          .thenReturn(Optional.of(targetSession));

      authenticationService.revokeOther(user, targetSessionId, currentSessionId);

      assertThat(targetSession.getRevokedAt()).isNotNull();
      assertThat(targetSession.active()).isFalse();
    }
  }

  private static User createTestUser(
      UUID id, UserRole role, boolean enabled, Instant verifiedAt, int accessTokenVersion) {
    User user =
        new User(
            id,
            "user-" + id + "@example.com",
            "user-" + id + "@example.com",
            "Test User",
            "hash",
            "zh-TW");
    if (role != UserRole.AUTHOR) {
      user.changeRole(role);
    }
    if (verifiedAt != null) {
      user.verify(verifiedAt);
    }
    if (!enabled) {
      user.disable();
    }
    while (user.getAccessTokenVersion() < accessTokenVersion) {
      user.changePassword("hash-" + UUID.randomUUID());
    }
    return user;
  }

  private static RefreshSession createTestSession(
      UUID sessionId,
      UUID userId,
      int accessTokenVersion,
      int userAccessTokenVersion,
      boolean expired,
      Instant revokedAt) {
    Instant now = Instant.now();
    Instant creationTime = expired ? now.minusSeconds(700000) : now;
    RefreshSession session =
        new RefreshSession(
            sessionId,
            userId,
            "digest".getBytes(StandardCharsets.UTF_8),
            creationTime,
            userAccessTokenVersion);
    for (int i = 0; i < accessTokenVersion; i++) {
      session.rotate(("digest-" + i).getBytes(StandardCharsets.UTF_8), creationTime);
    }
    if (revokedAt != null) {
      session.revoke(revokedAt);
    }
    return session;
  }
}
