package com.blogadmin.identity.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blogadmin.identity.domain.session.RefreshSession;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessTokenAuthenticationConverterTest {

  private UserRepository userRepository;
  private RefreshSessionRepository refreshSessionRepository;
  private AccessTokenAuthenticationConverter converter;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    refreshSessionRepository = mock(RefreshSessionRepository.class);
    converter = new AccessTokenAuthenticationConverter(userRepository, refreshSessionRepository);
  }

  @Test
  void convertsValidTokenToAuthenticatedPrincipalWithRoleAndSessionDetails() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();

    User user = createTestUser(userId, UserRole.ADMIN, true, Instant.now(), 0);
    RefreshSession session = createTestSession(sessionId, userId, 1, 0, false, null);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
        .thenReturn(Optional.of(session));

    Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, user.getAccessTokenVersion());

    AbstractAuthenticationToken auth = converter.convert(jwt);

    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getPrincipal()).isSameAs(user);
    assertThat(auth.getAuthorities()).containsExactly(new SimpleGrantedAuthority("ROLE_ADMIN"));
    assertThat(auth.getDetails()).isEqualTo(sessionId);
  }

  @Test
  void supportsNumberTypesForVersionClaims() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();

    User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);
    RefreshSession session = createTestSession(sessionId, userId, 2, 0, false, null);

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
        .thenReturn(Optional.of(session));

    // 'ver' as Long and 'uver' as Integer
    Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 2L, 0);

    AbstractAuthenticationToken auth = converter.convert(jwt);
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getAuthorities()).containsExactly(new SimpleGrantedAuthority("ROLE_AUTHOR"));
  }

  @Nested
  @DisplayName("Malformed or invalid token claim handling")
  class TokenClaimHandling {

    @Test
    void rejectsMalformedSubjectUuid() {
      Jwt jwt = createJwt("not-a-uuid", UUID.randomUUID().toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsMissingOrMalformedSessionUuid() {
      Jwt nullSid = createJwt(UUID.randomUUID().toString(), null, 1, 0);
      assertInvalidTokenException(() -> converter.convert(nullSid));

      Jwt malformedSid = createJwt(UUID.randomUUID().toString(), "invalid-sid", 1, 0);
      assertInvalidTokenException(() -> converter.convert(malformedSid));
    }

    @Test
    void rejectsMissingOrNonNumberSessionVersion() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      Jwt missingVer = createJwt(userId.toString(), sessionId.toString(), null, 0);
      assertInvalidTokenException(() -> converter.convert(missingVer));

      Jwt stringVer = createJwt(userId.toString(), sessionId.toString(), "1", 0);
      assertInvalidTokenException(() -> converter.convert(stringVer));
    }

    @Test
    void rejectsMissingOrNonNumberUserVersion() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      Jwt missingUver = createJwt(userId.toString(), sessionId.toString(), 1, null);
      assertInvalidTokenException(() -> converter.convert(missingUver));

      Jwt stringUver = createJwt(userId.toString(), sessionId.toString(), 1, "0");
      assertInvalidTokenException(() -> converter.convert(stringUver));
    }
  }

  @Nested
  @DisplayName("Session and User state validations")
  class SessionAndUserStateValidations {

    @Test
    void rejectsWhenSessionNotFoundOrRevoked() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.empty());

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenUserNotFound() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      RefreshSession session = createTestSession(sessionId, userId, 1, 0, false, null);
      when(userRepository.findById(userId)).thenReturn(Optional.empty());
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenSessionBelongsToAnotherUser() {
      UUID tokenUserId = UUID.randomUUID();
      UUID sessionUserId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      User user = createTestUser(tokenUserId, UserRole.AUTHOR, true, Instant.now(), 0);
      RefreshSession session = createTestSession(sessionId, sessionUserId, 1, 0, false, null);

      when(userRepository.findById(tokenUserId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(tokenUserId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenSessionAccessTokenVersionMismatchesTokenClaim() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);
      RefreshSession session = createTestSession(sessionId, userId, 2, 0, false, null);

      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenSessionIsInactiveOrExpired() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 0);
      // Expired session
      RefreshSession session = createTestSession(sessionId, userId, 1, 0, true, null);

      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenUserAccessTokenVersionMismatchesTokenClaim() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      // User accessTokenVersion is 1 (e.g. after demotion or password change), but token has 0
      User user = createTestUser(userId, UserRole.AUTHOR, true, Instant.now(), 1);
      RefreshSession session = createTestSession(sessionId, userId, 1, 1, false, null);

      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenUserIsDisabled() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      User user = createTestUser(userId, UserRole.AUTHOR, false, Instant.now(), 0);
      RefreshSession session = createTestSession(sessionId, userId, 1, 0, false, null);

      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }

    @Test
    void rejectsWhenUserIsUnverified() {
      UUID userId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();

      User user = createTestUser(userId, UserRole.AUTHOR, true, null, 0);
      RefreshSession session = createTestSession(sessionId, userId, 1, 0, false, null);

      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId))
          .thenReturn(Optional.of(session));

      Jwt jwt = createJwt(userId.toString(), sessionId.toString(), 1, 0);
      assertInvalidTokenException(() -> converter.convert(jwt));
    }
  }

  private static void assertInvalidTokenException(
      org.junit.jupiter.api.function.Executable executable) {
    assertThatThrownBy(executable::execute)
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(
            ex -> {
              OAuth2AuthenticationException oauth2Ex = (OAuth2AuthenticationException) ex;
              assertThat(oauth2Ex.getError().getErrorCode()).isEqualTo("invalid_token");
            });
  }

  private static Jwt createJwt(String subject, String sid, Object ver, Object uver) {
    var claimsBuilder =
        Map.<String, Object>ofEntries(
            Map.entry("sub", subject == null ? "" : subject),
            Map.entry("iat", Instant.now()),
            Map.entry("exp", Instant.now().plusSeconds(3600)));
    var claims = new java.util.HashMap<String, Object>(claimsBuilder);
    if (subject != null) claims.put("sub", subject);
    if (sid != null) claims.put("sid", sid);
    if (ver != null) claims.put("ver", ver);
    if (uver != null) claims.put("uver", uver);

    return new Jwt(
        "mock-token-value",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Map.of("alg", "HS256", "typ", "JWT"),
        Collections.unmodifiableMap(claims));
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
