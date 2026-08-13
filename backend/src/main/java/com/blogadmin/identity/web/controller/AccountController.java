package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AccountService;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.web.dto.AccountEmailRequest;
import com.blogadmin.identity.web.dto.AccountMeResponse;
import com.blogadmin.identity.web.dto.AccountPasswordRequest;
import com.blogadmin.identity.web.dto.AccountProfileRequest;
import com.blogadmin.identity.web.dto.AccountProfileResponse;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class AccountController {
  private final AccountService service;

  @GetMapping("/api/v1/account/me")
  public AccountMeResponse me(Authentication authentication) {
    User user = (User) authentication.getPrincipal();
    return new AccountMeResponse(
        user.getId(), user.getDisplayName(), user.getPreferredLanguage(), user.getRole());
  }

  @PatchMapping("/api/v1/account/profile")
  public AccountProfileResponse profile(
      @Valid @RequestBody AccountProfileRequest r, Authentication a) {
    User u = service.profile((User) a.getPrincipal(), r.displayName(), r.preferredLanguage());
    return new AccountProfileResponse(u.getDisplayName(), u.getPreferredLanguage());
  }

  @PutMapping("/api/v1/account/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(@Valid @RequestBody AccountPasswordRequest r, Authentication a) {
    service.password(
        (User) a.getPrincipal(),
        r.currentPassword(),
        r.newPassword(),
        (UUID) a.getDetails(),
        Boolean.TRUE.equals(r.logoutCurrentSession()));
  }

  @PostMapping("/api/v1/auth/password-resets")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void requestReset(@RequestBody @Valid Map<String, String> r) {
    service.requestReset(r.get("email"));
  }

  @PostMapping("/api/v1/auth/password-resets/{token}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@PathVariable String token, @RequestBody Map<String, String> r) {
    try {
      service.reset(token, r.get("password"));
    } catch (AccountService.ResetTokenNotFound e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Password reset token not found");
    }
  }

  @PostMapping("/api/v1/account/email")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void email(@Valid @RequestBody AccountEmailRequest r, Authentication a) {
    try {
      service.requestEmail((User) a.getPrincipal(), r.email());
    } catch (AccountService.AlreadyUsedEmail e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
    }
  }

  @PostMapping("/api/v1/auth/email-changes/{token}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirm(@PathVariable String token) {
    try {
      service.confirmEmail(token);
    } catch (AccountService.AlreadyUsedEmail e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
    } catch (AccountService.InvalidAccountException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email change token not found");
    }
  }
}
