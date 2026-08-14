package com.blogadmin.identity.application;

import com.blogadmin.identity.application.mail.IdentityEmailEvent;
import com.blogadmin.identity.domain.emailchange.EmailChangeToken;
import com.blogadmin.identity.domain.emailchange.EmailChangeTokenRepository;
import com.blogadmin.identity.domain.password.PasswordResetToken;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {
  private final UserRepository userRepository;
  private final RefreshSessionRepository refreshSessionRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailChangeTokenRepository emailChangeTokenRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public User profile(User user, String name, String language) {
    if (name == null
        || name.trim().length() < 1
        || name.trim().length() > 100
        || !Set.of("zh-TW", "en").contains(language)) {
      throw new InvalidAccountException();
    }
    User managedUser =
        userRepository.findLockedById(user.getId()).orElseThrow(InvalidAccountException::new);
    managedUser.updateProfile(name.trim(), language);
    return managedUser;
  }

  @Transactional
  public void password(
      User user,
      String currentPassword,
      String nextPassword,
      UUID currentSessionId,
      boolean logoutCurrentSession) {
    User managedUser =
        userRepository.findLockedById(user.getId()).orElseThrow(InvalidAccountException::new);
    if (!passwordEncoder.matches(currentPassword, managedUser.getPasswordHash())
        || passwordPolicy.validate(nextPassword) != PasswordPolicy.Violation.NONE) {
      throw new InvalidAccountException();
    }
    managedUser.changePasswordKeepingSessions(passwordEncoder.encode(nextPassword));
    if (logoutCurrentSession) {
      refreshSessionRepository.revokeAll(managedUser.getId(), Instant.now());
    } else {
      refreshSessionRepository.revokeOthers(managedUser.getId(), currentSessionId, Instant.now());
    }
  }

  @Transactional
  public void requestReset(String email) {
    if (email == null || email.isBlank()) {
      return;
    }
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    userRepository.lockNormalizedEmail(normalizedEmail);
    User user = userRepository.findByNormalizedEmail(normalizedEmail).orElse(null);
    if (user == null) {
      return;
    }
    passwordResetTokenRepository
        .findLockedByUserIdAndUsedAtIsNull(user.getId())
        .forEach(token -> token.use(Instant.now()));
    passwordResetTokenRepository.flush();
    OpaqueToken.Issued token = OpaqueToken.generate();
    passwordResetTokenRepository.save(
        new PasswordResetToken(
            UUID.randomUUID(), user.getId(), token.digest(), Instant.now().plusSeconds(86400)));
    eventPublisher.publishEvent(
        new IdentityEmailEvent.PasswordReset(user.getEmail(), token.value()));
  }

  @Transactional
  public void reset(String token, String nextPassword) {
    if (token == null) {
      throw new ResetTokenNotFound();
    }
    byte[] hash = OpaqueToken.digest(token);
    UUID userId =
        passwordResetTokenRepository
            .findUserIdByTokenHash(hash)
            .orElseThrow(ResetTokenNotFound::new);
    User user = userRepository.findLockedById(userId).orElseThrow(ResetTokenNotFound::new);
    PasswordResetToken resetToken =
        passwordResetTokenRepository
            .findLockedByTokenHash(hash)
            .orElseThrow(ResetTokenNotFound::new);
    if (resetToken.getUsedAt() != null || !resetToken.getExpiresAt().isAfter(Instant.now())) {
      throw new ResetTokenNotFound();
    }
    if (passwordPolicy.validate(nextPassword) != PasswordPolicy.Violation.NONE) {
      throw new InvalidAccountException();
    }
    user.changePassword(passwordEncoder.encode(nextPassword));
    refreshSessionRepository.revokeAll(user.getId(), Instant.now());
    resetToken.use(Instant.now());
  }

  @Transactional
  public void requestEmail(User user, String email) {
    if (email == null || email.isBlank()) {
      throw new InvalidAccountException();
    }
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    userRepository.lockNormalizedEmail(normalizedEmail);
    User managedUser =
        userRepository.findLockedById(user.getId()).orElseThrow(InvalidAccountException::new);
    if (userRepository
        .findByNormalizedEmail(normalizedEmail)
        .filter(candidate -> !candidate.getId().equals(managedUser.getId()))
        .isPresent()) {
      throw new AlreadyUsedEmail();
    }
    emailChangeTokenRepository
        .findLockedByUserIdAndUsedAtIsNull(managedUser.getId())
        .forEach(token -> token.use(Instant.now()));
    emailChangeTokenRepository.flush();
    OpaqueToken.Issued token = OpaqueToken.generate();
    emailChangeTokenRepository.save(
        new EmailChangeToken(
            UUID.randomUUID(),
            managedUser.getId(),
            normalizedEmail,
            token.digest(),
            Instant.now().plusSeconds(86400)));
    eventPublisher.publishEvent(
        new IdentityEmailEvent.EmailChangeConfirmation(normalizedEmail, token.value()));
  }

  @Transactional
  public void confirmEmail(String token) {
    if (token == null) {
      throw new InvalidAccountException();
    }
    byte[] hash = OpaqueToken.digest(token);
    EmailChangeToken changeToken =
        emailChangeTokenRepository.findByTokenHash(hash).orElseThrow(InvalidAccountException::new);
    if (changeToken.getUsedAt() != null || !changeToken.getExpiresAt().isAfter(Instant.now())) {
      throw new InvalidAccountException();
    }
    userRepository.lockNormalizedEmail(changeToken.getNewEmail());
    if (userRepository
        .findByNormalizedEmail(changeToken.getNewEmail())
        .filter(candidate -> !candidate.getId().equals(changeToken.getUserId()))
        .isPresent()) {
      throw new AlreadyUsedEmail();
    }
    User user =
        userRepository
            .findLockedById(changeToken.getUserId())
            .orElseThrow(InvalidAccountException::new);
    EmailChangeToken lockedToken =
        emailChangeTokenRepository
            .findLockedByTokenHash(hash)
            .orElseThrow(InvalidAccountException::new);
    if (lockedToken.getUsedAt() != null || !lockedToken.getExpiresAt().isAfter(Instant.now())) {
      throw new InvalidAccountException();
    }
    String oldEmail = user.getEmail();
    user.changeEmail(changeToken.getNewEmail());
    changeToken.use(Instant.now());
    eventPublisher.publishEvent(new IdentityEmailEvent.EmailChangedNotification(oldEmail));
    eventPublisher.publishEvent(
        new IdentityEmailEvent.EmailChangedNotification(changeToken.getNewEmail()));
  }

  public static class InvalidAccountException extends RuntimeException {}

  public static class ResetTokenNotFound extends RuntimeException {}

  public static class AlreadyUsedEmail extends RuntimeException {}
}
