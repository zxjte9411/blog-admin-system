package com.blogadmin.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.application.OpaqueToken;
import com.blogadmin.identity.domain.invitation.Invitation;
import com.blogadmin.identity.domain.invitation.InvitationRepository;
import com.blogadmin.test.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class InvitationRedemptionContextApiIntegrationTest extends AbstractPostgresIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private InvitationRepository invitationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanDatabase() {
    resetDatabase(jdbcTemplate);
  }

  @Test
  void exposesPublicContextStatusWithoutConsumingInvitationOrLeakingPersistenceFields() {
    Instant expiresAt = Instant.now().plusSeconds(3600);
    InvitationLink valid = createInvitation("Invited@Example.com", expiresAt);

    ResponseEntity<Map> validResponse = getContext(valid.token());
    Instant persistedExpiresAt =
        invitationRepository.findById(valid.invitation().getId()).orElseThrow().getExpiresAt();

    assertThat(validResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(validResponse.getBody())
        .containsEntry("status", "valid")
        .containsEntry("email", "invited@example.com")
        .containsEntry("expiresAt", persistedExpiresAt.toString())
        .doesNotContainKeys("tokenHash", "id", "usedAt");
    assertThat(invitationRepository.findById(valid.invitation().getId()).orElseThrow().getUsedAt())
        .isNull();

    Invitation expired =
        invitationRepository.save(
            new Invitation(
                UUID.randomUUID(),
                "expired@example.com",
                OpaqueToken.digest("expired-token"),
                Instant.now().minusSeconds(1)));
    Invitation used =
        new Invitation(
            UUID.randomUUID(),
            "used@example.com",
            OpaqueToken.digest("used-token"),
            Instant.now().plusSeconds(3600));
    used.use(Instant.now());
    invitationRepository.saveAndFlush(used);

    assertThat(getContext("expired-token").getBody())
        .containsEntry("status", "expired")
        .doesNotContainKeys("email", "expiresAt");
    assertThat(getContext("used-token").getBody())
        .containsEntry("status", "alreadyUsed")
        .doesNotContainKeys("email", "expiresAt");
    assertThat(getContext("invalid-token").getBody())
        .containsEntry("status", "invalid")
        .doesNotContainKeys("email", "expiresAt");
    assertThat(invitationRepository.findById(expired.getId()).orElseThrow().getUsedAt()).isNull();
  }

  private InvitationLink createInvitation(String email, Instant expiresAt) {
    OpaqueToken.Issued token = OpaqueToken.generate();
    Invitation invitation =
        invitationRepository.saveAndFlush(
            new Invitation(
                UUID.randomUUID(),
                email.trim().toLowerCase(Locale.ROOT),
                token.digest(),
                expiresAt));
    return new InvitationLink(invitation, token.value());
  }

  private ResponseEntity<Map> getContext(String token) {
    return restTemplate.getForEntity(
        url("/api/v1/auth/invitations/" + token + "/context"), Map.class);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private record InvitationLink(Invitation invitation, String token) {}
}
