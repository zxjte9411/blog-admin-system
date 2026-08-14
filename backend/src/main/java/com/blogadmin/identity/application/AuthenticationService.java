package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.session.RefreshSession;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserIdentity;
import com.blogadmin.identity.domain.user.UserIdentityRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.web.security.SupabaseJwtVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final UserRepository users;
  private final RefreshSessionRepository sessions;
  private final PasswordEncoder passwords;
  private final UserIdentityRepository identities;
  private final SupabaseJwtVerifier supabase;

  public AuthenticationService(
      UserRepository users,
      RefreshSessionRepository sessions,
      PasswordEncoder passwords,
      UserIdentityRepository identities,
      SupabaseJwtVerifier supabase) {
    this.users = users;
    this.sessions = sessions;
    this.passwords = passwords;
    this.identities = identities;
    this.supabase = supabase;
  }

  @Transactional
  public Result login(String email, String password) {
    User user = users.findByNormalizedEmail(normalize(email)).orElse(null);
    if (user == null
        || user.getVerifiedAt() == null
        || !user.isEnabled()
        || !passwords.matches(password, user.getPasswordHash()))
      throw new BadCredentialsException();
    return issue(user);
  }

  @Transactional
  public Result googleLogin(String accessToken) {
    SupabaseJwtVerifier.Claims claims;
    try {
      claims = supabase.verify(accessToken);
    } catch (SupabaseJwtVerifier.InvalidTokenException exception) {
      throw new BadCredentialsException();
    }

    User user =
        identities
            .findByProviderAndSubject("google", claims.subject())
            .map(identity -> users.findById(identity.getUserId()).orElse(null))
            .orElse(null);
    if (user == null) {
      String email = normalize(claims.email());
      users.lockNormalizedEmail(email);
      user = users.findByNormalizedEmail(email).orElse(null);
      if (user == null) {
        String displayName = claims.displayName() == null ? "" : claims.displayName().trim();
        if (displayName.length() > 100) displayName = "";
        user =
            users.save(
                new User(
                    UUID.randomUUID(),
                    claims.email().trim(),
                    email,
                    displayName,
                    passwords.encode(randomPassword()),
                    "zh-TW"));
        user.verify(Instant.now());
      } else if (user.getVerifiedAt() == null || !user.isEnabled()) {
        throw new BadCredentialsException();
      }
      if (identities.findByUserIdAndProvider(user.getId(), "google").isPresent())
        throw new BadCredentialsException();
      identities.save(
          new UserIdentity(UUID.randomUUID(), user.getId(), "google", claims.subject()));
    }
    if (!user.isEnabled() || user.getVerifiedAt() == null) throw new BadCredentialsException();
    return issue(user);
  }

  @Transactional
  public Result refresh(String token) {
    var session = sessions.findByTokenHash(hash(token)).orElse(null);
    if (session == null || !session.active()) throw new BadCredentialsException();
    var user = users.findById(session.getUserId()).orElse(null);
    if (user == null || !user.isEnabled() || user.getVerifiedAt() == null)
      throw new BadCredentialsException();
    if (session.getUserAccessTokenVersion() != user.getAccessTokenVersion()) {
      session.revoke(Instant.now());
      throw new BadCredentialsException();
    }
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String nextToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    session.rotate(hash(nextToken), Instant.now());
    sessions.save(session);
    return new Result(user, nextToken, session.getId(), session.getAccessTokenVersion());
  }

  @Transactional
  public void logout(String token) {
    sessions.findByTokenHash(hash(token)).ifPresent(s -> s.revoke(Instant.now()));
  }

  @Transactional
  public List<RefreshSession> sessions(User user) {
    return sessions
        .findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndUserAccessTokenVersionEqualsOrderByCreatedAtDesc(
            user.getId(), Instant.now(), user.getAccessTokenVersion());
  }

  @Transactional
  public void revokeOther(User user, UUID id, UUID currentSessionId) {
    if (id.equals(currentSessionId)) throw new SessionNotFoundException();
    var target = sessions.findById(id).orElseThrow(() -> new SessionNotFoundException());
    if (!target.getUserId().equals(user.getId())) throw new SessionNotFoundException();
    if (target.active()) target.revoke(Instant.now());
  }

  private Result issue(User user) {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    var session =
        new RefreshSession(
            UUID.randomUUID(),
            user.getId(),
            hash(token),
            Instant.now(),
            user.getAccessTokenVersion());
    sessions.save(session);
    return new Result(user, token, session.getId(), session.getAccessTokenVersion());
  }

  private static byte[] hash(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
