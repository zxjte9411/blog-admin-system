package com.blogadmin.identity.web.dto;

import com.blogadmin.identity.domain.user.UserRole;

public record AdminUserUpdateRequest(UserRole role, Boolean enabled) {}
