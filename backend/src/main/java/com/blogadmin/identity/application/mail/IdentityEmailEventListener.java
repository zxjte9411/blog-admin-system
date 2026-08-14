package com.blogadmin.identity.application.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IdentityEmailEventListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(IdentityEmailEventListener.class);
  private final JavaMailSender mail;
  private final String from;
  private final String frontend;

  public IdentityEmailEventListener(
      JavaMailSender mail,
      @Value("${app.mail.from:dev@example.com}") String from,
      @Value("${app.frontend-base-url:http://localhost:4200}") String frontend) {
    this.mail = mail;
    this.from = from;
    this.frontend = frontend;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handle(IdentityEmailEvent event) {
    try {
      SimpleMailMessage message = compose(event);
      mail.send(message);
    } catch (RuntimeException exception) {
      LOGGER.warn(
          "Identity email delivery failed for mailType={}, recipient={}",
          event.getClass().getSimpleName(),
          maskEmail(recipientOf(event)),
          exception);
    }
  }

  private SimpleMailMessage compose(IdentityEmailEvent event) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);

    switch (event) {
      case IdentityEmailEvent.Verification verification -> {
        message.setTo(verification.to());
        boolean isEn = "en".equalsIgnoreCase(verification.language());
        message.setSubject(isEn ? "Verify your email" : "驗證您的 Email");
        message.setText(
            (isEn ? "Hi " : "您好 ")
                + verification.displayName()
                + (isEn ? ", verify within 24 hours: " : "，請於 24 小時內驗證：")
                + frontend
                + "/verify-email?token="
                + verification.token());
      }
      case IdentityEmailEvent.PasswordReset passwordReset -> {
        message.setTo(passwordReset.to());
        message.setSubject("Password reset / 密碼重設");
        message.setText(
            "Reset password / 重設密碼: "
                + frontend
                + "/reset-password?token="
                + passwordReset.token());
      }
      case IdentityEmailEvent.Invitation invitation -> {
        message.setTo(invitation.to());
        message.setSubject("Invitation / 邀請");
        message.setText(
            "You are invited / 您收到邀請："
                + frontend
                + "/invite?token="
                + invitation.token()
                + " (valid 24 hours / 24 小時有效)");
      }
      case IdentityEmailEvent.EmailChangeConfirmation emailChange -> {
        message.setTo(emailChange.to());
        message.setSubject("Email change / Email 變更");
        message.setText(
            "Confirm / 確認: " + frontend + "/confirm-email?token=" + emailChange.token());
      }
      case IdentityEmailEvent.EmailChangedNotification changedNotification -> {
        message.setTo(changedNotification.to());
        message.setSubject("Email changed / Email 已變更");
        message.setText("Your email was changed. / 您的 Email 已變更。");
      }
    }

    return message;
  }

  private static String recipientOf(IdentityEmailEvent event) {
    return switch (event) {
      case IdentityEmailEvent.Verification v -> v.to();
      case IdentityEmailEvent.PasswordReset p -> p.to();
      case IdentityEmailEvent.Invitation i -> i.to();
      case IdentityEmailEvent.EmailChangeConfirmation e -> e.to();
      case IdentityEmailEvent.EmailChangedNotification n -> n.to();
    };
  }

  public static String maskEmail(String email) {
    if (email == null || email.isBlank()) {
      return "***";
    }
    int atIndex = email.indexOf('@');
    if (atIndex <= 0) {
      return "***";
    }
    String local = email.substring(0, atIndex);
    String domain = email.substring(atIndex);
    if (local.length() <= 2) {
      return local.charAt(0) + "***" + domain;
    }
    return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
  }
}
