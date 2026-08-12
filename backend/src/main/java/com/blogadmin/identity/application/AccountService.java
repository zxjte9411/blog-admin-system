package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.emailchange.*;
import com.blogadmin.identity.domain.invitation.*;
import com.blogadmin.identity.domain.password.*;
import com.blogadmin.identity.domain.ratelimit.*;
import com.blogadmin.identity.domain.session.*;
import com.blogadmin.identity.domain.user.*;
import com.blogadmin.identity.domain.verification.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AccountService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> COMMON =
      Set.of("password", "password123", "12345678", "qwerty123");
  private final UserRepository users;
  private final RefreshSessionRepository sessions;
  private final PasswordEncoder passwords;
  private final PasswordSettingRepository settings;
  private final PasswordResetTokenRepository resets;
  private final EmailChangeTokenRepository emails;
  private final JavaMailSender mail;
  private final String from;
  private final String frontend;

  public AccountService(
      UserRepository users,
      RefreshSessionRepository sessions,
      PasswordEncoder passwords,
      PasswordSettingRepository settings,
      PasswordResetTokenRepository resets,
      EmailChangeTokenRepository emails,
      JavaMailSender mail,
      @Value("${app.mail.from:dev@example.com}") String from,
      @Value("${app.frontend-base-url:http://localhost:4200}") String frontend) {
    this.users = users;
    this.sessions = sessions;
    this.passwords = passwords;
    this.settings = settings;
    this.resets = resets;
    this.emails = emails;
    this.mail = mail;
    this.from = from;
    this.frontend = frontend;
  }

  @Transactional
  public User profile(User user, String name, String language) {
    if (name == null
        || name.trim().length() < 1
        || name.trim().length() > 100
        || !Set.of("zh-TW", "en").contains(language)) throw new InvalidAccountException();
    User managed = users.findLockedById(user.getId()).orElseThrow(InvalidAccountException::new);
    managed.updateProfile(name.trim(), language);
    return managed;
  }

  @Transactional
  public void password(
      User user, String current, String next, UUID currentSession, boolean logoutCurrent) {
    User managed = users.findLockedById(user.getId()).orElseThrow(InvalidAccountException::new);
    int min = settings.findById(true).orElseThrow().getMinimumLength();
    if (!passwords.matches(current, managed.getPasswordHash())
        || next == null
        || next.length() < min
        || next.length() > 128
        || COMMON.contains(next.toLowerCase(Locale.ROOT))) throw new InvalidAccountException();
    managed.changePasswordKeepingSessions(passwords.encode(next));
    if (logoutCurrent) sessions.revokeAll(managed.getId(), Instant.now());
    else sessions.revokeOthers(managed.getId(), currentSession, Instant.now());
  }

  @Transactional
  public void requestReset(String email) {
    if (email == null) return;
    User u = users.findByNormalizedEmail(email.trim().toLowerCase(Locale.ROOT)).orElse(null);
    if (u == null) return;
    resets.findByUserIdAndUsedAtIsNull(u.getId()).forEach(t -> t.use(Instant.now()));
    resets.flush();
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random());
    resets.save(
        new PasswordResetToken(
            UUID.randomUUID(),
            u.getId(),
            hash(token.getBytes(StandardCharsets.UTF_8)),
            Instant.now().plusSeconds(86400)));
    sendAfterCommit(
        u.getEmail(),
        "Password reset / 密碼重設",
        "Reset password / 重設密碼: " + frontend + "/reset-password?token=" + token);
  }

  @Transactional
  public void reset(String token, String next) {
    if (token == null) throw new ResetTokenNotFound();
    PasswordResetToken t =
        resets
            .findByTokenHash(hash(token.getBytes(StandardCharsets.UTF_8)))
            .orElseThrow(ResetTokenNotFound::new);
    User u = users.findById(t.getUserId()).orElseThrow();
    int min = settings.findById(true).orElseThrow().getMinimumLength();
    if (t.getUsedAt() != null || !t.getExpiresAt().isAfter(Instant.now()))
      throw new ResetTokenNotFound();
    if (next == null
        || next.length() < min
        || next.length() > 128
        || COMMON.contains(next.toLowerCase(Locale.ROOT))) throw new InvalidAccountException();
    u.changePassword(passwords.encode(next));
    sessions.revokeAll(u.getId(), Instant.now());
    t.use(Instant.now());
  }

  @Transactional
  public void requestEmail(User u, String email) {
    User managed = users.findLockedById(u.getId()).orElseThrow(InvalidAccountException::new);
    if (email == null || email.isBlank()) throw new InvalidAccountException();
    String n = email.trim().toLowerCase(Locale.ROOT);
    users.lockNormalizedEmail(n);
    if (users.findByNormalizedEmail(n).filter(x -> !x.getId().equals(managed.getId())).isPresent())
      throw new AlreadyUsedEmail();
    emails.findByUserIdAndUsedAtIsNull(managed.getId()).forEach(t -> t.use(Instant.now()));
    emails.flush();
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random());
    emails.save(
        new EmailChangeToken(
            UUID.randomUUID(),
            managed.getId(),
            n,
            hash(token.getBytes(StandardCharsets.UTF_8)),
            Instant.now().plusSeconds(86400)));
    sendAfterCommit(
        n,
        "Email change / Email 變更",
        "Confirm / 確認: " + frontend + "/confirm-email?token=" + token);
  }

  @Transactional
  public void confirmEmail(String token) {
    EmailChangeToken t =
        emails
            .findByTokenHash(hash(token.getBytes(StandardCharsets.UTF_8)))
            .orElseThrow(InvalidAccountException::new);
    if (t.getUsedAt() != null || !t.getExpiresAt().isAfter(Instant.now()))
      throw new InvalidAccountException();
    users.lockNormalizedEmail(t.getNewEmail());
    if (users
        .findByNormalizedEmail(t.getNewEmail())
        .filter(x -> !x.getId().equals(t.getUserId()))
        .isPresent()) throw new AlreadyUsedEmail();
    User u = users.findLockedById(t.getUserId()).orElseThrow();
    String old = u.getEmail();
    u.changeEmail(t.getNewEmail());
    t.use(Instant.now());
    String subject = "Email changed / Email 已變更";
    String text = "Your email was changed. / 您的 Email 已變更。";
    sendAfterCommit(old, subject, text);
    sendAfterCommit(t.getNewEmail(), subject, text);
  }

  private void sendAfterCommit(String to, String subject, String text) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      send(to, subject, text);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            send(to, subject, text);
          }
        });
  }

  private void send(String to, String subject, String text) {
    SimpleMailMessage m = new SimpleMailMessage();
    m.setFrom(from);
    m.setTo(to);
    m.setSubject(subject);
    m.setText(text);
    try {
      mail.send(m);
    } catch (RuntimeException ignored) {
    }
  }

  private static byte[] random() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return b;
  }

  private static byte[] hash(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static class InvalidAccountException extends RuntimeException {}

  public static class ResetTokenNotFound extends RuntimeException {}

  public static class AlreadyUsedEmail extends RuntimeException {}
}
