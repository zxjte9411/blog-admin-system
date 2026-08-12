package com.blogadmin.identity.web.dto;

import java.time.Instant;

public record LoginResponse(String accessToken, Instant accessTokenExpiresAt) {}
