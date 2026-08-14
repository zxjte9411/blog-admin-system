package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blogadmin.identity.application.BootstrapAdminRunner;
import com.blogadmin.identity.application.PasswordPolicy;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapAdminApiIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate testRestTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PasswordSettingRepository passwordSettingRepository;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.security.jwt-secret", () -> "test-secret-that-is-at-least-32-bytes-long");
    registry.add("app.bootstrap.admin.email", () -> "  Bootstrap@Example.com ");
    registry.add("app.bootstrap.admin.password", () -> "bootstrap-password");
  }

  @Test
  void configuredBootstrapAdminCanLogIn() {
    ResponseEntity<Map> response =
        testRestTemplate.postForEntity(
            "http://localhost:" + port + "/api/v1/auth/login",
            Map.of("email", "bootstrap@example.com", "password", "bootstrap-password"),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsKey("accessToken");
  }

  @Test
  @Transactional
  void nonCompliantBootstrapPasswordFailsWithoutCreatingUser() {
    long beforeCount = userRepository.count();
    BootstrapAdminRunner runner =
        new BootstrapAdminRunner(
            userRepository,
            passwordEncoder,
            new PasswordPolicy(passwordSettingRepository),
            "invalid@example.com",
            "password");

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(userRepository.count()).isEqualTo(beforeCount);
  }
}
