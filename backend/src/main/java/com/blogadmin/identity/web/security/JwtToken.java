package com.blogadmin.identity.web.security;

import com.blogadmin.identity.domain.user.User;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
public final class JwtToken {
  private final JwtEncoder encoder;

  public JwtToken(@Value("${app.security.jwt-secret}") String secret) {
    byte[] key = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (key.length < 32) throw new IllegalStateException("JWT secret must be at least 32 bytes");
    var jwk =
        new OctetSequenceKey.Builder(key).algorithm(com.nimbusds.jose.JWSAlgorithm.HS256).build();
    JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(jwk));
    encoder = new NimbusJwtEncoder(source);
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
