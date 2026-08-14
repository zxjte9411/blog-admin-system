package com.blogadmin.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blogadmin.identity.domain.ratelimit.RateLimitEvent;
import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

  private RateLimitEventRepository rateLimitEventRepository;
  private RateLimitService rateLimitService;

  @BeforeEach
  void setUp() {
    rateLimitEventRepository = mock(RateLimitEventRepository.class);
    rateLimitService = new RateLimitService(rateLimitEventRepository);
  }

  @Test
  @DisplayName("IP allowed + Email allowed: allows request and records both rate-limit events")
  void allowsRequestWhenBothIpAndEmailAreUnderThreshold() {
    String bucket = "registration";
    String ip = "192.168.1.100";
    String email = "test@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    when(rateLimitEventRepository.countSince(bucket, ipKey)).thenReturn(1L);
    when(rateLimitEventRepository.countSince(bucket, emailKey)).thenReturn(2L);
    when(rateLimitEventRepository.findOldestSince(bucket, ipKey)).thenReturn(Optional.empty());
    when(rateLimitEventRepository.findOldestSince(bucket, emailKey)).thenReturn(Optional.empty());

    RateLimitService.Decision decision = rateLimitService.consume(bucket, ip, email);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.retryAfterSeconds()).isEqualTo(1L);

    verify(rateLimitEventRepository).lockBucket(bucket, ipKey);
    verify(rateLimitEventRepository).deleteExpired(bucket, ipKey);
    verify(rateLimitEventRepository).lockBucket(bucket, emailKey);
    verify(rateLimitEventRepository).deleteExpired(bucket, emailKey);

    verify(rateLimitEventRepository)
        .save(
            argThat(
                event -> event.getBucket().equals(bucket) && event.getBucketKey().equals(ipKey)));
    verify(rateLimitEventRepository)
        .save(
            argThat(
                event ->
                    event.getBucket().equals(bucket) && event.getBucketKey().equals(emailKey)));
  }

  @Test
  @DisplayName(
      "IP blocked + Email allowed: rejects request, returns IP retry-after, and does not record events")
  void rejectsRequestWhenIpIsBlocked() {
    String bucket = "login";
    String ip = "10.0.0.1";
    String email = "alice@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    Instant oldestIpEvent = Instant.now().minusSeconds(600); // 3000 seconds remaining
    when(rateLimitEventRepository.countSince(bucket, ipKey)).thenReturn(3L);
    when(rateLimitEventRepository.findOldestSince(bucket, ipKey))
        .thenReturn(Optional.of(oldestIpEvent));

    when(rateLimitEventRepository.countSince(bucket, emailKey)).thenReturn(0L);
    when(rateLimitEventRepository.findOldestSince(bucket, emailKey)).thenReturn(Optional.empty());

    RateLimitService.Decision decision = rateLimitService.consume(bucket, ip, email);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isBetween(2995L, 3005L);

    verify(rateLimitEventRepository, never()).save(any(RateLimitEvent.class));
  }

  @Test
  @DisplayName(
      "IP allowed + Email blocked: rejects request, returns Email retry-after, and does not record events")
  void rejectsRequestWhenEmailIsBlocked() {
    String bucket = "login";
    String ip = "10.0.0.2";
    String email = "bob@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    when(rateLimitEventRepository.countSince(bucket, ipKey)).thenReturn(1L);
    when(rateLimitEventRepository.findOldestSince(bucket, ipKey)).thenReturn(Optional.empty());

    Instant oldestEmailEvent = Instant.now().minusSeconds(1200); // 2400 seconds remaining
    when(rateLimitEventRepository.countSince(bucket, emailKey)).thenReturn(4L);
    when(rateLimitEventRepository.findOldestSince(bucket, emailKey))
        .thenReturn(Optional.of(oldestEmailEvent));

    RateLimitService.Decision decision = rateLimitService.consume(bucket, ip, email);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isBetween(2395L, 2405L);

    verify(rateLimitEventRepository, never()).save(any(RateLimitEvent.class));
  }

  @Test
  @DisplayName("IP blocked + Email blocked: chooses larger retry-after and caps at 3600")
  void rejectsRequestChoosingMaxRetryAfterWhenBothAreBlocked() {
    String bucket = "login";
    String ip = "10.0.0.3";
    String email = "charlie@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    Instant oldestIpEvent = Instant.now().minusSeconds(1000); // ~2600s remaining
    when(rateLimitEventRepository.countSince(bucket, ipKey)).thenReturn(5L);
    when(rateLimitEventRepository.findOldestSince(bucket, ipKey))
        .thenReturn(Optional.of(oldestIpEvent));

    Instant oldestEmailEvent = Instant.now().minusSeconds(200); // ~3400s remaining
    when(rateLimitEventRepository.countSince(bucket, emailKey)).thenReturn(3L);
    when(rateLimitEventRepository.findOldestSince(bucket, emailKey))
        .thenReturn(Optional.of(oldestEmailEvent));

    RateLimitService.Decision decision = rateLimitService.consume(bucket, ip, email);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isBetween(3395L, 3405L);
    assertThat(decision.retryAfterSeconds()).isLessThanOrEqualTo(3600L);

    verify(rateLimitEventRepository, never()).save(any(RateLimitEvent.class));
  }

  @Test
  @DisplayName("Retry-After is capped at 3600 and is at least 1")
  void retryAfterBoundaryConditions() {
    String bucket = "registration";
    String ip = "10.0.0.4";
    String email = "dave@example.com";
    String ipKey = "ip:" + ip;
    String emailKey = "email:" + email;

    // Simulated event in the future (skew) -> raw seconds > 3600 -> capped at 3600
    Instant futureEvent = Instant.now().plusSeconds(100);
    when(rateLimitEventRepository.countSince(bucket, ipKey)).thenReturn(3L);
    when(rateLimitEventRepository.findOldestSince(bucket, ipKey))
        .thenReturn(Optional.of(futureEvent));
    when(rateLimitEventRepository.countSince(bucket, emailKey)).thenReturn(0L);
    when(rateLimitEventRepository.findOldestSince(bucket, emailKey)).thenReturn(Optional.empty());

    RateLimitService.Decision decision = rateLimitService.consume(bucket, ip, email);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isEqualTo(3600L);
  }
}
