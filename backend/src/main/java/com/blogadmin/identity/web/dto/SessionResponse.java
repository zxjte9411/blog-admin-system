package com.blogadmin.identity.web.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID id, boolean current, Instant createdAt, Instant lastUsedAt) {}
