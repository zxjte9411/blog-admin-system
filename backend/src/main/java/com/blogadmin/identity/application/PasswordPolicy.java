package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasswordPolicy {
  private static final int MAXIMUM_LENGTH = 128;
  private static final Set<String> COMMON_PASSWORDS =
      Set.of("password", "password123", "12345678", "qwerty123");

  private final PasswordSettingRepository passwordSettingRepository;

  public Violation validate(String password) {
    int minimum = passwordSettingRepository.findById(true).orElseThrow().getMinimumLength();
    if (password == null || password.length() < minimum || password.length() > MAXIMUM_LENGTH) {
      return Violation.LENGTH;
    }
    if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
      return Violation.COMMON;
    }
    return Violation.NONE;
  }

  public boolean isValid(String password) {
    return validate(password) == Violation.NONE;
  }

  public enum Violation {
    NONE,
    LENGTH,
    COMMON
  }
}
