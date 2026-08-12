package com.blogadmin.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountProfileRequest(
    @NotBlank @Size(max = 100) String displayName,
    @NotBlank @Pattern(regexp = "zh-TW|en") String preferredLanguage) {}
