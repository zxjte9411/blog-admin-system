package com.blogadmin.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class OpaqueToken {
  private static final SecureRandom RANDOM = new SecureRandom();

  private OpaqueToken() {}

  public static Issued generate() {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    return new Issued(value, digest(value));
  }

  public static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public record Issued(String value, byte[] digest) {}
}
