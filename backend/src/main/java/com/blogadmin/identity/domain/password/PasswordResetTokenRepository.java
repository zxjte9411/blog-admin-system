package com.blogadmin.identity.domain.password;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
  Optional<PasswordResetToken> findByTokenHash(byte[] hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PasswordResetToken t where t.tokenHash = :hash")
  Optional<PasswordResetToken> findLockedByTokenHash(@Param("hash") byte[] hash);

  @Query("select t.userId from PasswordResetToken t where t.tokenHash = :hash")
  Optional<UUID> findUserIdByTokenHash(@Param("hash") byte[] hash);

  List<PasswordResetToken> findByUserIdAndUsedAtIsNull(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PasswordResetToken t where t.userId = :userId and t.usedAt is null")
  List<PasswordResetToken> findLockedByUserIdAndUsedAtIsNull(@Param("userId") UUID userId);
}
