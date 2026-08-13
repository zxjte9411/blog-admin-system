package com.blogadmin.identity.web.dto;

import com.blogadmin.identity.domain.user.UserRole;
import java.util.UUID;

public record AccountMeResponse(
    UUID id, String displayName, String preferredLanguage, UserRole role) {}
