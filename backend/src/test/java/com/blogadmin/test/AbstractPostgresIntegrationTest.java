package com.blogadmin.test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Common base class for integration tests requiring a live PostgreSQL instance. Manages a singleton
 * PostgreSQL Testcontainer reused across all integration test suites.
 *
 * <p><strong>Maintenance Contract &amp; Invariants:</strong>
 *
 * <ul>
 *   <li><strong>No Unprotected Parallel Execution:</strong> Integration tests sharing this database
 *       instance must not run concurrently without isolation. Each test suite relies on {@link
 *       #resetDatabase(JdbcTemplate)} to truncate shared tables; concurrent test execution would
 *       cause cross-test data loss and flakiness.
 *   <li><strong>Schema Evolution Maintenance:</strong> Whenever a new persistent application table
 *       or Liquibase migration is added, maintainers must review and update {@link
 *       #resetDatabase(JdbcTemplate)} to include the new table in the {@code TRUNCATE TABLE ...
 *       CASCADE} list and reset any static/seed state.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPostgresIntegrationTest {

  public static final String TEST_JWT_SECRET = "test-secret-that-is-at-least-32-bytes-long";

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("blog_admin");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void configurePostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("app.security.jwt-secret", () -> TEST_JWT_SECRET);
  }

  /**
   * Resets all application tables in PostgreSQL to a clean state while preserving schema and
   * default settings.
   *
   * <p>Invariant: Must be updated whenever new persistent application tables or Liquibase
   * migrations are introduced to maintain test isolation.
   */
  public static void resetDatabase(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute(
        """
        TRUNCATE TABLE
          article_tags,
          articles,
          tags,
          email_verification_tokens,
          email_change_tokens,
          password_reset_tokens,
          refresh_sessions,
          user_identities,
          admin_invitations,
          auth_rate_limit_events,
          password_setting_changes,
          users
        CASCADE
        """);
    jdbcTemplate.update("UPDATE password_settings SET minimum_length = 8 WHERE id = TRUE");
  }
}
