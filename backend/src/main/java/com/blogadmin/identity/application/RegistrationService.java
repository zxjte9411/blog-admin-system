package com.blogadmin.identity.application;

import com.blogadmin.identity.application.mail.IdentityEmailEvent;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.verification.EmailVerificationToken;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
  private final UserRepository users;
  private final EmailVerificationTokenRepository tokens;
  private final RateLimitService rateLimits;
  private final ApplicationEventPublisher events;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;

  public RegistrationService(
      UserRepository users,
      EmailVerificationTokenRepository tokens,
      RateLimitService rateLimits,
      ApplicationEventPublisher events,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy) {
    this.users = users;
    this.tokens = tokens;
    this.rateLimits = rateLimits;
    this.events = events;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
  }

  @Transactional
  public void register(
      String email, String displayName, String password, String language, String ip) {
    String normalized = normalize(email);
    checkRate("registration", ip, normalized);
    users.lockNormalizedEmail(normalized);
    if (passwordPolicy.validate(password) != PasswordPolicy.Violation.NONE)
      throw new InvalidRegistrationException();
    String trimmedDisplayName = displayName == null ? null : displayName.trim();
    if (trimmedDisplayName == null
        || trimmedDisplayName.isEmpty()
        || trimmedDisplayName.length() > 100) throw new InvalidRegistrationException("displayName");
    User user =
        users
            .findByNormalizedEmail(normalized)
            .orElseGet(
                () ->
                    users.save(
                        new User(
                            UUID.randomUUID(),
                            email.trim(),
                            normalized,
                            trimmedDisplayName,
                            passwordEncoder.encode(password),
                            language)));
    if (user.getVerifiedAt() == null) issue(user);
  }

  @Transactional
  public void resend(String email, String ip) {
    String normalized = normalize(email);
    checkRate("resend", ip, normalized);
    users.lockNormalizedEmail(normalized);
    users
        .findByNormalizedEmail(normalized)
        .filter(a -> a.getVerifiedAt() == null)
        .ifPresent(this::issue);
  }

  @Transactional
  public boolean verify(String token) {
    Instant now = Instant.now();
    byte[] tokenHash = OpaqueToken.digest(token);
    UUID userId = tokens.findUserIdByTokenHash(tokenHash).orElse(null);
    if (userId == null) return false;
    User user = users.findLockedById(userId).orElse(null);
    if (user == null) return false;
    var row = tokens.findByTokenHash(tokenHash).orElse(null);
    if (row == null
        || !user.getId().equals(row.getUserId())
        || row.getUsedAt() != null
        || row.getInvalidatedAt() != null
        || !row.getExpiresAt().isAfter(now)) return false;
    row.use(now);
    user.verify(now);
    return true;
  }

  private void issue(User user) {
    OpaqueToken.Issued token = OpaqueToken.generate();
    tokens
        .findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(user.getId())
        .forEach(t -> t.invalidate(Instant.now()));
    tokens.save(
        new EmailVerificationToken(
            UUID.randomUUID(), user.getId(), token.digest(), Instant.now().plusSeconds(86400)));
    tokens.flush();
    events.publishEvent(
        new IdentityEmailEvent.Verification(
            user.getEmail(), user.getDisplayName(), token.value(), user.getPreferredLanguage()));
  }

  private void checkRate(String bucket, String ip, String email) {
    var decision = rateLimits.consume(bucket, ip, email);
    if (!decision.allowed()) throw new RateLimitedException(decision.retryAfterSeconds());
  }

  private static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  public static class InvalidRegistrationException extends RuntimeException {
    public InvalidRegistrationException() {}

    public InvalidRegistrationException(String field) {
      super(field);
    }
  }

  public static class RateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }
}
