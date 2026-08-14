package com.blogadmin.identity.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class IdentityEmailEventListenerTest {

  private static final String FROM = "dev@example.com";
  private static final String FRONTEND_URL = "http://localhost:4200";

  private JavaMailSender mailSender;
  private IdentityEmailEventListener listener;

  @BeforeEach
  void setUp() {
    mailSender = mock(JavaMailSender.class);
    listener = new IdentityEmailEventListener(mailSender, FROM, FRONTEND_URL);
  }

  @Test
  void verificationEmailEnglishComposition() {
    String token = "raw-verification-token-" + UUID.randomUUID();
    IdentityEmailEvent.Verification event =
        new IdentityEmailEvent.Verification("alice@example.com", "Alice User", token, "en");

    listener.handle(event);

    SimpleMailMessage message = captureSentMessage();
    assertThat(message.getTo()).containsExactly("alice@example.com");
    assertThat(message.getSubject()).isEqualTo("Verify your email");
    assertThat(message.getText())
        .contains("Hi Alice User")
        .contains(", verify within 24 hours: ")
        .contains(FRONTEND_URL + "/verify-email?token=")
        .contains(token)
        .doesNotContain("請於 24 小時內驗證");
  }

  @Test
  void verificationEmailTraditionalChineseComposition() {
    String token = "raw-verification-token-" + UUID.randomUUID();
    IdentityEmailEvent.Verification event =
        new IdentityEmailEvent.Verification("bob@example.com", "Bob User", token, "zh-TW");

    listener.handle(event);

    SimpleMailMessage message = captureSentMessage();
    assertThat(message.getTo()).containsExactly("bob@example.com");
    assertThat(message.getSubject()).isEqualTo("驗證您的 Email");
    assertThat(message.getText())
        .contains("您好 Bob User")
        .contains("，請於 24 小時內驗證：")
        .contains(FRONTEND_URL + "/verify-email?token=")
        .contains(token);
  }

  @Test
  void passwordResetEmailComposition() {
    String token = "raw-password-reset-token-" + UUID.randomUUID();
    IdentityEmailEvent.PasswordReset event =
        new IdentityEmailEvent.PasswordReset("charlie@example.com", token);

    listener.handle(event);

    SimpleMailMessage message = captureSentMessage();
    assertThat(message.getTo()).containsExactly("charlie@example.com");
    assertThat(message.getSubject()).isEqualTo("Password reset / 密碼重設");
    assertThat(message.getText()).contains(FRONTEND_URL + "/reset-password?token=").contains(token);
  }

  @Test
  void invitationEmailComposition() {
    String token = "raw-invitation-token-" + UUID.randomUUID();
    IdentityEmailEvent.Invitation event =
        new IdentityEmailEvent.Invitation("david@example.com", token);

    listener.handle(event);

    SimpleMailMessage message = captureSentMessage();
    assertThat(message.getTo()).containsExactly("david@example.com");
    assertThat(message.getSubject()).isEqualTo("Invitation / 邀請");
    assertThat(message.getText())
        .contains(FRONTEND_URL + "/invite?token=")
        .contains(token)
        .contains("24");
  }

  @Test
  void emailChangeConfirmationComposition() {
    String token = "raw-email-change-token-" + UUID.randomUUID();
    IdentityEmailEvent.EmailChangeConfirmation event =
        new IdentityEmailEvent.EmailChangeConfirmation("new-email@example.com", token);

    listener.handle(event);

    SimpleMailMessage message = captureSentMessage();
    assertThat(message.getTo()).containsExactly("new-email@example.com");
    assertThat(message.getSubject()).isEqualTo("Email change / Email 變更");
    assertThat(message.getText()).contains(FRONTEND_URL + "/confirm-email?token=").contains(token);
  }

  @Test
  void emailChangedNotificationComposition() {
    IdentityEmailEvent.EmailChangedNotification event =
        new IdentityEmailEvent.EmailChangedNotification("old-email@example.com");

    listener.handle(event);

    SimpleMailMessage message = captureSentMessage();
    assertThat(message.getTo()).containsExactly("old-email@example.com");
    assertThat(message.getSubject()).isEqualTo("Email changed / Email 已變更");
    assertThat(message.getText()).contains("Your email was changed.").contains("您的 Email 已變更。");
  }

  @Test
  void mailFailureLogsMaskedRecipientAndMailTypeWithoutTokenOrFullUrl() {
    String secretToken = "super-secret-token-123456789";
    IdentityEmailEvent.Verification event =
        new IdentityEmailEvent.Verification("victim@example.com", "Victim", secretToken, "en");

    doThrow(new RuntimeException("SMTP connection refused"))
        .when(mailSender)
        .send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

    Logger logger = (Logger) LoggerFactory.getLogger(IdentityEmailEventListener.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      listener.handle(event);

      assertThat(appender.list).isNotEmpty();
      String loggedMessage = appender.list.get(0).getFormattedMessage();

      assertThat(loggedMessage).contains("mailType=Verification");
      assertThat(loggedMessage).contains("recipient=v***m@example.com");
      assertThat(loggedMessage).doesNotContain(secretToken);
      assertThat(loggedMessage).doesNotContain("/verify-email?token=");
      assertThat(loggedMessage).doesNotContain("victim@example.com");
    } finally {
      logger.detachAppender(appender);
    }
  }

  private SimpleMailMessage captureSentMessage() {
    ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(captor.capture());
    return captor.getValue();
  }
}
