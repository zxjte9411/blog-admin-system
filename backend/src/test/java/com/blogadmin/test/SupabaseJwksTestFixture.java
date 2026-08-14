package com.blogadmin.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Reusable test fixture for Supabase / Google OAuth JWT cryptographic tokens and JWKS server. */
public final class SupabaseJwksTestFixture {

  public static final String DEFAULT_ISSUER = "https://example.supabase.co/auth/v1";
  public static final String DEFAULT_AUDIENCE = "authenticated";

  public static final ECKey PRIMARY_EC_KEY = generateEcKey("google-test");
  public static final ECKey ALTERNATE_EC_KEY = generateEcKey("alternate-google-test");
  public static final KeyPair RSA_KEY_PAIR = generateRsaKeyPair();
  public static final RSAKey RSA_PUBLIC_KEY = generateRsaPublicKey(RSA_KEY_PAIR, "rsa-test");

  private static final HttpServer JWKS_SERVER = startJwksServer();

  private SupabaseJwksTestFixture() {}

  public static void configureDynamicProperties(DynamicPropertyRegistry registry) {
    registry.add("app.security.supabase.issuer", () -> DEFAULT_ISSUER);
    registry.add("app.security.supabase.audience", () -> DEFAULT_AUDIENCE);
    registry.add("app.security.supabase.jwks-url", SupabaseJwksTestFixture::getJwksUrl);
  }

  public static String getJwksUrl() {
    return "http://localhost:" + JWKS_SERVER.getAddress().getPort();
  }

  public static String createValidGoogleToken(String subject, String email) {
    return createToken(
        subject, email, DEFAULT_ISSUER, DEFAULT_AUDIENCE, 300, -1, "google", true, "Google User");
  }

  public static String createValidGoogleToken(
      String subject, String email, String displayName, boolean emailVerified) {
    return createToken(
        subject,
        email,
        DEFAULT_ISSUER,
        DEFAULT_AUDIENCE,
        300,
        -1,
        "google",
        emailVerified,
        displayName);
  }

  public static String createToken(
      String subject,
      String email,
      String issuer,
      String audience,
      long expiresIn,
      long notBeforeOffset,
      String provider,
      boolean emailVerified,
      String displayName) {
    long now = Instant.now().getEpochSecond();
    JWTClaimsSet claims =
        buildClaims(
            subject,
            email,
            issuer,
            audience,
            expiresIn,
            notBeforeOffset,
            provider,
            emailVerified,
            displayName,
            now);
    try {
      return signToken(
          claims,
          JWSAlgorithm.ES256,
          new ECDSASigner(PRIMARY_EC_KEY.toECPrivateKey()),
          "google-test");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String createAlternateKeySignedToken(String subject, String email) {
    long now = Instant.now().getEpochSecond();
    JWTClaimsSet claims =
        buildClaims(
            subject,
            email,
            DEFAULT_ISSUER,
            DEFAULT_AUDIENCE,
            300,
            -1,
            "google",
            true,
            "Google User",
            now);
    try {
      return signToken(
          claims,
          JWSAlgorithm.ES256,
          new ECDSASigner(ALTERNATE_EC_KEY.toECPrivateKey()),
          "google-test");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String createRs256SignedToken(String subject, String email) {
    long now = Instant.now().getEpochSecond();
    JWTClaimsSet claims =
        buildClaims(
            subject,
            email,
            DEFAULT_ISSUER,
            DEFAULT_AUDIENCE,
            300,
            -1,
            "google",
            true,
            "Google User",
            now);
    return signToken(
        claims, JWSAlgorithm.RS256, new RSASSASigner(RSA_KEY_PAIR.getPrivate()), "rsa-test");
  }

  public static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JWTClaimsSet buildClaims(
      String subject,
      String email,
      String issuer,
      String audience,
      long expiresIn,
      long notBeforeOffset,
      String provider,
      boolean emailVerified,
      String displayName,
      long now) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .subject(subject)
        .claim("email", email)
        .claim("email_verified", emailVerified)
        .claim("app_metadata", Map.of("provider", provider))
        .claim("user_metadata", displayName == null ? Map.of() : Map.of("name", displayName))
        .notBeforeTime(Date.from(Instant.ofEpochSecond(now + notBeforeOffset)))
        .expirationTime(Date.from(Instant.ofEpochSecond(now + expiresIn)))
        .build();
  }

  private static String signToken(
      JWTClaimsSet claims, JWSAlgorithm algorithm, JWSSigner signer, String keyId) {
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(algorithm).keyID(keyId).type(JOSEObjectType.JWT).build(), claims);
    try {
      token.sign(signer);
      return token.serialize();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ECKey generateEcKey(String keyId) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair keyPair = generator.generateKeyPair();
      return new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
          .privateKey((ECPrivateKey) keyPair.getPrivate())
          .keyID(keyId)
          .algorithm(JWSAlgorithm.ES256)
          .keyUse(KeyUse.SIGNATURE)
          .build();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static KeyPair generateRsaKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static RSAKey generateRsaPublicKey(KeyPair keyPair, String keyId) {
    return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
        .keyID(keyId)
        .algorithm(JWSAlgorithm.RS256)
        .keyUse(KeyUse.SIGNATURE)
        .build();
  }

  private static HttpServer startJwksServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      byte[] body =
          new ObjectMapper()
              .writeValueAsBytes(
                  Map.of(
                      "keys",
                      List.of(
                          PRIMARY_EC_KEY.toPublicJWK().toJSONObject(),
                          RSA_PUBLIC_KEY.toJSONObject())));
      server.createContext(
          "/",
          exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      return server;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
