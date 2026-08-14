package com.blogadmin.identity.domain.verification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_verification_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationToken {
  @Id private UUID id;
  private UUID userId;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;
  private Instant invalidatedAt;

  public EmailVerificationToken(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public void use(Instant at) {
    if (this.usedAt == null) {
      this.usedAt = at;
    }
  }

  public void invalidate(Instant at) {
    if (this.invalidatedAt == null) {
      this.invalidatedAt = at;
    }
  }
}
