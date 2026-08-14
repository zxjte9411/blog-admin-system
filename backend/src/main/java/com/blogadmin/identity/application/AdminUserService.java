package com.blogadmin.identity.application;

import com.blogadmin.identity.application.mail.IdentityEmailEvent;
import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordSetting;
import com.blogadmin.identity.domain.password.PasswordSettingChange;
import com.blogadmin.identity.domain.password.PasswordSettingChangeRepository;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final UserRepository userRepository;
  private final InvitationRepository invitationRepository;
  private final PasswordSettingChangeRepository passwordSettingChangeRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final PasswordSettingRepository passwordSettingRepository;
  private final ApplicationEventPublisher eventPublisher;

  public List<User> list(UserRole role, Boolean enabled, String query) {
    String trimmedQuery = query == null || query.isBlank() ? null : query.trim();
    return userRepository.findAllForAdmin(role == null ? null : role.name(), enabled, trimmedQuery);
  }

  @Transactional
  public User update(User actor, UUID targetUserId, UserRole role, Boolean enabled) {
    if (actor.getId().equals(targetUserId)) {
      throw new ForbiddenException();
    }
    userRepository.lockAdminMutation();
    User managedActor = userRepository.findById(actor.getId()).orElseThrow();
    if (managedActor.getRole() != UserRole.ADMIN || !managedActor.isEnabled()) {
      throw new ForbiddenException();
    }
    User target =
        userRepository.findLockedById(targetUserId).orElseThrow(NoSuchElementException::new);
    if (Boolean.FALSE.equals(enabled) || (role != null && role != UserRole.ADMIN)) {
      if (target.getRole() == UserRole.ADMIN
          && target.isEnabled()
          && target.getVerifiedAt() != null
          && userRepository.countByRoleAndEnabledTrueAndVerifiedAtIsNotNull(UserRole.ADMIN) <= 1) {
        throw new LastAdminException();
      }
    }
    if (role != null) {
      target.changeRole(role);
    }
    if (enabled != null) {
      target.setEnabled(enabled);
    }
    return target;
  }

  @Transactional
  public Invitation invite(String email) {
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    userRepository.lockNormalizedEmail(normalizedEmail);
    if (userRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
      throw new AlreadyExistsException();
    }
    invitationRepository
        .findByEmailAndUsedAtIsNull(normalizedEmail)
        .forEach(invitation -> invitation.use(Instant.now()));
    invitationRepository.flush();
    OpaqueToken.Issued token = OpaqueToken.generate();
    Invitation invitation =
        new Invitation(
            UUID.randomUUID(), normalizedEmail, token.digest(), Instant.now().plusSeconds(86400));
    invitationRepository.save(invitation);
    eventPublisher.publishEvent(new IdentityEmailEvent.Invitation(normalizedEmail, token.value()));
    return invitation;
  }

  @Transactional
  public User redeem(String token, String displayName, String password, String language) {
    if (token == null) {
      throw new InvalidInvitationException();
    }
    byte[] hash = OpaqueToken.digest(token);
    Invitation invitation =
        invitationRepository.findByTokenHash(hash).orElseThrow(InvalidInvitationException::new);
    Instant now = Instant.now();
    if (invitation.getUsedAt() != null || !invitation.getExpiresAt().isAfter(now)) {
      throw new InvalidInvitationException();
    }
    userRepository.lockNormalizedEmail(invitation.getEmail());
    if (userRepository.findByNormalizedEmail(invitation.getEmail()).isPresent()) {
      throw new AlreadyExistsException();
    }
    Invitation lockedInvitation =
        invitationRepository
            .findLockedByTokenHash(hash)
            .orElseThrow(InvalidInvitationException::new);
    if (lockedInvitation.getUsedAt() != null || !lockedInvitation.getExpiresAt().isAfter(now)) {
      throw new InvalidInvitationException();
    }
    PasswordPolicy.Violation violation = passwordPolicy.validate(password);
    if (violation == PasswordPolicy.Violation.LENGTH) {
      throw new InvalidMinimumException();
    }
    if (violation == PasswordPolicy.Violation.COMMON) {
      throw new RegistrationService.InvalidRegistrationException();
    }
    String name = displayName == null ? null : displayName.trim();
    if (name == null || name.isEmpty() || name.length() > 100) {
      throw new RegistrationService.InvalidRegistrationException("displayName");
    }
    if (!Set.of("zh-TW", "en").contains(language)) {
      throw new RegistrationService.InvalidRegistrationException("preferredLanguage");
    }
    User user =
        new User(
            UUID.randomUUID(),
            invitation.getEmail(),
            invitation.getEmail(),
            name,
            passwordEncoder.encode(password),
            language);
    user.verify(now);
    userRepository.save(user);
    lockedInvitation.use(now);
    return user;
  }

  @Transactional
  public User redeemGoogle(String token, String email, String displayName) {
    if (token == null || email == null) {
      throw new InvalidInvitationException();
    }
    byte[] hash = OpaqueToken.digest(token);
    Invitation invitation =
        invitationRepository.findByTokenHash(hash).orElseThrow(InvalidInvitationException::new);
    Instant now = Instant.now();
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    if (invitation.getUsedAt() != null
        || !invitation.getExpiresAt().isAfter(now)
        || !invitation.getEmail().equals(normalizedEmail)) {
      throw new InvalidInvitationException();
    }
    userRepository.lockNormalizedEmail(normalizedEmail);
    if (userRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
      throw new AlreadyExistsException();
    }
    Invitation lockedInvitation =
        invitationRepository
            .findLockedByTokenHash(hash)
            .orElseThrow(InvalidInvitationException::new);
    if (lockedInvitation.getUsedAt() != null
        || !lockedInvitation.getExpiresAt().isAfter(now)
        || !lockedInvitation.getEmail().equals(normalizedEmail)) {
      throw new InvalidInvitationException();
    }
    String name = displayName == null ? "" : displayName.trim();
    if (name.isEmpty() || name.length() > 100) {
      throw new InvalidInvitationException();
    }
    User user =
        new User(
            UUID.randomUUID(),
            invitation.getEmail(),
            normalizedEmail,
            name,
            passwordEncoder.encode(randomPassword()),
            "zh-TW");
    user.verify(now);
    userRepository.save(user);
    lockedInvitation.use(now);
    return user;
  }

  public int getMinimum() {
    return passwordSettingRepository.findById(true).orElseThrow().getMinimumLength();
  }

  @Transactional
  public int setMinimum(User actor, int minimumLength) {
    if (minimumLength < 8 || minimumLength > 128) {
      throw new InvalidMinimumException();
    }
    PasswordSetting setting = passwordSettingRepository.findLockedById(true).orElseThrow();
    int oldMinimumLength = setting.getMinimumLength();
    if (oldMinimumLength != minimumLength) {
      passwordSettingChangeRepository.save(
          new PasswordSettingChange(
              UUID.randomUUID(), actor.getId(), oldMinimumLength, minimumLength, Instant.now()));
      setting.setMinimumLength(minimumLength);
    }
    return minimumLength;
  }

  public List<PasswordSettingChange> history() {
    return passwordSettingChangeRepository.findAllByOrderByChangedAtDesc();
  }

  public List<Invitation> invitations() {
    return invitationRepository.findAll();
  }

  private static String randomPassword() {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  public static class ForbiddenException extends RuntimeException {}

  public static class LastAdminException extends RuntimeException {}

  public static class AlreadyExistsException extends RuntimeException {}

  public static class InvalidMinimumException extends RuntimeException {}

  public static class InvalidInvitationException extends RuntimeException {}
}
