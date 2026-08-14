package com.blogadmin.identity.domain.user;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
  @Id private UUID id;
  private String email;
  private String normalizedEmail;
  private String displayName;
  private String passwordHash;
  private String preferredLanguage;

  @Enumerated(EnumType.STRING)
  private UserRole role;

  private boolean enabled;
  private Instant verifiedAt;
  private int accessTokenVersion;

  public User(
      UUID id,
      String email,
      String normalizedEmail,
      String displayName,
      String passwordHash,
      String preferredLanguage) {
    this.id = id;
    this.email = email;
    this.normalizedEmail = normalizedEmail;
    this.displayName = displayName == null ? null : displayName.trim();
    this.passwordHash = passwordHash;
    this.preferredLanguage = preferredLanguage;
    this.role = UserRole.AUTHOR;
    this.enabled = true;
  }

  public void updateProfile(String name, String language) {
    displayName = name == null ? null : name.trim();
    preferredLanguage = language;
  }

  public void changePassword(String hash) {
    passwordHash = hash;
    accessTokenVersion++;
  }

  public void changePasswordKeepingSessions(String hash) {
    passwordHash = hash;
  }

  public void changeEmail(String email) {
    this.email = email == null ? null : email.trim();
    this.normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  public void verify(Instant at) {
    if (this.verifiedAt == null) {
      this.verifiedAt = at;
    }
  }

  public void disable() {
    enabled = false;
  }

  public void setEnabled(boolean enabled) {
    if (this.enabled != enabled) {
      this.enabled = enabled;
      accessTokenVersion++;
    }
  }

  public void changeRole(UserRole newRole) {
    newRole = Objects.requireNonNull(newRole);
    if (role != newRole) {
      role = newRole;
      accessTokenVersion++;
    }
  }
}
