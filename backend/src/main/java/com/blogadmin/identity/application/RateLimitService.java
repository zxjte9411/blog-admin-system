package com.blogadmin.identity.application;

import com.blogadmin.identity.domain.ratelimit.RateLimitEvent;
import com.blogadmin.identity.domain.ratelimit.RateLimitEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RateLimitService {
  private final RateLimitEventRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Decision consume(String bucket, String ip, String email) {
    Decision ipDecision = consumeKey(bucket, "ip:" + ip);
    Decision emailDecision = consumeKey(bucket, "email:" + email);
    long retry = 1;
    if (!ipDecision.allowed()) retry = Math.max(retry, ipDecision.retryAfterSeconds());
    if (!emailDecision.allowed()) retry = Math.max(retry, emailDecision.retryAfterSeconds());
    return new Decision(ipDecision.allowed() && emailDecision.allowed(), Math.min(3600, retry));
  }

  private Decision consumeKey(String bucket, String key) {
    repository.lockBucket(bucket, key);
    repository.deleteExpired(bucket, key);
    long count = repository.countSince(bucket, key);
    long retry =
        repository
            .findOldestSince(bucket, key)
            .map(
                at ->
                    Math.max(
                        1,
                        (long)
                            Math.ceil(
                                (at.plusSeconds(3600).toEpochMilli() - Instant.now().toEpochMilli())
                                    / 1000.0)))
            .orElse(1L);
    repository.save(new RateLimitEvent(bucket, key, Instant.now()));
    return new Decision(count < 3, retry);
  }

  public record Decision(boolean allowed, long retryAfterSeconds) {}
}
