package com.blogadmin.identity.web.security;

import com.blogadmin.identity.domain.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class JwtToken {
  private final byte[] key;
  private final ObjectMapper objectMapper;

  public JwtToken(@Value("${app.security.jwt-secret}") String secret, ObjectMapper objectMapper) {
    key = secret.getBytes(StandardCharsets.UTF_8);
    this.objectMapper = objectMapper;
    if (key.length < 32) throw new IllegalStateException("JWT secret must be at least 32 bytes");
  }

  public Token create(User user, UUID sessionId, int accessTokenVersion) {
    Instant expiresAt = Instant.now().plusSeconds(900);
    long exp = expiresAt.getEpochSecond();
    String header = enc(new Header("HS256", "JWT"));
    String payload =
        enc(
            new Payload(
                user.getId().toString(),
                sessionId,
                accessTokenVersion,
                user.getAccessTokenVersion(),
                exp));
    return new Token(
        header + "." + payload + "." + sign(header + "." + payload), Instant.ofEpochSecond(exp));
  }

  public Claims verify(String token) {
    try {
      String[] p = token.split("\\.");
      if (p.length != 3 || !MessageDigestCompat.constant(sign(p[0] + "." + p[1]), p[2]))
        throw new IllegalArgumentException();
      String body = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
      Payload payload = objectMapper.readValue(body, Payload.class);
      long exp = payload.exp();
      if (exp <= Instant.now().getEpochSecond()) throw new IllegalArgumentException();
      return new Claims(payload.sub(), payload.sid(), payload.ver(), payload.uver());
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (RuntimeException | JsonProcessingException exception) {
      throw new IllegalArgumentException("Malformed access token", exception);
    }
  }

  public record Token(String value, Instant expiresAt) {}

  private String enc(Object value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(objectMapper.writeValueAsBytes(value));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize JWT", exception);
    }
  }

  private String sign(String s) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record Claims(
      String userId, UUID sessionId, int accessTokenVersion, int userAccessTokenVersion) {}

  private record Header(String alg, String typ) {}

  private record Payload(String sub, UUID sid, int ver, int uver, long exp) {}

  private static final class MessageDigestCompat {
    static boolean constant(String a, String b) {
      return MessageDigest.isEqual(
          a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
  }
}
