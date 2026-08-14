package com.blogadmin.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RateLimitPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired private RateLimitService rateLimitService;
  @Autowired private RateLimitEventRepository rateLimitEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    resetDatabase(jdbcTemplate);
  }

  @Test
  @DisplayName(
      "Threshold persistence: allowed requests persist events, blocked requests add no new events")
  void thresholdPersistenceAllowsUpToLimitAndBlocksSubsequentWithoutRecordingEvents() {
    String bucket = "login";
    String ip = "192.168.10.1";
    String email = "threshold@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    // Requests 1-3 should succeed and persist events
    RateLimitService.Decision d1 = rateLimitService.consume(bucket, ip, email);
    RateLimitService.Decision d2 = rateLimitService.consume(bucket, ip, email);
    RateLimitService.Decision d3 = rateLimitService.consume(bucket, ip, email);

    assertThat(d1.allowed()).isTrue();
    assertThat(d2.allowed()).isTrue();
    assertThat(d3.allowed()).isTrue();

    // Requests 4-5 should be blocked and persist NO additional events
    RateLimitService.Decision d4 = rateLimitService.consume(bucket, ip, email);
    RateLimitService.Decision d5 = rateLimitService.consume(bucket, ip, email);

    assertThat(d4.allowed()).isFalse();
    assertThat(d4.retryAfterSeconds()).isBetween(3590L, 3600L);
    assertThat(d5.allowed()).isFalse();
    assertThat(d5.retryAfterSeconds()).isBetween(3590L, 3600L);

    // Verify DB state directly: exactly 3 events per key in auth_rate_limit_events
    long ipCount = rateLimitEventRepository.countSince(bucket, ipKey);
    long emailCount = rateLimitEventRepository.countSince(bucket, emailKey);
    Integer totalRows =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events", Integer.class);

    assertThat(ipCount).isEqualTo(3);
    assertThat(emailCount).isEqualTo(3);
    assertThat(totalRows).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "Expired-event cleanup: events older than 1 hour are cleaned up and do not count towards threshold")
  void expiredEventsAreCleanedUpAndDoNotCountTowardsLimit() {
    String bucket = "login";
    String ip = "192.168.10.2";
    String email = "expired@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    // Insert 2 expired events (2 hours ago)
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '2 hours')",
        bucket,
        ipKey);
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '2 hours')",
        bucket,
        emailKey);

    // Insert 1 active event (15 minutes ago)
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '15 minutes')",
        bucket,
        ipKey);
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '15 minutes')",
        bucket,
        emailKey);

    Integer rowsBefore =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events", Integer.class);
    assertThat(rowsBefore).isEqualTo(4);

    // Consuming now should delete expired events, count active (1 < 3), allow the request, and
    // insert the new event
    RateLimitService.Decision decision = rateLimitService.consume(bucket, ip, email);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.retryAfterSeconds()).isEqualTo(1L);

    // Verify in PostgreSQL that expired events were deleted
    Integer expiredRemaining =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_rate_limit_events WHERE requested_at < NOW() - INTERVAL '1 hour'",
            Integer.class);
    assertThat(expiredRemaining).isEqualTo(0);

    // Active count is now 2 (1 existing active + 1 new)
    long ipCount = rateLimitEventRepository.countSince(bucket, ipKey);
    long emailCount = rateLimitEventRepository.countSince(bucket, emailKey);
    Integer totalRowsAfter =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events", Integer.class);

    assertThat(ipCount).isEqualTo(2);
    assertThat(emailCount).isEqualTo(2);
    assertThat(totalRowsAfter).isEqualTo(4);
  }

  @Test
  @Timeout(15)
  @DisplayName(
      "Concurrency: concurrent requests orchestrated via CyclicBarrier enforce mutual exclusion without threshold breach")
  void concurrentRequestsPreventRaceConditionAndDoNotBreachThreshold() throws Exception {
    String bucket = "login";
    String ip = "192.168.10.3";
    String email = "concurrent@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    // Seed DB with 2 active events for both IP and Email (threshold is 3, exactly 1 slot remains)
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '10 minutes')",
        bucket,
        ipKey);
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '5 minutes')",
        bucket,
        ipKey);
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '10 minutes')",
        bucket,
        emailKey);
    jdbcTemplate.update(
        "INSERT INTO auth_rate_limit_events (bucket, bucket_key, requested_at) VALUES (?, ?, NOW() - INTERVAL '5 minutes')",
        bucket,
        emailKey);

    CyclicBarrier barrier = new CyclicBarrier(2);

    Callable<RateLimitService.Decision> task =
        () -> {
          awaitBarrier(barrier, 5, TimeUnit.SECONDS, "threads synchronization before consume");
          return rateLimitService.consume(bucket, ip, email);
        };

    List<RateLimitService.Decision> decisions = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<RateLimitService.Decision> f1 = executor.submit(task);
      Future<RateLimitService.Decision> f2 = executor.submit(task);

      decisions.add(f1.get(10, TimeUnit.SECONDS));
      decisions.add(f2.get(10, TimeUnit.SECONDS));
    }

    long allowedCount = decisions.stream().filter(RateLimitService.Decision::allowed).count();
    long blockedCount = decisions.stream().filter(d -> !d.allowed()).count();

    // Exactly one request must be allowed, and exactly one request must be blocked
    assertThat(allowedCount).isEqualTo(1);
    assertThat(blockedCount).isEqualTo(1);

    // Total events in DB must be exactly 3 per key (2 initial + 1 from allowed request; 0 from
    // blocked request)
    long ipCount = rateLimitEventRepository.countSince(bucket, ipKey);
    long emailCount = rateLimitEventRepository.countSince(bucket, emailKey);
    Integer totalRows =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events", Integer.class);

    assertThat(ipCount).isEqualTo(3);
    assertThat(emailCount).isEqualTo(3);
    assertThat(totalRows).isEqualTo(6);
  }

  private static void awaitBarrier(
      CyclicBarrier barrier, long timeout, TimeUnit unit, String stageDescription)
      throws Exception {
    try {
      barrier.await(timeout, unit);
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Synchronization timeout: "
              + stageDescription
              + " did not complete within "
              + timeout
              + " "
              + unit.name().toLowerCase(Locale.ROOT),
          exception);
    }
  }
}
