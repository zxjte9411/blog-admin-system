package com.blogadmin.identity.web.dto;

import com.blogadmin.identity.domain.user.UserRole;

public record AccountMeResponse(String displayName, String preferredLanguage, UserRole role) {}
