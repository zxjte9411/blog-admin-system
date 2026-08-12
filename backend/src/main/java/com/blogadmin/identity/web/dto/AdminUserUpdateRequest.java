package com.blogadmin.identity.web.dto;

import com.blogadmin.identity.domain.UserRole;

public record AdminUserUpdateRequest(UserRole role, Boolean enabled) {}
