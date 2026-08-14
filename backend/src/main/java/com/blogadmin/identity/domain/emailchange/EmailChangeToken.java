package com.blogadmin.identity.domain.emailchange;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_change_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailChangeToken {
  @Id private UUID id;
  private UUID userId;
  private String newEmail;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;

  public EmailChangeToken(
      UUID id, UUID userId, String newEmail, byte[] tokenHash, Instant expiresAt) {
    this.id = id;
    this.userId = userId;
    this.newEmail = newEmail;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public void use(Instant at) {
    if (this.usedAt == null) {
      this.usedAt = at;
    }
  }
}
