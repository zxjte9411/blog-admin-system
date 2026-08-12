package com.blogadmin.identity.domain;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateLimitEventRepository extends JpaRepository<RateLimitEvent, Long> {
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:b || ':' || :k, 0))) locked",
      nativeQuery = true)
  int lockBucket(@Param("b") String bucket, @Param("k") String key);

  @Modifying
  @Query(
      value =
          "DELETE FROM auth_rate_limit_events WHERE bucket=:b AND bucket_key=:k AND requested_at < NOW() - INTERVAL '1 hour'",
      nativeQuery = true)
  int deleteExpired(@Param("b") String bucket, @Param("k") String key);

  @Query(
      value =
          "SELECT COUNT(*) FROM auth_rate_limit_events WHERE bucket=:b AND bucket_key=:k AND requested_at >= NOW() - INTERVAL '1 hour'",
      nativeQuery = true)
  long countSince(@Param("b") String bucket, @Param("k") String key);

  @Query(
      value =
          "SELECT requested_at FROM auth_rate_limit_events WHERE bucket=:b AND bucket_key=:k AND requested_at >= NOW() - INTERVAL '1 hour' ORDER BY requested_at LIMIT 1",
      nativeQuery = true)
  Optional<Instant> findOldestSince(@Param("b") String bucket, @Param("k") String key);
}
