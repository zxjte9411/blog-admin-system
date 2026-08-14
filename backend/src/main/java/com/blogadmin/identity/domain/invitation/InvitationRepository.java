package com.blogadmin.identity.domain.invitation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
  Optional<Invitation> findByTokenHash(byte[] hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Invitation i where i.tokenHash = :hash")
  Optional<Invitation> findLockedByTokenHash(@Param("hash") byte[] hash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<Invitation> findByEmailAndUsedAtIsNull(String email);
}
