package com.blogadmin.identity.domain.emailchange;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_change_tokens")
public class EmailChangeToken {
  @Id private UUID id;
  private UUID userId;
  private String newEmail;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;

  protected EmailChangeToken() {}

  public EmailChangeToken(UUID id, UUID userId, String email, byte[] hash, Instant expiry) {
    this.id = id;
    this.userId = userId;
    newEmail = email;
    tokenHash = hash;
    expiresAt = expiry;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getNewEmail() {
    return newEmail;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void use(Instant at) {
    if (this.usedAt == null) {
      this.usedAt = at;
    }
  }
}
