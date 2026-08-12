package com.blogadmin.identity.domain.invitation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
  Optional<Invitation> findByTokenHash(byte[] hash);

  java.util.List<Invitation> findByEmailAndUsedAtIsNull(String email);
}
