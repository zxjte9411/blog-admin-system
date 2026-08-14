package com.blogadmin.identity.domain.emailchange;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeToken, UUID> {
  Optional<EmailChangeToken> findByTokenHash(byte[] hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from EmailChangeToken t where t.tokenHash = :hash")
  Optional<EmailChangeToken> findLockedByTokenHash(@Param("hash") byte[] hash);

  @Query("select t.userId from EmailChangeToken t where t.tokenHash = :hash")
  Optional<UUID> findUserIdByTokenHash(@Param("hash") byte[] hash);

  List<EmailChangeToken> findByUserIdAndUsedAtIsNull(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from EmailChangeToken t where t.userId = :userId and t.usedAt is null")
  List<EmailChangeToken> findLockedByUserIdAndUsedAtIsNull(@Param("userId") UUID userId);
}
