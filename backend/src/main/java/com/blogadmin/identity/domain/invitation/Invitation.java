package com.blogadmin.identity.domain.invitation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_invitations")
public class Invitation {
  @Id private UUID id;
  private String email;
  private byte[] tokenHash;
  private Instant expiresAt;
  private Instant usedAt;

  protected Invitation() {}

  public Invitation(UUID id, String email, byte[] tokenHash, Instant expiresAt) {
    this.id = id;
    this.email = email;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
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
