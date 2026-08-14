package com.blogadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.emailchange.EmailChangeTokenRepository;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.identity.domain.password.PasswordResetTokenRepository;
import com.blogadmin.identity.domain.password.PasswordSettingChangeRepository;
import com.blogadmin.identity.domain.password.PasswordSettingRepository;
import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.UserIdentityRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.domain.verification.EmailVerificationTokenRepository;
import com.blogadmin.publishing.domain.article.ArticleRepository;
import com.blogadmin.publishing.domain.tag.TagRepository;
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

  @MockitoBean private UserRepository userRepository;
  @MockitoBean private UserIdentityRepository userIdentityRepository;
  @MockitoBean private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @MockitoBean private EmailChangeTokenRepository emailChangeTokenRepository;
  @MockitoBean private RateLimitEventRepository rateLimitEventRepository;
  @MockitoBean private RefreshSessionRepository refreshSessionRepository;
  @MockitoBean private InvitationRepository invitationRepository;
  @MockitoBean private PasswordSettingChangeRepository passwordSettingChangeRepository;
  @MockitoBean private PasswordSettingRepository passwordSettingRepository;
  @MockitoBean private PasswordResetTokenRepository passwordResetTokenRepository;
  @MockitoBean private ArticleRepository articleRepository;
  @MockitoBean private TagRepository tagRepository;
  @MockitoBean private JavaMailSender mailSender;

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate testRestTemplate;

  @Autowired private Environment environment;

  @Test
  void contextAndHealthEndpointStart() {
    var response =
        testRestTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void swaggerIsPubliclyAccessible() {
    assertThat(
            testRestTemplate.getForEntity(
                "http://localhost:" + port + "/swagger-ui/index.html", String.class))
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.OK);
    assertThat(
            testRestTemplate.getForEntity(
                "http://localhost:" + port + "/v3/api-docs", String.class))
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.OK);
  }

  @Test
  void liquibaseMasterChangelogExistsOnClasspath() {
    assertThat(getClass().getResourceAsStream("/db/changelog/db.changelog-master.yaml"))
        .isNotNull();
  }

  @Test
  void disablesOpenEntityManagerInView() {
    assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
  }
}
