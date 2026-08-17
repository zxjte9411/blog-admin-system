package com.blogadmin.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordSetting;
import com.blogadmin.identity.domain.password.PasswordSettingChange;
import com.blogadmin.identity.domain.password.PasswordSettingChangeRepository;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminUserServiceTest {

  private UserRepository userRepository;
  private InvitationRepository invitationRepository;
  private PasswordSettingChangeRepository passwordSettingChangeRepository;
  private PasswordEncoder passwordEncoder;
  private PasswordPolicy passwordPolicy;
  private PasswordSettingRepository passwordSettingRepository;
  private ApplicationEventPublisher eventPublisher;
  private AdminUserService adminUserService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    invitationRepository = mock(InvitationRepository.class);
    passwordSettingChangeRepository = mock(PasswordSettingChangeRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    passwordPolicy = mock(PasswordPolicy.class);
    passwordSettingRepository = mock(PasswordSettingRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);

    when(passwordEncoder.encode(any())).thenReturn("encoded-hash");
    when(passwordPolicy.validate(any())).thenReturn(null);

    adminUserService =
        new AdminUserService(
            userRepository,
            invitationRepository,
            passwordSettingChangeRepository,
            passwordEncoder,
            passwordPolicy,
            passwordSettingRepository,
            eventPublisher);
  }

  @Nested
  @DisplayName("Redeem password-based invitation")
  class RedeemPasswordInvitation {

    @Test
    void rejectsNullOrNonExistentToken() {
      assertThatThrownBy(() -> adminUserService.redeem(null, "User", "password", "zh-TW"))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);

      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.empty());
      assertThatThrownBy(() -> adminUserService.redeem("token", "User", "password", "zh-TW"))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
    }

    @Test
    void rejectsUsedOrExpiredInvitation() {
      Invitation usedInvitation =
          new Invitation(
              UUID.randomUUID(),
              "user@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      usedInvitation.use(Instant.now());
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(usedInvitation));

      assertThatThrownBy(() -> adminUserService.redeem("token", "User", "password", "zh-TW"))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);

      Invitation expiredInvitation =
          new Invitation(
              UUID.randomUUID(),
              "user@example.com",
              new byte[] {1, 2, 3},
              Instant.now().minusSeconds(10));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(expiredInvitation));

      assertThatThrownBy(() -> adminUserService.redeem("token", "User", "password", "zh-TW"))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
    }

    @Test
    void rejectsWhenAccountAlreadyExistsForInvitationEmail() {
      Invitation validInvitation =
          new Invitation(
              UUID.randomUUID(),
              "existing@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(validInvitation));

      User existing =
          new User(
              UUID.randomUUID(),
              "existing@example.com",
              "existing@example.com",
              "Existing",
              "hash",
              "zh-TW");
      when(userRepository.findByNormalizedEmail("existing@example.com"))
          .thenReturn(Optional.of(existing));

      assertThatThrownBy(() -> adminUserService.redeem("token", "User", "password", "zh-TW"))
          .isInstanceOf(AdminUserService.AlreadyExistsException.class);
    }

    @Test
    void rejectsPasswordPolicyViolations() {
      Invitation invitation =
          new Invitation(
              UUID.randomUUID(),
              "user@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(invitationRepository.findLockedByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(userRepository.findByNormalizedEmail("user@example.com")).thenReturn(Optional.empty());

      when(passwordPolicy.validate("short")).thenReturn(PasswordPolicy.Violation.LENGTH);
      assertThatThrownBy(() -> adminUserService.redeem("token", "User", "short", "zh-TW"))
          .isInstanceOf(AdminUserService.InvalidMinimumException.class);

      when(passwordPolicy.validate("commonpassword")).thenReturn(PasswordPolicy.Violation.COMMON);
      assertThatThrownBy(() -> adminUserService.redeem("token", "User", "commonpassword", "zh-TW"))
          .isInstanceOf(RegistrationService.InvalidRegistrationException.class);
    }

    @Test
    void rejectsInvalidDisplayNameOrLanguage() {
      Invitation invitation =
          new Invitation(
              UUID.randomUUID(),
              "user@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(invitationRepository.findLockedByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(userRepository.findByNormalizedEmail("user@example.com")).thenReturn(Optional.empty());

      // Null, blank, overlong display name
      assertThatThrownBy(() -> adminUserService.redeem("token", null, "safe-password", "zh-TW"))
          .isInstanceOf(RegistrationService.InvalidRegistrationException.class);
      assertThatThrownBy(() -> adminUserService.redeem("token", "   ", "safe-password", "zh-TW"))
          .isInstanceOf(RegistrationService.InvalidRegistrationException.class);
      assertThatThrownBy(
              () -> adminUserService.redeem("token", "a".repeat(101), "safe-password", "zh-TW"))
          .isInstanceOf(RegistrationService.InvalidRegistrationException.class);

      // Unsupported language
      assertThatThrownBy(
              () -> adminUserService.redeem("token", "Valid Name", "safe-password", "fr"))
          .isInstanceOf(RegistrationService.InvalidRegistrationException.class);
    }

    @Test
    void successfullyRedeemsInvitationAndMarksItUsed() {
      Invitation invitation =
          new Invitation(
              UUID.randomUUID(),
              "user@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(invitationRepository.findLockedByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(userRepository.findByNormalizedEmail("user@example.com")).thenReturn(Optional.empty());

      User user = adminUserService.redeem("token", "Valid User", "safe-password", "en");

      assertThat(user.getEmail()).isEqualTo("user@example.com");
      assertThat(user.getDisplayName()).isEqualTo("Valid User");
      assertThat(user.getPreferredLanguage()).isEqualTo("en");
      assertThat(user.getVerifiedAt()).isNotNull();
      assertThat(invitation.getUsedAt()).isNotNull();

      verify(userRepository).save(user);
    }
  }

  @Nested
  @DisplayName("Redeem Google OAuth invitation")
  class RedeemGoogleInvitation {

    @Test
    void rejectsNullTokenOrEmail() {
      assertThatThrownBy(() -> adminUserService.redeemGoogle(null, "user@example.com", "Name"))
          .isInstanceOf(AdminUserService.InvitationInvalidatedException.class);
      assertThatThrownBy(() -> adminUserService.redeemGoogle("token", null, "Name"))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
    }

    @Test
    void rejectsEmailMismatchBetweenInvitationAndToken() {
      Invitation invitation =
          new Invitation(
              UUID.randomUUID(),
              "invited@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(
              () -> adminUserService.redeemGoogle("token", "different@example.com", "Google Name"))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
    }

    @Test
    void rejectsInvalidGoogleDisplayName() {
      Invitation invitation =
          new Invitation(
              UUID.randomUUID(),
              "google@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(invitationRepository.findLockedByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(userRepository.findByNormalizedEmail("google@example.com")).thenReturn(Optional.empty());

      // Null or blank displayName fails in redeemGoogle
      assertThatThrownBy(() -> adminUserService.redeemGoogle("token", "google@example.com", null))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
      assertThatThrownBy(() -> adminUserService.redeemGoogle("token", "google@example.com", "   "))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
      assertThatThrownBy(
              () -> adminUserService.redeemGoogle("token", "google@example.com", "x".repeat(101)))
          .isInstanceOf(AdminUserService.InvalidInvitationException.class);
    }

    @Test
    void successfullyRedeemsGoogleInvitation() {
      Invitation invitation =
          new Invitation(
              UUID.randomUUID(),
              "google@example.com",
              new byte[] {1, 2, 3},
              Instant.now().plusSeconds(3600));
      when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(invitationRepository.findLockedByTokenHash(any())).thenReturn(Optional.of(invitation));
      when(userRepository.findByNormalizedEmail("google@example.com")).thenReturn(Optional.empty());

      User user = adminUserService.redeemGoogle("token", "google@example.com", "Google User");

      assertThat(user.getEmail()).isEqualTo("google@example.com");
      assertThat(user.getDisplayName()).isEqualTo("Google User");
      assertThat(user.getPreferredLanguage()).isEqualTo("zh-TW");
      assertThat(user.getVerifiedAt()).isNotNull();
      assertThat(invitation.getUsedAt()).isNotNull();

      verify(userRepository).save(user);
    }
  }

  @Nested
  @DisplayName("Password minimum length configuration")
  class PasswordMinimumConfiguration {

    @ParameterizedTest
    @ValueSource(ints = {7, 129, 0, -1})
    void rejectsMinimumLengthOutsideRange(int invalidLength) {
      User admin =
          new User(
              UUID.randomUUID(),
              "admin@example.com",
              "admin@example.com",
              "Admin",
              "hash",
              "zh-TW");
      assertThatThrownBy(() -> adminUserService.setMinimum(admin, invalidLength))
          .isInstanceOf(AdminUserService.InvalidMinimumException.class);
    }

    @Test
    void recordsAuditChangeWhenMinimumLengthIsModified() {
      User admin =
          new User(
              UUID.randomUUID(),
              "admin@example.com",
              "admin@example.com",
              "Admin",
              "hash",
              "zh-TW");
      PasswordSetting setting = mock(PasswordSetting.class);
      when(setting.getMinimumLength()).thenReturn(8);
      when(passwordSettingRepository.findLockedById(true)).thenReturn(Optional.of(setting));

      int updated = adminUserService.setMinimum(admin, 12);
      assertThat(updated).isEqualTo(12);
      verify(setting).setMinimumLength(12);

      verify(passwordSettingChangeRepository).save(any(PasswordSettingChange.class));
    }

    @Test
    void doesNotRecordAuditChangeWhenMinimumLengthIsUnchanged() {
      User admin =
          new User(
              UUID.randomUUID(),
              "admin@example.com",
              "admin@example.com",
              "Admin",
              "hash",
              "zh-TW");
      PasswordSetting setting = mock(PasswordSetting.class);
      when(setting.getMinimumLength()).thenReturn(8);
      when(passwordSettingRepository.findLockedById(true)).thenReturn(Optional.of(setting));

      int updated = adminUserService.setMinimum(admin, 8);
      assertThat(updated).isEqualTo(8);

      verify(passwordSettingChangeRepository, never()).save(any(PasswordSettingChange.class));
    }
  }
}
