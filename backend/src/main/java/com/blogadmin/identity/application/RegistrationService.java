package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.EmailVerificationToken;
import com.blogadmin.identity.domain.EmailVerificationTokenRepository;
import com.blogadmin.identity.domain.PasswordSettingRepository;
import com.blogadmin.identity.domain.User;
import com.blogadmin.identity.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RegistrationService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> COMMON =
      Set.of("password", "password123", "12345678", "qwerty123");
  private final UserRepository users;
  private final EmailVerificationTokenRepository tokens;
  private final RateLimitService rateLimits;
  private final JavaMailSender mail;
  private final PasswordEncoder passwordEncoder;
  private final PasswordSettingRepository passwordSettings;
  private final String from;
  private final String frontend;

  public RegistrationService(
      UserRepository users,
      EmailVerificationTokenRepository tokens,
      RateLimitService rateLimits,
      JavaMailSender mail,
      PasswordEncoder passwordEncoder,
      PasswordSettingRepository passwordSettings,
      @Value("${app.mail.from:dev@example.com}") String from,
      @Value("${app.frontend-base-url:http://localhost:4200}") String frontend) {
    this.users = users;
    this.tokens = tokens;
    this.rateLimits = rateLimits;
    this.mail = mail;
    this.passwordEncoder = passwordEncoder;
    this.passwordSettings = passwordSettings;
    this.from = from;
    this.frontend = frontend;
  }

  @Transactional
  public void register(
      String email, String displayName, String password, String language, String ip) {
    String normalized = normalize(email);
    checkRate("registration", ip, normalized);
    users.lockNormalizedEmail(normalized);
    int minimum = passwordSettings.findById(true).orElseThrow().getMinimumLength();
    if (password == null
        || password.length() < minimum
        || password.length() > 128
        || COMMON.contains(password.toLowerCase(Locale.ROOT)))
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
                            displayName,
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
    byte[] tokenHash = hash(token);
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
    if (user == null) return false;
    row.use(now);
    user.verify(now);
    return true;
  }

  private void issue(User user) {
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
    tokens
        .findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(user.getId())
        .forEach(t -> t.invalidate(Instant.now()));
    tokens.save(
        new EmailVerificationToken(
            UUID.randomUUID(), user.getId(), hash(token), Instant.now().plusSeconds(86400)));
    tokens.flush();
    try {
      var m = new SimpleMailMessage();
      m.setFrom(from);
      m.setTo(user.getEmail());
      m.setSubject(user.getPreferredLanguage().equals("en") ? "Verify your email" : "驗證您的 Email");
      m.setText(
          (user.getPreferredLanguage().equals("en") ? "Hi " : "您好 ")
              + user.getDisplayName()
              + (user.getPreferredLanguage().equals("en")
                  ? ", verify within 24 hours: "
                  : "，請於 24 小時內驗證：")
              + frontend
              + "/verify-email?token="
              + token);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                try {
                  mail.send(m);
                } catch (RuntimeException ignored) {
                }
              }
            });
      } else mail.send(m);
    } catch (RuntimeException ignored) {
    }
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

  private static byte[] randomBytes() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return b;
  }

  private static byte[] hash(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
