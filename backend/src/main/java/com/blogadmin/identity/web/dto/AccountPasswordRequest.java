package com.blogadmin.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountPasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank @Size(max = 128) String newPassword,
    Boolean logoutCurrentSession) {}
