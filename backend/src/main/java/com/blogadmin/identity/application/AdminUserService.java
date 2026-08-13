package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.invitation.*;
import com.blogadmin.identity.domain.password.*;
import com.blogadmin.identity.domain.user.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
public class AdminUserService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> COMMON =
      Set.of("password", "password123", "12345678", "qwerty123");
  private final UserRepository users;
  private final InvitationRepository invitations;
  private final PasswordSettingChangeRepository changes;
  private final PasswordEncoder passwords;
  private final PasswordSettingRepository passwordSettings;
  private final JavaMailSender mail;
  private final String from;
  private final String frontend;

  public AdminUserService(
      UserRepository users,
      InvitationRepository invitations,
      PasswordSettingChangeRepository changes,
      PasswordEncoder passwords,
      PasswordSettingRepository passwordSettings,
      JavaMailSender mail,
      @Value("${app.mail.from:dev@example.com}") String from,
      @Value("${app.frontend-base-url:http://localhost:4200}") String frontend) {
    this.users = users;
    this.invitations = invitations;
    this.changes = changes;
    this.passwords = passwords;
    this.passwordSettings = passwordSettings;
    this.mail = mail;
    this.from = from;
    this.frontend = frontend;
  }

  public List<User> list(UserRole role, Boolean enabled, String q) {
    return users.findAll().stream()
        .filter(u -> role == null || u.getRole() == role)
        .filter(u -> enabled == null || u.isEnabled() == enabled)
        .filter(
            u ->
                q == null
                    || u.getEmail().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                    || u.getDisplayName()
                        .toLowerCase(Locale.ROOT)
                        .contains(q.toLowerCase(Locale.ROOT)))
        .toList();
  }

  @Transactional
  public User update(User actor, UUID id, UserRole role, Boolean enabled) {
    if (actor.getId().equals(id)) throw new ForbiddenException();
    users.lockAdminMutation();
    actor = users.findById(actor.getId()).orElseThrow();
    if (actor.getRole() != UserRole.ADMIN || !actor.isEnabled()) throw new ForbiddenException();
    User target = users.findLockedById(id).orElseThrow(NoSuchElementException::new);
    if (Boolean.FALSE.equals(enabled) || role != null && role != UserRole.ADMIN)
      if (target.getRole() == UserRole.ADMIN
          && target.isEnabled()
          && target.getVerifiedAt() != null
          && users.countByRoleAndEnabledTrueAndVerifiedAtIsNotNull(UserRole.ADMIN) <= 1)
        throw new LastAdminException();
    if (role != null) target.changeRole(role);
    if (enabled != null) target.setEnabled(enabled);
    return target;
  }

  @Transactional
  public Invitation invite(String email) {
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    users.lockNormalizedEmail(normalized);
    if (users.findByNormalizedEmail(normalized).isPresent()) throw new AlreadyExistsException();
    invitations.findByEmailAndUsedAtIsNull(normalized).forEach(i -> i.use(Instant.now()));
    invitations.flush();
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    Invitation i =
        new Invitation(
            UUID.randomUUID(),
            normalized,
            hash(Base64.getUrlEncoder().withoutPadding().encodeToString(raw)),
            Instant.now().plusSeconds(86400));
    invitations.save(i);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(normalized);
    message.setSubject("Invitation / 邀請");
    message.setText(
        "You are invited / 您收到邀請："
            + frontend
            + "/invite?token="
            + token
            + " (valid 24 hours / 24 小時有效)");
    sendAfterCommit(message);
    return i;
  }

  @Transactional
  public User redeem(String token, String displayName, String password, String language) {
    Invitation invitation =
        invitations.findByTokenHash(hash(token)).orElseThrow(InvalidInvitationException::new);
    Instant now = Instant.now();
    if (invitation.getUsedAt() != null || !invitation.getExpiresAt().isAfter(now))
      throw new InvalidInvitationException();
    users.lockNormalizedEmail(invitation.getEmail());
    if (users.findByNormalizedEmail(invitation.getEmail()).isPresent())
      throw new AlreadyExistsException();
    int minimum = passwordSettings.findById(true).orElseThrow().getMinimumLength();
    if (password == null || password.length() < minimum || password.length() > 128)
      throw new InvalidMinimumException();
    if (COMMON.contains(password.toLowerCase(Locale.ROOT)))
      throw new RegistrationService.InvalidRegistrationException();
    String name = displayName == null ? null : displayName.trim();
    if (name == null || name.isEmpty() || name.length() > 100)
      throw new RegistrationService.InvalidRegistrationException("displayName");
    if (!Set.of("zh-TW", "en").contains(language))
      throw new RegistrationService.InvalidRegistrationException("preferredLanguage");
    User user =
        new User(
            UUID.randomUUID(),
            invitation.getEmail(),
            invitation.getEmail(),
            name,
            passwords.encode(password),
            language);
    user.verify(now);
    users.save(user);
    invitation.use(now);
    return user;
  }

  public int getMinimum() {
    return passwordSettings.findById(true).orElseThrow().getMinimumLength();
  }

  @Transactional
  public int setMinimum(User actor, int value) {
    if (value < 8 || value > 128) throw new InvalidMinimumException();
    PasswordSetting setting = passwordSettings.findLockedById(true).orElseThrow();
    int old = setting.getMinimumLength();
    if (old != value) {
      changes.save(
          new PasswordSettingChange(UUID.randomUUID(), actor.getId(), old, value, Instant.now()));
      setting.setMinimumLength(value);
    }
    return value;
  }

  public List<PasswordSettingChange> history() {
    return changes.findAllByOrderByChangedAtDesc();
  }

  public List<Invitation> invitations() {
    return invitations.findAll();
  }

  private static byte[] hash(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void sendAfterCommit(SimpleMailMessage message) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      send(message);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            send(message);
          }
        });
  }

  private void send(SimpleMailMessage message) {
    try {
      mail.send(message);
    } catch (RuntimeException ignored) {
    }
  }

  public static class ForbiddenException extends RuntimeException {}

  public static class LastAdminException extends RuntimeException {}

  public static class AlreadyExistsException extends RuntimeException {}

  public static class InvalidMinimumException extends RuntimeException {}

  public static class InvalidInvitationException extends RuntimeException {}
}
