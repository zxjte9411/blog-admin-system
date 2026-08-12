package com.blogadmin.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AccountEmailRequest(@NotBlank @Email String email) {}
