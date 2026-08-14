package com.blogadmin.identity.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blogadmin.test.SupabaseJwksTestFixture;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupabaseJwtVerifierTest {

  private static SupabaseJwtVerifier verifier;

  @BeforeAll
  static void setUp() {
    verifier =
        new SupabaseJwtVerifier(
            SupabaseJwksTestFixture.DEFAULT_ISSUER,
            SupabaseJwksTestFixture.DEFAULT_AUDIENCE,
            SupabaseJwksTestFixture.getJwksUrl());
  }

  @Nested
  @DisplayName("Email verified claim resolution")
  class EmailVerifiedClaimResolution {

    @Test
    void verifiesSuccessfullyWhenTopLevelEmailVerifiedIsTrue() {
      String token =
          createCustomToken(
              builder -> {
                builder.claim("email_verified", true);
                builder.claim("user_metadata", Map.of("name", "Valid User"));
              });

      SupabaseJwtVerifier.Claims claims = verifier.verify(token);
      assertThat(claims.email()).isEqualTo("user@example.com");
      assertThat(claims.displayName()).isEqualTo("Valid User");
    }

    @Test
    void verifiesSuccessfullyWhenTopLevelIsFalseButUserMetadataEmailVerifiedIsTrue() {
      String token =
          createCustomToken(
              builder -> {
                builder.claim("email_verified", false);
                builder.claim(
                    "user_metadata", Map.of("name", "Valid User", "email_verified", true));
              });

      SupabaseJwtVerifier.Claims claims = verifier.verify(token);
      assertThat(claims.email()).isEqualTo("user@example.com");
      assertThat(claims.displayName()).isEqualTo("Valid User");
    }

    @Test
    void rejectsWhenBothTopLevelAndUserMetadataEmailVerifiedAreFalseOrMissing() {
      String bothFalse =
          createCustomToken(
              builder -> {
                builder.claim("email_verified", false);
                builder.claim(
                    "user_metadata", Map.of("name", "Unverified", "email_verified", false));
              });
      assertThatThrownBy(() -> verifier.verify(bothFalse))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);

      String missingBoth =
          createCustomToken(
              builder -> {
                builder.claim("email_verified", null);
                builder.claim("user_metadata", Map.of("name", "Unverified"));
              });
      assertThatThrownBy(() -> verifier.verify(missingBoth))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);
    }
  }

  @Nested
  @DisplayName("Subject and email validation")
  class SubjectAndEmailValidation {

    @Test
    void rejectsNullOrBlankSubject() {
      String nullSubject = createCustomToken(builder -> builder.subject(null));
      assertThatThrownBy(() -> verifier.verify(nullSubject))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);

      String emptySubject = createCustomToken(builder -> builder.subject(""));
      assertThatThrownBy(() -> verifier.verify(emptySubject))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);

      String blankSubject = createCustomToken(builder -> builder.subject("   "));
      assertThatThrownBy(() -> verifier.verify(blankSubject))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);
    }

    @Test
    void rejectsNullEmail() {
      String nullEmail = createCustomToken(builder -> builder.claim("email", null));
      assertThatThrownBy(() -> verifier.verify(nullEmail))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "no-at-sign",
          "@starts-with-at.com",
          "ends-with-at@",
          "multiple@@at.com",
          "two@at@signs.com",
          "has whitespace@example.com",
          "has\twhitespace@example.com",
          "has\nwhitespace@example.com"
        })
    void rejectsInvalidEmailFormats(String invalidEmail) {
      String token = createCustomToken(builder -> builder.claim("email", invalidEmail));
      assertThatThrownBy(() -> verifier.verify(token))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);
    }

    @Test
    void rejectsEmailOver320Characters() {
      String localPart = "a".repeat(315);
      String longEmail = localPart + "@b.com"; // 321 characters
      assertThat(longEmail.length()).isGreaterThan(320);

      String token = createCustomToken(builder -> builder.claim("email", longEmail));
      assertThatThrownBy(() -> verifier.verify(token))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);
    }

    @Test
    void acceptsEmailExactly320Characters() {
      String localPart = "a".repeat(314);
      String validLongEmail = localPart + "@b.com"; // 320 characters
      assertThat(validLongEmail.length()).isEqualTo(320);

      String token = createCustomToken(builder -> builder.claim("email", validLongEmail));
      SupabaseJwtVerifier.Claims claims = verifier.verify(token);
      assertThat(claims.email()).isEqualTo(validLongEmail);
    }
  }

  @Nested
  @DisplayName("App and User Metadata handling")
  class MetadataHandling {

    @Test
    void rejectsMissingAppMetadataOrNonGoogleProvider() {
      String missingAppMetadata = createCustomToken(builder -> builder.claim("app_metadata", null));
      assertThatThrownBy(() -> verifier.verify(missingAppMetadata))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);

      String wrongProvider =
          createCustomToken(builder -> builder.claim("app_metadata", Map.of("provider", "github")));
      assertThatThrownBy(() -> verifier.verify(wrongProvider))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);

      String nullProvider = createCustomToken(builder -> builder.claim("app_metadata", Map.of()));
      assertThatThrownBy(() -> verifier.verify(nullProvider))
          .isInstanceOf(SupabaseJwtVerifier.InvalidTokenException.class);
    }

    @Test
    void fallsBackToEmptyDisplayNameWhenUserMetadataIsMissing() {
      String token = createCustomToken(builder -> builder.claim("user_metadata", null));
      SupabaseJwtVerifier.Claims claims = verifier.verify(token);
      assertThat(claims.displayName()).isEmpty();
    }

    @Test
    void fallsBackToEmptyDisplayNameWhenNameInUserMetadataIsNotString() {
      String integerName =
          createCustomToken(
              builder ->
                  builder.claim("user_metadata", Map.of("name", 12345, "email_verified", true)));
      SupabaseJwtVerifier.Claims claimsInteger = verifier.verify(integerName);
      assertThat(claimsInteger.displayName()).isEmpty();

      String booleanName =
          createCustomToken(
              builder ->
                  builder.claim("user_metadata", Map.of("name", true, "email_verified", true)));
      SupabaseJwtVerifier.Claims claimsBoolean = verifier.verify(booleanName);
      assertThat(claimsBoolean.displayName()).isEmpty();
    }

    @Test
    void extractsValidStringDisplayName() {
      String token =
          createCustomToken(
              builder ->
                  builder.claim(
                      "user_metadata", Map.of("name", "Alice Wonder", "email_verified", true)));
      SupabaseJwtVerifier.Claims claims = verifier.verify(token);
      assertThat(claims.displayName()).isEqualTo("Alice Wonder");
    }
  }

  private static String createCustomToken(
      java.util.function.Consumer<JWTClaimsSet.Builder> customizer) {
    long now = Instant.now().getEpochSecond();
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer(SupabaseJwksTestFixture.DEFAULT_ISSUER)
            .audience(SupabaseJwksTestFixture.DEFAULT_AUDIENCE)
            .subject(UUID.randomUUID().toString())
            .claim("email", "user@example.com")
            .claim("email_verified", true)
            .claim("app_metadata", Map.of("provider", "google"))
            .claim("user_metadata", Map.of("name", "Default User"))
            .notBeforeTime(Date.from(Instant.ofEpochSecond(now - 10)))
            .expirationTime(Date.from(Instant.ofEpochSecond(now + 300)));

    customizer.accept(builder);
    JWTClaimsSet claims = builder.build();

    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID("google-test")
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    try {
      token.sign(new ECDSASigner(SupabaseJwksTestFixture.PRIMARY_EC_KEY.toECPrivateKey()));
      return token.serialize();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
