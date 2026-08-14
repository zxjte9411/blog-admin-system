package com.blogadmin.identity.domain.session;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshSession {
  @Id private UUID id;
  private UUID userId;
  private byte[] tokenHash;
  private Instant createdAt;
  private Instant lastUsedAt;
  private Instant expiresAt;
  private Instant revokedAt;
  private int accessTokenVersion;
  private int userAccessTokenVersion;

  public RefreshSession(
      UUID id, UUID userId, byte[] tokenHash, Instant now, int userAccessTokenVersion) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.createdAt = now;
    this.lastUsedAt = now;
    this.expiresAt = now.plusSeconds(604800);
    this.userAccessTokenVersion = userAccessTokenVersion;
  }

  public boolean active() {
    return revokedAt == null && expiresAt.isAfter(Instant.now());
  }

  public void rotate(byte[] newTokenHash, Instant now) {
    this.tokenHash = newTokenHash;
    this.lastUsedAt = now;
    this.accessTokenVersion++;
  }

  public void revoke(Instant now) {
    this.revokedAt = now;
  }
}
