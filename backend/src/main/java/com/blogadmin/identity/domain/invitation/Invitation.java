package com.blogadmin.identity.domain.invitation;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "admin_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {
  @Id private UUID id;
  private String email;

  @Getter(AccessLevel.NONE)
  private byte[] tokenHash;

  private Instant expiresAt;
  private Instant usedAt;

  public Invitation(UUID id, String email, byte[] tokenHash, Instant expiresAt) {
    this.id = id;
    this.email = email;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  public void use(Instant at) {
    if (this.usedAt == null) {
      this.usedAt = at;
    }
  }
}
