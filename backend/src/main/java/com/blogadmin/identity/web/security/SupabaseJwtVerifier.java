package com.blogadmin.identity.web.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Component
public class SupabaseJwtVerifier {
  private final JwtDecoder decoder;

  public SupabaseJwtVerifier(
      @Value("${app.security.supabase.issuer}") String issuer,
      @Value("${app.security.supabase.audience:authenticated}") String audience,
      @Value("${app.security.supabase.jwks-url}") String jwksUrl) {
    NimbusJwtDecoder jwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwksUrl).jwsAlgorithm(SignatureAlgorithm.ES256).build();
    jwtDecoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtIssuerValidator(issuer),
            new JwtTimestampValidator(Duration.ZERO),
            new JwtAudienceValidator(audience),
            new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
    this.decoder = jwtDecoder;
  }

  public Claims verify(String compact) {
    try {
      Jwt jwt = decoder.decode(compact);
      String subject = jwt.getSubject();
      String email = jwt.getClaimAsString("email");
      Map<String, Object> appMetadata = jwt.getClaimAsMap("app_metadata");
      Map<String, Object> userMetadata = jwt.getClaimAsMap("user_metadata");
      boolean emailVerified =
          Boolean.TRUE.equals(jwt.getClaim("email_verified"))
              || (userMetadata != null && Boolean.TRUE.equals(userMetadata.get("email_verified")));
      if (subject == null
          || subject.isBlank()
          || email == null
          || !validEmail(email)
          || !emailVerified
          || appMetadata == null
          || !"google".equals(appMetadata.get("provider"))) {
        throw new IllegalArgumentException();
      }
      String displayName =
          userMetadata == null ? "" : userMetadata.get("name") instanceof String name ? name : "";
      return new Claims(subject, email, displayName);
    } catch (Exception exception) {
      throw new InvalidTokenException();
    }
  }

  private static boolean validEmail(String email) {
    return email.length() <= 320
        && email.indexOf('@') > 0
        && email.indexOf('@') == email.lastIndexOf('@')
        && email.indexOf('@') < email.length() - 1
        && email.chars().noneMatch(Character::isWhitespace);
  }

  public record Claims(String subject, String email, String displayName) {}

  public static class InvalidTokenException extends RuntimeException {}
}
