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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
      "app.security.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
    })
class BlogAdminApplicationTests {

  @MockBean private UserRepository users;
  @MockBean private EmailVerificationTokenRepository tokens;
  @MockBean private EmailChangeTokenRepository emailChangeTokens;
  @MockBean private RateLimitEventRepository limits;
  @MockBean private RefreshSessionRepository sessions;
  @MockBean private InvitationRepository invitations;
  @MockBean private PasswordSettingChangeRepository passwordSettingChanges;
  @MockBean private PasswordSettingRepository passwordSettings;
  @MockBean private PasswordResetTokenRepository passwordResetTokens;
  @MockBean private ArticleRepository articles;
  @MockBean private TagRepository tags;
  @MockBean private JavaMailSender mail;

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void contextAndHealthEndpointStart() {
    var response =
        restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void liquibaseMasterChangelogExists() {
    assertThat(Files.exists(Path.of("src/main/resources/db/changelog/db.changelog-master.yaml")))
        .isTrue();
  }
}
