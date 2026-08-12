package com.blogadmin.identity.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<User> findByNormalizedEmail(String email);

  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))) locked",
      nativeQuery = true)
  Integer lockNormalizedEmail(@Param("email") String email);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findLockedById(@Param("id") UUID id);
}
