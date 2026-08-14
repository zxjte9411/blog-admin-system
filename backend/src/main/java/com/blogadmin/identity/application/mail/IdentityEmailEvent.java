package com.blogadmin.identity.application.mail;

public sealed interface IdentityEmailEvent {
  record Verification(String to, String displayName, String token, String language)
      implements IdentityEmailEvent {}

  record PasswordReset(String to, String token) implements IdentityEmailEvent {}

  record Invitation(String to, String token) implements IdentityEmailEvent {}

  record EmailChangeConfirmation(String to, String token) implements IdentityEmailEvent {}

  record EmailChangedNotification(String to) implements IdentityEmailEvent {}
}
