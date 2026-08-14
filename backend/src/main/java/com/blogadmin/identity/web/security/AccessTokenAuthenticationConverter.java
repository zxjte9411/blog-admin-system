package com.blogadmin.identity.web.security;

import com.blogadmin.identity.domain.session.RefreshSession;
import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.User;
import com.blogadmin.identity.domain.user.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

@RequiredArgsConstructor
public final class AccessTokenAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {
  private final UserRepository userRepository;
  private final RefreshSessionRepository refreshSessionRepository;

  @Override
  public AbstractAuthenticationToken convert(Jwt token) {
    try {
      UUID userId = UUID.fromString(token.getSubject());
      UUID sessionId = UUID.fromString(token.getClaimAsString("sid"));
      Integer sessionVersion = numberClaim(token, "ver");
      Integer userVersion = numberClaim(token, "uver");
      RefreshSession session =
          refreshSessionRepository.findByIdAndRevokedAtIsNull(sessionId).orElse(null);
      User user = userRepository.findById(userId).orElse(null);
      if (session == null
          || user == null
          || !session.getUserId().equals(userId)
          || session.getAccessTokenVersion() != sessionVersion
          || !session.active()
          || user.getAccessTokenVersion() != userVersion
          || !user.isEnabled()
          || user.getVerifiedAt() == null) {
        throw invalidToken();
      }

      var authentication =
          new UsernamePasswordAuthenticationToken(
              user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
      authentication.setDetails(sessionId);
      return authentication;
    } catch (OAuth2AuthenticationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidToken();
    }
  }

  private static Integer numberClaim(Jwt token, String name) {
    Object value = token.getClaim(name);
    return value instanceof Number number ? number.intValue() : null;
  }

  private static OAuth2AuthenticationException invalidToken() {
    return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
  }
}
