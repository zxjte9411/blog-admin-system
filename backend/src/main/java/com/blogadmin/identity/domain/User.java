package com.blogadmin.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
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

  protected User() {}

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
    this.displayName = displayName;
    this.passwordHash = passwordHash;
    this.preferredLanguage = preferredLanguage;
    this.role = UserRole.AUTHOR;
    this.enabled = true;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getNormalizedEmail() {
    return normalizedEmail;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getPreferredLanguage() {
    return preferredLanguage;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public UserRole getRole() {
    return role;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getAccessTokenVersion() {
    return accessTokenVersion;
  }

  public void verify(Instant at) {
    verifiedAt = at;
  }

  public void disable() {
    enabled = false;
  }

  public void changeRole(UserRole newRole) {
    newRole = Objects.requireNonNull(newRole);
    if (role != newRole) {
      role = newRole;
      accessTokenVersion++;
    }
  }
}
