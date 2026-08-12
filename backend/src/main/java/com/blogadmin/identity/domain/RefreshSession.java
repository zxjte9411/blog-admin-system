package com.blogadmin.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
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

  protected RefreshSession() {}

  public RefreshSession(
      UUID id, UUID userId, byte[] tokenHash, Instant now, int userAccessTokenVersion) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    createdAt = now;
    lastUsedAt = now;
    expiresAt = now.plusSeconds(604800);
    this.userAccessTokenVersion = userAccessTokenVersion;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public boolean active() {
    return revokedAt == null && expiresAt.isAfter(Instant.now());
  }

  public int getAccessTokenVersion() {
    return accessTokenVersion;
  }

  public int getUserAccessTokenVersion() {
    return userAccessTokenVersion;
  }

  public void rotate(byte[] newTokenHash, Instant now) {
    tokenHash = newTokenHash;
    lastUsedAt = now;
    accessTokenVersion++;
  }

  public void revoke(Instant now) {
    revokedAt = now;
  }
}
