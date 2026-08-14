package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.session.RefreshSession;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserIdentity;
import com.blogadmin.identity.domain.user.UserIdentityRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.web.security.SupabaseJwtVerifier;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final UserRepository userRepository;
  private final RefreshSessionRepository refreshSessionRepository;
  private final PasswordEncoder passwordEncoder;
  private final UserIdentityRepository userIdentityRepository;
  private final SupabaseJwtVerifier supabaseJwtVerifier;
  private final AdminUserService adminUserService;

  @Transactional
  public Result login(String email, String password) {
    User user = userRepository.findByNormalizedEmail(normalize(email)).orElse(null);
    if (user == null
        || user.getVerifiedAt() == null
        || !user.isEnabled()
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new BadCredentialsException();
    }
    return issue(user);
  }

  @Transactional
  public Result googleLogin(String accessToken) {
    return googleLogin(accessToken, null);
  }

  @Transactional
  public Result googleLogin(String accessToken, String invitationToken) {
    SupabaseJwtVerifier.Claims claims;
    try {
      claims = supabaseJwtVerifier.verify(accessToken);
    } catch (SupabaseJwtVerifier.InvalidTokenException exception) {
      throw new BadCredentialsException();
    }

    if (invitationToken != null && !invitationToken.isBlank()) {
      User user;
      try {
        user = adminUserService.redeemGoogle(invitationToken, claims.email(), claims.displayName());
      } catch (AdminUserService.InvalidInvitationException
          | AdminUserService.AlreadyExistsException exception) {
        throw new BadCredentialsException();
      }
      if (userIdentityRepository.findByUserIdAndProvider(user.getId(), "google").isPresent()) {
        throw new BadCredentialsException();
      }
      userIdentityRepository.save(
          new UserIdentity(UUID.randomUUID(), user.getId(), "google", claims.subject()));
      return issue(user);
    }

    User user =
        userIdentityRepository
            .findByProviderAndSubject("google", claims.subject())
            .map(identity -> userRepository.findById(identity.getUserId()).orElse(null))
            .orElse(null);
    if (user == null) {
      String email = normalize(claims.email());
      userRepository.lockNormalizedEmail(email);
      user = userRepository.findByNormalizedEmail(email).orElse(null);
      if (user == null) {
        String displayName = claims.displayName() == null ? "" : claims.displayName().trim();
        if (displayName.length() > 100) {
          displayName = "";
        }
        user =
            userRepository.save(
                new User(
                    UUID.randomUUID(),
                    claims.email().trim(),
                    email,
                    displayName,
                    passwordEncoder.encode(randomPassword()),
                    "zh-TW"));
        user.verify(Instant.now());
      } else if (user.getVerifiedAt() == null || !user.isEnabled()) {
        throw new BadCredentialsException();
      }
      if (userIdentityRepository.findByUserIdAndProvider(user.getId(), "google").isPresent()) {
        throw new BadCredentialsException();
      }
      userIdentityRepository.save(
          new UserIdentity(UUID.randomUUID(), user.getId(), "google", claims.subject()));
    }
    if (!user.isEnabled() || user.getVerifiedAt() == null) {
      throw new BadCredentialsException();
    }
    return issue(user);
  }

  @Transactional
  public Result refresh(String token) {
    RefreshSession session =
        refreshSessionRepository.findByTokenHash(OpaqueToken.digest(token)).orElse(null);
    if (session == null || !session.active()) {
      throw new BadCredentialsException();
    }
    User user = userRepository.findById(session.getUserId()).orElse(null);
    if (user == null || !user.isEnabled() || user.getVerifiedAt() == null) {
      throw new BadCredentialsException();
    }
    if (session.getUserAccessTokenVersion() != user.getAccessTokenVersion()) {
      session.revoke(Instant.now());
      throw new BadCredentialsException();
    }
    OpaqueToken.Issued nextToken = OpaqueToken.generate();
    session.rotate(nextToken.digest(), Instant.now());
    refreshSessionRepository.save(session);
    return new Result(user, nextToken.value(), session.getId(), session.getAccessTokenVersion());
  }

  @Transactional
  public void logout(String token) {
    refreshSessionRepository
        .findByTokenHash(OpaqueToken.digest(token))
        .ifPresent(session -> session.revoke(Instant.now()));
  }

  @Transactional
  public List<RefreshSession> sessions(User user) {
    return refreshSessionRepository
        .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndUserAccessTokenVersionEqualsOrderByCreatedAtDesc(
            user.getId(), Instant.now(), user.getAccessTokenVersion());
  }

  @Transactional
  public void revokeOther(User user, UUID sessionId, UUID currentSessionId) {
    if (sessionId.equals(currentSessionId)) {
      throw new SessionNotFoundException();
    }
    RefreshSession targetSession =
        refreshSessionRepository.findById(sessionId).orElseThrow(SessionNotFoundException::new);
    if (!targetSession.getUserId().equals(user.getId())) {
      throw new SessionNotFoundException();
    }
    if (targetSession.active()) {
      targetSession.revoke(Instant.now());
    }
  }

  private Result issue(User user) {
    OpaqueToken.Issued token = OpaqueToken.generate();
    RefreshSession session =
        new RefreshSession(
            UUID.randomUUID(),
            user.getId(),
            token.digest(),
            Instant.now(),
            user.getAccessTokenVersion());
    refreshSessionRepository.save(session);
    return new Result(user, token.value(), session.getId(), session.getAccessTokenVersion());
  }

  private static String randomPassword() {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  public record Result(User user, String refreshToken, UUID sessionId, int accessTokenVersion) {}

  public static class BadCredentialsException extends RuntimeException {}

  public static class SessionNotFoundException extends RuntimeException {}
}
