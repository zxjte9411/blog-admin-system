package com.blogadmin.identity.domain.password;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordSettingChangeRepository
    extends JpaRepository<PasswordSettingChange, UUID> {
  List<PasswordSettingChange> findAllByOrderByChangedAtDesc();
}
