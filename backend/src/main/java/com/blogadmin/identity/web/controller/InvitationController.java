package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AdminUserService;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.web.dto.InvitationRedeemRequest;
import com.blogadmin.identity.web.dto.InvitationUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/invitations")
public class InvitationController {
  private final AdminUserService adminUserService;

  @PostMapping("/{token}/redeem")
  public InvitationUserResponse redeem(
      @PathVariable String token, @RequestBody InvitationRedeemRequest request) {
    User user;
    try {
      user =
          adminUserService.redeem(
              token, request.displayName(), request.password(), request.preferredLanguage());
    } catch (AdminUserService.InvalidInvitationException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found");
    } catch (AdminUserService.AlreadyExistsException exception) {
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
