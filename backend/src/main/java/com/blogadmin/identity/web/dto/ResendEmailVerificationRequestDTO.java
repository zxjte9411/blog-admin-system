package com.blogadmin.identity.web.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendEmailVerificationRequestDTO {
  @Email @NotBlank private String email;

  @JsonSetter("email")
  public void setEmail(String email) {
    this.email = email == null ? null : email.trim();
  }
}
