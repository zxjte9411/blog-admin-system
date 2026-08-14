package com.blogadmin.identity.web.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class AccessTokenSecurityConfig {
  public static final MacAlgorithm MAC_ALGORITHM = MacAlgorithm.HS256;
  public static final JWSAlgorithm JWS_ALGORITHM = JWSAlgorithm.HS256;

  private final SecretKey secretKey;

  public AccessTokenSecurityConfig(@Value("${app.security.jwt-secret}") String secret) {
    byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length < 32) {
      throw new IllegalStateException("JWT secret must be at least 32 bytes");
    }
    this.secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
  }

  @Bean
  public JwtEncoder accessTokenEncoder() {
    var jwk = new OctetSequenceKey.Builder(secretKey).algorithm(JWS_ALGORITHM).build();
    JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(jwk));
    return new NimbusJwtEncoder(source);
  }

  @Bean
  public JwtDecoder accessTokenDecoder() {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MAC_ALGORITHM).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
    return decoder;
  }
}
