package com.blogadmin.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {
  @Id private UUID id;
  private UUID userId;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;

  protected PasswordResetToken() {}

  public PasswordResetToken(UUID id, UUID userId, byte[] hash, Instant expiry) {
    this.id = id;
    this.userId = userId;
    tokenHash = hash;
    expiresAt = expiry;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void use(Instant at) {
    usedAt = at;
  }
}
