package com.blogadmin.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshSession> findByTokenHash(byte[] hash);

  Optional<RefreshSession> findByIdAndRevokedAtIsNull(UUID id);

  List<RefreshSession>
      findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndUserAccessTokenVersionEqualsOrderByCreatedAtDesc(
          UUID userId, java.time.Instant now, int userAccessTokenVersion);
}
