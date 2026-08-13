package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.emailchange.EmailChangeTokenRepository;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.password.PasswordSettingChangeRepository;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.tag.TagRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
      "app.security.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
    })
class BlogAdminApplicationTests {

  @MockitoBean private UserRepository users;
  @MockitoBean private EmailVerificationTokenRepository tokens;
  @MockitoBean private EmailChangeTokenRepository emailChangeTokens;
  @MockitoBean private RateLimitEventRepository limits;
  @MockitoBean private RefreshSessionRepository sessions;
  @MockitoBean private InvitationRepository invitations;
  @MockitoBean private PasswordSettingChangeRepository passwordSettingChanges;
  @MockitoBean private PasswordSettingRepository passwordSettings;
  @MockitoBean private PasswordResetTokenRepository passwordResetTokens;
  @MockitoBean private ArticleRepository articles;
  @MockitoBean private TagRepository tags;
  @MockitoBean private JavaMailSender mail;

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private Environment environment;

  @Test
  void contextAndHealthEndpointStart() {
    var response =
        restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void swaggerIsPubliclyAccessible() {
    assertThat(
            restTemplate.getForEntity(
                "http://localhost:" + port + "/swagger-ui/index.html", String.class))
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.OK);
    assertThat(restTemplate.getForEntity("http://localhost:" + port + "/v3/api-docs", String.class))
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.OK);
  }

  @Test
  void liquibaseMasterChangelogExists() {
    assertThat(Files.exists(Path.of("src/main/resources/db/changelog/db.changelog-master.yaml")))
        .isTrue();
  }

  @Test
  void disablesOpenEntityManagerInView() {
    assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
  }
}
