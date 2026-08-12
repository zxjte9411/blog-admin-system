package com.blogadmin.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "password_settings")
public class PasswordSetting {
  @Id private boolean id;
  private int minimumLength;

  protected PasswordSetting() {}

  public int getMinimumLength() {
    return minimumLength;
  }

  public void setMinimumLength(int value) {
    minimumLength = value;
  }
}
