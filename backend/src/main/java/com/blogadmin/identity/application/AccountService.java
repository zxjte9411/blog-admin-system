package com.blogadmin.identity.application;

import com.blogadmin.identity.application.mail.IdentityEmailEvent;
import com.blogadmin.identity.domain.emailchange.EmailChangeToken;
import com.blogadmin.identity.domain.emailchange.EmailChangeTokenRepository;
import com.blogadmin.identity.domain.password.PasswordPolicy;
import com.blogadmin.identity.domain.password.PasswordResetToken;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final UserRepository users;
  private final RefreshSessionRepository sessions;
  private final PasswordEncoder passwords;
  private final PasswordPolicy passwordPolicy;
  private final PasswordResetTokenRepository resets;
  private final EmailChangeTokenRepository emails;
  private final ApplicationEventPublisher events;

  public AccountService(
      UserRepository users,
      RefreshSessionRepository sessions,
      PasswordEncoder passwords,
      PasswordPolicy passwordPolicy,
      PasswordResetTokenRepository resets,
      EmailChangeTokenRepository emails,
      ApplicationEventPublisher events) {
    this.users = users;
    this.sessions = sessions;
    this.passwords = passwords;
    this.passwordPolicy = passwordPolicy;
    this.resets = resets;
    this.emails = emails;
    this.events = events;
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
    if (!passwords.matches(current, managed.getPasswordHash())
        || passwordPolicy.validate(next) != PasswordPolicy.Violation.NONE)
      throw new InvalidAccountException();
    managed.changePasswordKeepingSessions(passwords.encode(next));
    if (logoutCurrent) sessions.revokeAll(managed.getId(), Instant.now());
    else sessions.revokeOthers(managed.getId(), currentSession, Instant.now());
  }

  @Transactional
  public void requestReset(String email) {
    if (email == null || email.isBlank()) return;
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    users.lockNormalizedEmail(normalized);
    User u = users.findByNormalizedEmail(normalized).orElse(null);
    if (u == null) return;
    resets.findLockedByUserIdAndUsedAtIsNull(u.getId()).forEach(t -> t.use(Instant.now()));
    resets.flush();
    OpaqueToken.Issued token = OpaqueToken.generate();
    resets.save(
        new PasswordResetToken(
            UUID.randomUUID(), u.getId(), token.digest(), Instant.now().plusSeconds(86400)));
    events.publishEvent(new IdentityEmailEvent.PasswordReset(u.getEmail(), token.value()));
  }

  @Transactional
  public void reset(String token, String next) {
    if (token == null) throw new ResetTokenNotFound();
    byte[] hash = OpaqueToken.digest(token);
    UUID userId = resets.findUserIdByTokenHash(hash).orElseThrow(ResetTokenNotFound::new);
    User u = users.findLockedById(userId).orElseThrow(ResetTokenNotFound::new);
    PasswordResetToken t = resets.findLockedByTokenHash(hash).orElseThrow(ResetTokenNotFound::new);
    if (t.getUsedAt() != null || !t.getExpiresAt().isAfter(Instant.now()))
      throw new ResetTokenNotFound();
    if (passwordPolicy.validate(next) != PasswordPolicy.Violation.NONE)
      throw new InvalidAccountException();
    u.changePassword(passwords.encode(next));
    sessions.revokeAll(u.getId(), Instant.now());
    t.use(Instant.now());
  }

  @Transactional
  public void requestEmail(User u, String email) {
    if (email == null || email.isBlank()) throw new InvalidAccountException();
    String n = email.trim().toLowerCase(Locale.ROOT);
    users.lockNormalizedEmail(n);
    User managed = users.findLockedById(u.getId()).orElseThrow(InvalidAccountException::new);
    if (users.findByNormalizedEmail(n).filter(x -> !x.getId().equals(managed.getId())).isPresent())
      throw new AlreadyUsedEmail();
    emails.findLockedByUserIdAndUsedAtIsNull(managed.getId()).forEach(t -> t.use(Instant.now()));
    emails.flush();
    OpaqueToken.Issued token = OpaqueToken.generate();
    emails.save(
        new EmailChangeToken(
            UUID.randomUUID(),
            managed.getId(),
            n,
            token.digest(),
            Instant.now().plusSeconds(86400)));
    events.publishEvent(new IdentityEmailEvent.EmailChangeConfirmation(n, token.value()));
  }

  @Transactional
  public void confirmEmail(String token) {
    if (token == null) throw new InvalidAccountException();
    byte[] hash = OpaqueToken.digest(token);
    EmailChangeToken t = emails.findByTokenHash(hash).orElseThrow(InvalidAccountException::new);
    if (t.getUsedAt() != null || !t.getExpiresAt().isAfter(Instant.now()))
      throw new InvalidAccountException();
    users.lockNormalizedEmail(t.getNewEmail());
    if (users
        .findByNormalizedEmail(t.getNewEmail())
        .filter(x -> !x.getId().equals(t.getUserId()))
        .isPresent()) throw new AlreadyUsedEmail();
    User u = users.findLockedById(t.getUserId()).orElseThrow(InvalidAccountException::new);
    EmailChangeToken lockedToken =
        emails.findLockedByTokenHash(hash).orElseThrow(InvalidAccountException::new);
    if (lockedToken.getUsedAt() != null || !lockedToken.getExpiresAt().isAfter(Instant.now()))
      throw new InvalidAccountException();
    String old = u.getEmail();
    u.changeEmail(t.getNewEmail());
    t.use(Instant.now());
    events.publishEvent(new IdentityEmailEvent.EmailChangedNotification(old));
    events.publishEvent(new IdentityEmailEvent.EmailChangedNotification(t.getNewEmail()));
  }

  public static class InvalidAccountException extends RuntimeException {}

  public static class ResetTokenNotFound extends RuntimeException {}

  public static class AlreadyUsedEmail extends RuntimeException {}
}
