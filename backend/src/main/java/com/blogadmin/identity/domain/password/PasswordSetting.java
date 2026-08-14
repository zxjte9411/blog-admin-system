package com.blogadmin.identity.domain.password;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "password_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordSetting {
  @Id private boolean id;
  @Setter private int minimumLength;
}
