package com.blogadmin.identity.web;

import com.blogadmin.identity.domain.RefreshSessionRepository;
import com.blogadmin.identity.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccessTokenFilter extends OncePerRequestFilter {
  private final UserRepository users;
  private final JwtToken jwt;
  private final RefreshSessionRepository sessions;

  public AccessTokenFilter(UserRepository users, JwtToken jwt, RefreshSessionRepository sessions) {
    this.users = users;
    this.jwt = jwt;
    this.sessions = sessions;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorizationHeader = request.getHeader("Authorization");
    if (authorizationHeader != null && authorizationHeader.startsWith("Bearer "))
      try {
        var claims = jwt.verify(authorizationHeader.substring(7));
        var session = sessions.findByIdAndRevokedAtIsNull(claims.sessionId()).orElse(null);
        var user = users.findById(UUID.fromString(claims.userId())).orElse(null);
        if (session != null
            && session.getUserId().toString().equals(claims.userId())
            && session.getAccessTokenVersion() == claims.accessTokenVersion()
            && session.active()
            && user != null
            && user.getAccessTokenVersion() == claims.userAccessTokenVersion()
            && user.isEnabled()
            && user.getVerifiedAt() != null) {
          var authentication =
              new UsernamePasswordAuthenticationToken(
                  user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
          authentication.setDetails(claims.sessionId());
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
      } catch (IllegalArgumentException ignored) {
      }
    chain.doFilter(request, response);
  }
}
