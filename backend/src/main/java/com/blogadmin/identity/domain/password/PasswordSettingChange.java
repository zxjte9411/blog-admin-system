package com.blogadmin.identity.domain.password;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_setting_changes")
public class PasswordSettingChange {
  @Id private UUID id;
  private UUID operatorId;
  private int previousValue;
  private int newValue;
  private Instant changedAt;

  protected PasswordSettingChange() {}

  public PasswordSettingChange(
      UUID id, UUID operatorId, int previousValue, int newValue, Instant changedAt) {
    this.id = id;
    this.operatorId = operatorId;
    this.previousValue = previousValue;
    this.newValue = newValue;
    this.changedAt = changedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOperatorId() {
    return operatorId;
  }

  public int getPreviousValue() {
    return previousValue;
  }

  public int getNewValue() {
    return newValue;
  }

  public Instant getChangedAt() {
    return changedAt;
  }
}
