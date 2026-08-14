package com.blogadmin.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokenTest {

  @Test
  void generateProducesUniqueNonEmptyTokensWithMatchingSha256Digest() {
    Set<String> values = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      OpaqueToken.Issued token = OpaqueToken.generate();
      assertThat(token.value()).isNotBlank();
      assertThat(token.digest()).hasSize(32); // SHA-256 is 32 bytes
      assertThat(token.digest()).isEqualTo(OpaqueToken.digest(token.value()));
      values.add(token.value());
    }
    assertThat(values).hasSize(100);
  }

  @Test
  void digestIsDeterministicForGivenInput() {
    byte[] digest1 = OpaqueToken.digest("test-token-value");
    byte[] digest2 = OpaqueToken.digest("test-token-value");
    assertThat(digest1).isEqualTo(digest2);
  }
}
