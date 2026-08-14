package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AccountService;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.web.dto.AccountEmailRequest;
import com.blogadmin.identity.web.dto.AccountMeResponse;
import com.blogadmin.identity.web.dto.AccountPasswordRequest;
import com.blogadmin.identity.web.dto.AccountProfileRequest;
import com.blogadmin.identity.web.dto.AccountProfileResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class AccountController {
  private final AccountService accountService;

  @GetMapping("/api/v1/account/me")
  public AccountMeResponse me(Authentication authentication) {
    User user = (User) authentication.getPrincipal();
    return new AccountMeResponse(
        user.getId(), user.getDisplayName(), user.getPreferredLanguage(), user.getRole());
  }

  @PatchMapping("/api/v1/account/profile")
  public AccountProfileResponse profile(
      @Valid @RequestBody AccountProfileRequest request, Authentication authentication) {
    User user =
        accountService.profile(
            (User) authentication.getPrincipal(),
            request.displayName(),
            request.preferredLanguage());
    return new AccountProfileResponse(user.getDisplayName(), user.getPreferredLanguage());
  }

  @PutMapping("/api/v1/account/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(
      @Valid @RequestBody AccountPasswordRequest request, Authentication authentication) {
    accountService.password(
        (User) authentication.getPrincipal(),
        request.currentPassword(),
        request.newPassword(),
        (UUID) authentication.getDetails(),
        Boolean.TRUE.equals(request.logoutCurrentSession()));
  }

  @PostMapping("/api/v1/auth/password-resets")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void requestReset(@RequestBody @Valid Map<String, String> request) {
    accountService.requestReset(request.get("email"));
  }

  @PostMapping("/api/v1/auth/password-resets/{token}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@PathVariable String token, @RequestBody Map<String, String> request) {
    try {
      accountService.reset(token, request.get("password"));
    } catch (AccountService.ResetTokenNotFound exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Password reset token not found");
    }
  }

  @PostMapping("/api/v1/account/email")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void email(
      @Valid @RequestBody AccountEmailRequest request, Authentication authentication) {
    try {
      accountService.requestEmail((User) authentication.getPrincipal(), request.email());
    } catch (AccountService.AlreadyUsedEmail exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
    }
  }

  @PostMapping("/api/v1/auth/email-changes/{token}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirm(@PathVariable String token) {
    try {
      accountService.confirmEmail(token);
    } catch (AccountService.AlreadyUsedEmail exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
    } catch (AccountService.InvalidAccountException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Email change token not found");
    }
  }
}
