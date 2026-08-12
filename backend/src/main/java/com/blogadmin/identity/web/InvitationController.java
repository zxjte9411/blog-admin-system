package com.blogadmin.identity.web;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.User;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/invitations")
public class InvitationController {
  private final AdminUserService service;

  public record RedeemRequest(String displayName, String password, String preferredLanguage) {}

  @PostMapping("/{token}/redeem")
  public UserResponse redeem(@PathVariable String token, @RequestBody RedeemRequest request) {
    User user;
    try {
      user =
          service.redeem(
              token, request.displayName(), request.password(), request.preferredLanguage());
    } catch (AdminUserService.InvalidInvitationException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
    } catch (AdminUserService.AlreadyExistsException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
    }
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.getRole(),
        user.isEnabled(),
        user.getVerifiedAt());
  }

  public record UserResponse(
      UUID id,
      String email,
      String displayName,
      com.blogadmin.identity.domain.UserRole role,
      boolean enabled,
      java.time.Instant verifiedAt) {}
}
