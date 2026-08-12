package com.blogadmin.identity.web;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.*;
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

  public record UserResponse(
      UUID id,
      String email,
      String displayName,
      UserRole role,
      boolean enabled,
      java.time.Instant verifiedAt) {
    static UserResponse of(User u) {
      return new UserResponse(
          u.getId(),
          u.getEmail(),
          u.getDisplayName(),
          u.getRole(),
          u.isEnabled(),
          u.getVerifiedAt());
    }
  }

  public record UpdateRequest(UserRole role, Boolean enabled) {}

  public record MinimumRequest(int value) {}

  @GetMapping("/users")
  public List<UserResponse> users(
      @RequestParam(required = false) UserRole role,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String q) {
    return service.list(role, enabled, q).stream().map(UserResponse::of).toList();
  }

  @PatchMapping("/users/{id}")
  public UserResponse update(
      @PathVariable UUID id, @RequestBody UpdateRequest r, Authentication a) {
    return UserResponse.of(service.update((User) a.getPrincipal(), id, r.role(), r.enabled()));
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
  public Map<String, Integer> minimum(@RequestBody MinimumRequest r, Authentication a) {
    return Map.of("value", service.setMinimum((User) a.getPrincipal(), r.value()));
  }

  @GetMapping("/settings/password-minimum-length/history")
  public List<PasswordSettingChange> history() {
    return service.history();
  }
}
