package com.blogadmin.identity.web.security;

import com.blogadmin.identity.domain.user.User;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public final class JwtToken {
  private final JwtEncoder encoder;

  public JwtToken(JwtEncoder encoder) {
    this.encoder = encoder;
  }

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
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
    String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new Token(value, expiresAt);
  }

  public record Token(String value, Instant expiresAt) {}
}
