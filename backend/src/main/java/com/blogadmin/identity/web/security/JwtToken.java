package com.blogadmin.identity.web.security;

import com.blogadmin.identity.domain.user.User;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class JwtToken {
  private final JwtEncoder jwtEncoder;

  public Token create(User user, UUID sessionId, int accessTokenVersion) {
    Instant expiresAt = Instant.ofEpochSecond(Instant.now().plusSeconds(900).getEpochSecond());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(user.getId().toString())
            .claim("sid", sessionId.toString())
            .claim("ver", accessTokenVersion)
            .claim("uver", user.getAccessTokenVersion())
            .expiresAt(expiresAt)
            .build();
    JwsHeader header = JwsHeader.with(AccessTokenSecurityConfig.MAC_ALGORITHM).type("JWT").build();
    String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new Token(value, expiresAt);
  }

  public record Token(String value, Instant expiresAt) {}
}
