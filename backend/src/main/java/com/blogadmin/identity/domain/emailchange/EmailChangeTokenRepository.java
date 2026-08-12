package com.blogadmin.identity.domain.emailchange;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeToken, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<EmailChangeToken> findByTokenHash(byte[] hash);

  List<EmailChangeToken> findByUserIdAndUsedAtIsNull(UUID id);
}
