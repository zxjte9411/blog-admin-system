package com.blogadmin.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class EmailVerificationRequestDTO {
  @NotBlank private String token;

  @Builder
  public EmailVerificationRequestDTO(String token) {
    this.token = token;
  }
}
