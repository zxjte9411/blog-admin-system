package com.blogadmin.identity.web.dto;

import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRole;
import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
    UUID id, String email, String displayName, UserRole role, boolean enabled, Instant verifiedAt) {
  public static AdminUserResponse of(User user) {
    return new AdminUserResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.getRole(),
        user.isEnabled(),
        user.getVerifiedAt());
  }
}
