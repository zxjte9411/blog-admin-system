package com.blogadmin.identity.domain.password;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {
  @Id private UUID id;
  private UUID userId;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;

  public PasswordResetToken(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt) {
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
}
