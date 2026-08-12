package com.blogadmin.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auth_rate_limit_events")
@NoArgsConstructor
public class RateLimitEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String bucket;
  private String bucketKey;
  private Instant requestedAt;

  public RateLimitEvent(String bucket, String bucketKey, Instant requestedAt) {
    this.bucket = bucket;
    this.bucketKey = bucketKey;
    this.requestedAt = requestedAt;
  }
}
