package com.blogadmin.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PasswordSettingRepository extends JpaRepository<PasswordSetting, Boolean> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PasswordSetting> findLockedById(boolean id);
}
