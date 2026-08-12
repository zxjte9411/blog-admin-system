package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.User;
import com.blogadmin.identity.web.dto.InvitationRedeemRequest;
import com.blogadmin.identity.web.dto.InvitationUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/invitations")
public class InvitationController {
  private final AdminUserService service;

  @PostMapping("/{token}/redeem")
  public InvitationUserResponse redeem(
      @PathVariable String token, @RequestBody InvitationRedeemRequest request) {
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
    return new InvitationUserResponse(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.getRole(),
        user.isEnabled(),
        user.getVerifiedAt());
  }
}
