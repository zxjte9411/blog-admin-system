package com.blogadmin.identity.web;

import com.blogadmin.identity.application.AccountService;
import com.blogadmin.identity.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

  public record Profile(
      @NotBlank @Size(max = 100) String displayName,
      @NotBlank @Pattern(regexp = "zh-TW|en") String preferredLanguage) {}

  public record Password(
      @NotBlank String currentPassword,
      @NotBlank @Size(max = 128) String newPassword,
      Boolean logoutCurrentSession) {}

  public record Email(@NotBlank @jakarta.validation.constraints.Email String email) {}

  @PatchMapping("/api/v1/account/profile")
  public Profile profile(@Valid @RequestBody Profile r, Authentication a) {
    User u = service.profile((User) a.getPrincipal(), r.displayName(), r.preferredLanguage());
    return new Profile(u.getDisplayName(), u.getPreferredLanguage());
  }

  @PutMapping("/api/v1/account/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void password(@Valid @RequestBody Password r, Authentication a) {
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
  public void email(@Valid @RequestBody Email r, Authentication a) {
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
