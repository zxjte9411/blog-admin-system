package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.user.UserRole;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final String email;
  private final String password;

  public BootstrapAdminRunner(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy,
      @Value("${app.bootstrap.admin.email:}") String email,
      @Value("${app.bootstrap.admin.password:}") String password) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
    this.email = email;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
      return;
    }
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    if (userRepository.findByNormalizedEmail(normalizedEmail).isPresent()) {
      return;
    }
    if (!passwordPolicy.isValid(password)) {
      throw new IllegalStateException("Invalid bootstrap admin password");
    }

    User admin =
        new User(
            UUID.randomUUID(),
            email.trim(),
            normalizedEmail,
            "Admin",
            passwordEncoder.encode(password),
            "zh-TW");
    admin.changeRole(UserRole.ADMIN);
    admin.verify(Instant.now());
    userRepository.save(admin);
  }
}
