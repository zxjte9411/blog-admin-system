package com.blogadmin.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<EmailVerificationToken> findByTokenHash(byte[] hash);

  @Query("select t.userId from EmailVerificationToken t where t.tokenHash = :hash")
  Optional<UUID> findUserIdByTokenHash(@Param("hash") byte[] hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<EmailVerificationToken> findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(UUID userId);
}
