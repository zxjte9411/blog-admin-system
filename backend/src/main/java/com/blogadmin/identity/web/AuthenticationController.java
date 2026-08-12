package com.blogadmin.identity.web;

import com.blogadmin.identity.application.AuthenticationService;
import com.blogadmin.identity.domain.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
  private final AuthenticationService service;
  private final JwtToken jwt;

  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  public record LoginResponse(String accessToken, Instant accessTokenExpiresAt) {}

  @PostMapping("/login")
  public LoginResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    var result = service.login(request.email(), request.password());
    cookie(response, result.refreshToken());
    var accessToken = jwt.create(result.user(), result.sessionId(), result.accessTokenVersion());
    return new LoginResponse(accessToken.value(), accessToken.expiresAt());
  }

  @PostMapping("/refresh")
  public LoginResponse refresh(
      @CookieValue(name = "refresh_token", required = false) String token,
      HttpServletResponse response) {
    if (token == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var result = service.refresh(token);
    cookie(response, result.refreshToken());
    var accessToken = jwt.create(result.user(), result.sessionId(), result.accessTokenVersion());
    return new LoginResponse(accessToken.value(), accessToken.expiresAt());
  }

  @GetMapping("/sessions")
  public List<SessionResponse> sessions(Authentication authentication) {
    var user = (User) authentication.getPrincipal();
    var current = (UUID) authentication.getDetails();
    return service.sessions(user).stream()
        .map(
            s ->
                new SessionResponse(
                    s.getId(), s.getId().equals(current), s.getCreatedAt(), s.getLastUsedAt()))
        .toList();
  }

  @DeleteMapping("/sessions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSession(@PathVariable UUID id, Authentication authentication) {
    service.revokeOther(
        (User) authentication.getPrincipal(), id, (UUID) authentication.getDetails());
  }

  public record SessionResponse(UUID id, boolean current, Instant createdAt, Instant lastUsedAt) {}

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @CookieValue(name = "refresh_token", required = false) String token,
      HttpServletResponse response) {
    if (token != null) service.logout(token);
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("refresh_token", "")
            .path("/api/v1/auth")
            .httpOnly(true)
            .maxAge(0)
            .build()
            .toString());
  }

  private void cookie(HttpServletResponse response, String token) {
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
