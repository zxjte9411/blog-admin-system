package com.blogadmin.identity.web.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SupabaseJwtVerifier {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final String issuer;
  private final String audience;
  private final URI jwksUri;

  public SupabaseJwtVerifier(
      ObjectMapper objectMapper,
      @Value("${app.security.supabase.issuer}") String issuer,
      @Value("${app.security.supabase.audience:authenticated}") String audience,
      @Value("${app.security.supabase.jwks-url}") String jwksUrl) {
    this.objectMapper = objectMapper;
    this.issuer = issuer;
    this.audience = audience;
    this.jwksUri = URI.create(jwksUrl);
  }

  public Claims verify(String compact) {
    try {
      if (issuer.isBlank() || audience.isBlank()) throw new IllegalArgumentException();
      String[] parts = compact.split("\\.", -1);
      if (parts.length != 3) throw new IllegalArgumentException();
      JsonNode header = objectMapper.readTree(decode(parts[0]));
      if (!"RS256".equals(header.path("alg").asText()) || header.path("kid").isMissingNode())
        throw new IllegalArgumentException();
      PublicKey key = key(header.path("kid").asText());
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initVerify(key);
      signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!signature.verify(Base64.getUrlDecoder().decode(parts[2])))
        throw new IllegalArgumentException();

      JsonNode body = objectMapper.readTree(decode(parts[1]));
      if (!issuer.equals(body.path("iss").asText()) || !audienceMatches(body.path("aud")))
        throw new IllegalArgumentException();
      long now = Instant.now().getEpochSecond();
      if (!body.has("exp") || body.path("exp").asLong() <= now)
        throw new IllegalArgumentException();
      if (body.has("nbf") && body.path("nbf").asLong() > now) throw new IllegalArgumentException();
      String subject = body.path("sub").asText();
      String email = body.path("email").asText();
      if (subject.isBlank() || !validEmail(email) || !emailVerified(body))
        throw new IllegalArgumentException();
      if (!"google".equals(body.path("app_metadata").path("provider").asText()))
        throw new IllegalArgumentException();
      String displayName = body.path("user_metadata").path("name").asText(email);
      return new Claims(subject, email, displayName);
    } catch (Exception exception) {
      throw new InvalidTokenException();
    }
  }

  private PublicKey key(String kid) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(2)).GET().build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) throw new IllegalArgumentException();
    for (JsonNode jwk : objectMapper.readTree(response.body()).path("keys")) {
      if (kid.equals(jwk.path("kid").asText()) && "RSA".equals(jwk.path("kty").asText())) {
        var modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
        var exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
        return KeyFactory.getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(modulus, exponent));
      }
    }
    throw new IllegalArgumentException();
  }

  private boolean audienceMatches(JsonNode audienceClaim) {
    return audienceClaim.isTextual()
        ? audience.equals(audienceClaim.asText())
        : audienceClaim.isArray()
            && java.util.stream.StreamSupport.stream(audienceClaim.spliterator(), false)
                .anyMatch(a -> audience.equals(a.asText()));
  }

  private static boolean emailVerified(JsonNode body) {
    return body.path("email_verified").asBoolean(false)
        || body.path("user_metadata").path("email_verified").asBoolean(false);
  }

  private static boolean validEmail(String email) {
    return email.length() <= 320
        && email.indexOf('@') > 0
        && email.indexOf('@') == email.lastIndexOf('@')
        && email.indexOf('@') < email.length() - 1
        && email.chars().noneMatch(Character::isWhitespace);
  }

  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  public record Claims(String subject, String email, String displayName) {}

  public static class InvalidTokenException extends RuntimeException {}
}
