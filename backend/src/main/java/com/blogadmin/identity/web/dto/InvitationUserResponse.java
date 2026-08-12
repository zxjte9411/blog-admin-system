package com.blogadmin.identity.web.dto;

import com.blogadmin.identity.domain.UserRole;
import java.time.Instant;
import java.util.UUID;

public record InvitationUserResponse(
    UUID id,
    String email,
    String displayName,
    UserRole role,
    boolean enabled,
    Instant verifiedAt) {}
