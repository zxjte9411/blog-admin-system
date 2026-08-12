package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.RegistrationService;
import com.blogadmin.identity.web.dto.EmailVerificationRequestDTO;
import com.blogadmin.identity.web.dto.RegistrationRequestDTO;
import com.blogadmin.identity.web.dto.ResendEmailVerificationRequestDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class RegistrationController {
  private final RegistrationService service;

  @PostMapping("/registrations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void register(
      @Valid @RequestBody RegistrationRequestDTO request, HttpServletRequest http) {
    service.register(
        request.getEmail(),
        request.getDisplayName(),
        request.getPassword(),
        request.getPreferredLanguage() == null ? "zh-TW" : request.getPreferredLanguage(),
        http.getRemoteAddr());
  }

  @PostMapping("/email-verifications/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void resend(
      @Valid @RequestBody ResendEmailVerificationRequestDTO request, HttpServletRequest http) {
    service.resend(request.getEmail(), http.getRemoteAddr());
  }

  @PostMapping("/email-verifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(@Valid @RequestBody EmailVerificationRequestDTO request) {
    if (!service.verify(request.getToken())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email verification token not found");
    }
  }
}
