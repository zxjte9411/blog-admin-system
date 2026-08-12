package com.blogadmin.identity.domain.session;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "update RefreshSession s set s.revokedAt = :at where s.userId = :userId and s.revokedAt is null and s.id <> :keep")
  void revokeOthers(
      @org.springframework.data.repository.query.Param("userId") UUID userId,
      @org.springframework.data.repository.query.Param("keep") UUID keep,
      @org.springframework.data.repository.query.Param("at") Instant at);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "update RefreshSession s set s.revokedAt = :at where s.userId = :userId and s.revokedAt is null")
  void revokeAll(
      @org.springframework.data.repository.query.Param("userId") UUID userId,
      @org.springframework.data.repository.query.Param("at") Instant at);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshSession> findByTokenHash(byte[] hash);

  Optional<RefreshSession> findByIdAndRevokedAtIsNull(UUID id);

  List<RefreshSession>
      findByUserIdAndRevokedAtIsNullAndExpiresAtAfterAndUserAccessTokenVersionEqualsOrderByCreatedAtDesc(
          UUID userId, Instant now, int userAccessTokenVersion);
}
