package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.password.PasswordSettingChange;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRole;
import com.blogadmin.identity.web.dto.AdminUserResponse;
import com.blogadmin.identity.web.dto.AdminUserUpdateRequest;
import com.blogadmin.identity.web.dto.PasswordMinimumRequest;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminUserController {
  private final AdminUserService adminUserService;

  @GetMapping("/users")
  public List<AdminUserResponse> users(
      @RequestParam(required = false) UserRole role,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String query) {
    return adminUserService.list(role, enabled, query).stream().map(AdminUserResponse::of).toList();
  }

  @PatchMapping("/users/{id}")
  public AdminUserResponse update(
      @PathVariable UUID id,
      @RequestBody AdminUserUpdateRequest request,
      Authentication authentication) {
    return AdminUserResponse.of(
        adminUserService.update(
            (User) authentication.getPrincipal(), id, request.role(), request.enabled()));
  }

  @PostMapping("/invitations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void invite(@RequestBody Map<String, String> request) {
    adminUserService.invite(request.get("email"));
  }

  @GetMapping("/invitations")
  public List<Invitation> invitations() {
    return adminUserService.invitations();
  }

  @GetMapping("/settings/password-minimum-length")
  public Map<String, Integer> current() {
    return Map.of("value", adminUserService.getMinimum());
  }

  @PutMapping("/settings/password-minimum-length")
  public Map<String, Integer> minimum(
      @RequestBody PasswordMinimumRequest request, Authentication authentication) {
    return Map.of(
        "value",
        adminUserService.setMinimum((User) authentication.getPrincipal(), request.value()));
  }

  @GetMapping("/settings/password-minimum-length/history")
  public List<PasswordSettingChange> history() {
    return adminUserService.history();
  }
}
