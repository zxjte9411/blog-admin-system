package com.blogadmin.identity.application;

import com.blogadmin.identity.application.mail.IdentityEmailEvent;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.verification.EmailVerificationToken;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationService {
  private final UserRepository userRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final RateLimitService rateLimitService;
  private final ApplicationEventPublisher eventPublisher;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;

  @Transactional
  public void register(
      String email, String displayName, String password, String language, String ip) {
    String normalizedEmail = normalize(email);
    checkRate("registration", ip, normalizedEmail);
    userRepository.lockNormalizedEmail(normalizedEmail);
    if (passwordPolicy.validate(password) != PasswordPolicy.Violation.NONE) {
      throw new InvalidRegistrationException();
    }
    String trimmedDisplayName = displayName == null ? null : displayName.trim();
    if (trimmedDisplayName == null
        || trimmedDisplayName.isEmpty()
        || trimmedDisplayName.length() > 100) {
      throw new InvalidRegistrationException("displayName");
    }
    User user =
        userRepository
            .findByNormalizedEmail(normalizedEmail)
            .orElseGet(
                () ->
                    userRepository.save(
                        new User(
                            UUID.randomUUID(),
                            email.trim(),
                            normalizedEmail,
                            trimmedDisplayName,
                            passwordEncoder.encode(password),
                            language)));
    if (user.getVerifiedAt() == null) {
      issue(user);
    }
  }

  @Transactional
  public void resend(String email, String ip) {
    String normalizedEmail = normalize(email);
    checkRate("resend", ip, normalizedEmail);
    userRepository.lockNormalizedEmail(normalizedEmail);
    userRepository
        .findByNormalizedEmail(normalizedEmail)
        .filter(user -> user.getVerifiedAt() == null)
        .ifPresent(this::issue);
  }

  @Transactional
  public boolean verify(String token) {
    Instant now = Instant.now();
    byte[] tokenHash = OpaqueToken.digest(token);
    UUID userId = emailVerificationTokenRepository.findUserIdByTokenHash(tokenHash).orElse(null);
    if (userId == null) {
      return false;
    }
    User user = userRepository.findLockedById(userId).orElse(null);
    if (user == null) {
      return false;
    }
    EmailVerificationToken verificationToken =
        emailVerificationTokenRepository.findByTokenHash(tokenHash).orElse(null);
    if (verificationToken == null
        || !user.getId().equals(verificationToken.getUserId())
        || verificationToken.getUsedAt() != null
        || verificationToken.getInvalidatedAt() != null
        || !verificationToken.getExpiresAt().isAfter(now)) {
      return false;
    }
    verificationToken.use(now);
    user.verify(now);
    return true;
  }

  private void issue(User user) {
    OpaqueToken.Issued token = OpaqueToken.generate();
    emailVerificationTokenRepository
        .findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(user.getId())
        .forEach(existingToken -> existingToken.invalidate(Instant.now()));
    emailVerificationTokenRepository.save(
        new EmailVerificationToken(
            UUID.randomUUID(), user.getId(), token.digest(), Instant.now().plusSeconds(86400)));
    emailVerificationTokenRepository.flush();
    eventPublisher.publishEvent(
        new IdentityEmailEvent.Verification(
            user.getEmail(), user.getDisplayName(), token.value(), user.getPreferredLanguage()));
  }

  private void checkRate(String bucket, String ip, String email) {
    var decision = rateLimitService.consume(bucket, ip, email);
    if (!decision.allowed()) {
      throw new RateLimitedException(decision.retryAfterSeconds());
    }
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

  @Getter
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
