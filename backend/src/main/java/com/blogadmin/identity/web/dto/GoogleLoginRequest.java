package com.blogadmin.identity.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
    @NotBlank @JsonAlias({"token", "supabaseToken"}) String accessToken) {}
