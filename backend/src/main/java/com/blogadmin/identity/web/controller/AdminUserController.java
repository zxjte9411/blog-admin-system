package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.invitation.*;
import com.blogadmin.identity.domain.password.*;
import com.blogadmin.identity.domain.user.*;
import com.blogadmin.identity.web.dto.AdminUserResponse;
import com.blogadmin.identity.web.dto.AdminUserUpdateRequest;
import com.blogadmin.identity.web.dto.PasswordMinimumRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminUserController {
  private final AdminUserService service;

  @GetMapping("/users")
  public List<AdminUserResponse> users(
      @RequestParam(required = false) UserRole role,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String q) {
    return service.list(role, enabled, q).stream().map(AdminUserResponse::of).toList();
  }

  @PatchMapping("/users/{id}")
  public AdminUserResponse update(
      @PathVariable UUID id, @RequestBody AdminUserUpdateRequest r, Authentication a) {
    return AdminUserResponse.of(service.update((User) a.getPrincipal(), id, r.role(), r.enabled()));
  }

  @PostMapping("/invitations")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void invite(@RequestBody Map<String, String> r) {
    service.invite(r.get("email"));
  }

  @GetMapping("/invitations")
  public List<Invitation> invitations() {
    return service.invitations();
  }

  @PutMapping("/settings/password-minimum-length")
  public Map<String, Integer> minimum(@RequestBody PasswordMinimumRequest r, Authentication a) {
    return Map.of("value", service.setMinimum((User) a.getPrincipal(), r.value()));
  }

  @GetMapping("/settings/password-minimum-length/history")
  public List<PasswordSettingChange> history() {
    return service.history();
  }
}
