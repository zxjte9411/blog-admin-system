package com.blogadmin.identity.web;

import com.blogadmin.identity.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.stereotype.Component;

@Component
final class JwtToken {
  private final byte[] key;

  JwtToken(@Value("${app.security.jwt-secret}") String secret) {
    key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) throw new IllegalStateException("JWT secret must be at least 32 bytes");
  }

  Token create(User user, UUID sessionId, int accessTokenVersion) {
    Instant expiresAt = Instant.now().plusSeconds(900);
    long exp = expiresAt.getEpochSecond();
    String header = enc("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"),
        payload =
            enc(
                "{\"sub\":\""
                    + user.getId()
                    + "\",\"sid\":\""
                    + sessionId
                    + "\",\"ver\":"
                    + accessTokenVersion
                    + ",\"uver\":"
                    + user.getAccessTokenVersion()
                    + ",\"exp\":"
                    + exp
                    + "}");
    return new Token(
        header + "." + payload + "." + sign(header + "." + payload), Instant.ofEpochSecond(exp));
  }

  Claims verify(String token) {
    try {
      String[] p = token.split("\\.");
      if (p.length != 3 || !MessageDigestCompat.constant(sign(p[0] + "." + p[1]), p[2]))
        throw new IllegalArgumentException();
      String body = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
      var json = new BasicJsonParser().parseMap(body);
      Object subValue = json.get("sub");
      Object expValue = json.get("exp");
      Object sessionValue = json.get("sid");
      Object versionValue = json.get("ver");
      Object userVersionValue = json.get("uver");
      if (!(subValue instanceof String)
          || !(sessionValue instanceof String)
          || !(versionValue instanceof Number)
          || !(userVersionValue instanceof Number)
          || !(expValue instanceof Number)) throw new IllegalArgumentException();
      String sub = (String) subValue;
      UUID sessionId = UUID.fromString((String) sessionValue);
      int version = ((Number) versionValue).intValue();
      int userVersion = ((Number) userVersionValue).intValue();
      long exp = ((Number) expValue).longValue();
      if (exp <= Instant.now().getEpochSecond()) throw new IllegalArgumentException();
      return new Claims(sub, sessionId, version, userVersion);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Malformed access token", exception);
    }
  }

  record Token(String value, Instant expiresAt) {}

  private static String enc(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
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

  record Claims(
      String userId, UUID sessionId, int accessTokenVersion, int userAccessTokenVersion) {}

  private static final class MessageDigestCompat {
    static boolean constant(String a, String b) {
      return MessageDigest.isEqual(
          a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
  }
}
