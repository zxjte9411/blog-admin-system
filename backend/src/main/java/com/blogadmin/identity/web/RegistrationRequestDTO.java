package com.blogadmin.identity.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationRequestDTO {
  @Email @NotBlank private String email;

  @JsonSetter("email")
  public void setEmail(String email) {
    this.email = email == null ? null : email.trim();
  }

  @NotBlank
  @Size(min = 1, max = 100)
  private String displayName;

  @JsonSetter("displayName")
  public void setDisplayName(String displayName) {
    this.displayName = displayName == null ? null : displayName.trim();
  }

  @NotBlank
  @Size(min = 8, max = 128)
  private String password;

  @Pattern(regexp = "zh-TW|en")
  private String preferredLanguage;
}
