package com.blogadmin.identity.domain.user;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_identities")
public class UserIdentity {
  @Id private UUID id;
  private UUID userId;
  private String provider;
  private String subject;

  protected UserIdentity() {}

  public UserIdentity(UUID id, UUID userId, String provider, String subject) {
    this.id = id;
    this.userId = userId;
    this.provider = provider;
    this.subject = subject;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getProvider() {
    return provider;
  }

  public String getSubject() {
    return subject;
  }
}
