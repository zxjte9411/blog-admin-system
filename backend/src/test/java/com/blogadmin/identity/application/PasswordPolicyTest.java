package com.blogadmin.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blogadmin.identity.domain.password.PasswordSetting;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

  private PasswordSettingRepository repository;
  private PasswordPolicy policy;

  @BeforeEach
  void setUp() {
    repository = mock(PasswordSettingRepository.class);
    PasswordSetting setting = mock(PasswordSetting.class);
    when(setting.getMinimumLength()).thenReturn(8);
    when(repository.findById(true)).thenReturn(Optional.of(setting));
    policy = new PasswordPolicy(repository);
  }

  @Test
  void nullOrTooShortPasswordFailsWithLengthViolation() {
    assertThat(policy.validate(null)).isEqualTo(PasswordPolicy.Violation.LENGTH);
    assertThat(policy.validate("")).isEqualTo(PasswordPolicy.Violation.LENGTH);
    assertThat(policy.validate("1234567")).isEqualTo(PasswordPolicy.Violation.LENGTH);
  }

  @Test
  void overlongPasswordFailsWithLengthViolation() {
    assertThat(policy.validate("a".repeat(129))).isEqualTo(PasswordPolicy.Violation.LENGTH);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"password", "PASSWORD", "Password123", "12345678", "qwerty123", "QWERTY123"})
  void commonPasswordsFailWithCommonViolation(String commonPassword) {
    assertThat(policy.validate(commonPassword)).isEqualTo(PasswordPolicy.Violation.COMMON);
    assertThat(policy.isValid(commonPassword)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CorrectHorseBatteryStaple", "Valid-Password!123"})
  void compliantPasswordsPassValidation(String validPassword) {
    assertThat(policy.validate(validPassword)).isEqualTo(PasswordPolicy.Violation.NONE);
    assertThat(policy.isValid(validPassword)).isTrue();
  }

  @Test
  void maxLengthPasswordPassesValidation() {
    assertThat(policy.validate("a".repeat(128))).isEqualTo(PasswordPolicy.Violation.NONE);
    assertThat(policy.isValid("a".repeat(128))).isTrue();
  }

  @Test
  void respectsDynamicMinimumLengthSetting() {
    PasswordSetting setting = mock(PasswordSetting.class);
    when(setting.getMinimumLength()).thenReturn(12);
    when(repository.findById(true)).thenReturn(Optional.of(setting));

    assertThat(policy.validate("12345678901")).isEqualTo(PasswordPolicy.Violation.LENGTH);
    assertThat(policy.validate("1234567890123")).isEqualTo(PasswordPolicy.Violation.NONE);
  }
}
