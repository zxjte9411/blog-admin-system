package com.blogadmin.identity.web.controller;

import com.blogadmin.identity.application.AuthenticationService;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.web.dto.GoogleLoginRequest;
import com.blogadmin.identity.web.dto.LoginRequest;
import com.blogadmin.identity.web.dto.LoginResponse;
import com.blogadmin.identity.web.dto.SessionResponse;
import com.blogadmin.identity.web.security.JwtToken;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
  private final AuthenticationService authenticationService;
  private final JwtToken jwtToken;

  @PostMapping("/login")
  public LoginResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    var result = authenticationService.login(request.email(), request.password());
    setRefreshTokenCookie(response, result.refreshToken());
    var accessToken =
        jwtToken.create(result.user(), result.sessionId(), result.accessTokenVersion());
    return new LoginResponse(accessToken.value(), accessToken.expiresAt());
  }

  @PostMapping("/google")
  public LoginResponse google(
      @Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
    var result =
        authenticationService.googleLogin(request.accessToken(), request.invitationToken());
    setRefreshTokenCookie(response, result.refreshToken());
    var accessToken =
        jwtToken.create(result.user(), result.sessionId(), result.accessTokenVersion());
    return new LoginResponse(accessToken.value(), accessToken.expiresAt());
  }

  @PostMapping("/refresh")
  public LoginResponse refresh(
      @CookieValue(name = "refresh_token", required = false) String token,
      HttpServletResponse response) {
    if (token == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    var result = authenticationService.refresh(token);
    setRefreshTokenCookie(response, result.refreshToken());
    var accessToken =
        jwtToken.create(result.user(), result.sessionId(), result.accessTokenVersion());
    return new LoginResponse(accessToken.value(), accessToken.expiresAt());
  }

  @GetMapping("/sessions")
  public List<SessionResponse> sessions(Authentication authentication) {
    User user = (User) authentication.getPrincipal();
    UUID currentSessionId = (UUID) authentication.getDetails();
    return authenticationService.sessions(user).stream()
        .map(
            session ->
                new SessionResponse(
                    session.getId(),
                    session.getId().equals(currentSessionId),
                    session.getCreatedAt(),
                    session.getLastUsedAt()))
        .toList();
  }

  @DeleteMapping("/sessions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSession(@PathVariable UUID id, Authentication authentication) {
    authenticationService.revokeOther(
        (User) authentication.getPrincipal(), id, (UUID) authentication.getDetails());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @CookieValue(name = "refresh_token", required = false) String token,
      HttpServletResponse response) {
    if (token != null) {
      authenticationService.logout(token);
    }
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("refresh_token", "")
            .path("/api/v1/auth")
            .httpOnly(true)
            .maxAge(0)
            .build()
            .toString());
  }

  private void setRefreshTokenCookie(HttpServletResponse response, String token) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("refresh_token", token)
            .path("/api/v1/auth")
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .maxAge(604800)
            .build()
            .toString());
  }
}
