package com.blogadmin.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {
  @Id private UUID id;
  private UUID userId;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;
  private Instant invalidatedAt;

  protected EmailVerificationToken() {}

  public EmailVerificationToken(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
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

  public Instant getInvalidatedAt() {
    return invalidatedAt;
  }

  public void use(Instant at) {
    usedAt = at;
  }

  public void invalidate(Instant at) {
    invalidatedAt = at;
  }
}
