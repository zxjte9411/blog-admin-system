package com.blogadmin.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PasswordResetToken> findByTokenHash(byte[] hash);

  List<PasswordResetToken> findByUserIdAndUsedAtIsNull(UUID id);
}
