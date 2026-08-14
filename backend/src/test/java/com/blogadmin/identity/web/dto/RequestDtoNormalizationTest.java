package com.blogadmin.identity.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.blogadmin.identity.domain.password.PasswordSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RequestDtoNormalizationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void registrationRequestDtoNormalizesFieldsIdenticallyBetweenJacksonAndBuilder()
      throws Exception {
    String json =
        """
        {
          "email": "  user@example.com  ",
          "displayName": "  Alice Smith  ",
          "password": "Password123!",
          "preferredLanguage": "zh-TW"
        }
        """;

    RegistrationRequestDTO fromJackson = objectMapper.readValue(json, RegistrationRequestDTO.class);

    RegistrationRequestDTO fromBuilder =
        RegistrationRequestDTO.builder()
            .email("  user@example.com  ")
            .displayName("  Alice Smith  ")
            .password("Password123!")
            .preferredLanguage("zh-TW")
            .build();

    assertThat(fromJackson.getEmail()).isEqualTo("user@example.com");
    assertThat(fromBuilder.getEmail()).isEqualTo("user@example.com");
    assertThat(fromBuilder.getEmail()).isEqualTo(fromJackson.getEmail());

    assertThat(fromJackson.getDisplayName()).isEqualTo("Alice Smith");
    assertThat(fromBuilder.getDisplayName()).isEqualTo("Alice Smith");
    assertThat(fromBuilder.getDisplayName()).isEqualTo(fromJackson.getDisplayName());

    assertThat(fromBuilder.getPassword()).isEqualTo("Password123!");
    assertThat(fromBuilder.getPreferredLanguage()).isEqualTo("zh-TW");
  }

  @Test
  void registrationRequestDtoHandlesNullsInBuilder() {
    RegistrationRequestDTO dto =
        RegistrationRequestDTO.builder()
            .email(null)
            .displayName(null)
            .password(null)
            .preferredLanguage(null)
            .build();

    assertThat(dto.getEmail()).isNull();
    assertThat(dto.getDisplayName()).isNull();
    assertThat(dto.getPassword()).isNull();
    assertThat(dto.getPreferredLanguage()).isNull();
  }

  @Test
  void resendEmailVerificationRequestDtoNormalizesEmailIdenticallyBetweenJacksonAndBuilder()
      throws Exception {
    String json =
        """
        {
          "email": "  user@example.com  "
        }
        """;

    ResendEmailVerificationRequestDTO fromJackson =
        objectMapper.readValue(json, ResendEmailVerificationRequestDTO.class);

    ResendEmailVerificationRequestDTO fromBuilder =
        ResendEmailVerificationRequestDTO.builder().email("  user@example.com  ").build();

    assertThat(fromJackson.getEmail()).isEqualTo("user@example.com");
    assertThat(fromBuilder.getEmail()).isEqualTo("user@example.com");
    assertThat(fromBuilder.getEmail()).isEqualTo(fromJackson.getEmail());
  }

  @Test
  void resendEmailVerificationRequestDtoHandlesNullInBuilder() {
    ResendEmailVerificationRequestDTO dto =
        ResendEmailVerificationRequestDTO.builder().email(null).build();

    assertThat(dto.getEmail()).isNull();
  }

  @Test
  void emailVerificationRequestDtoConstructsIdenticallyBetweenJacksonAndBuilder() throws Exception {
    String json =
        """
        {
          "token": "verification-token-123"
        }
        """;

    EmailVerificationRequestDTO fromJackson =
        objectMapper.readValue(json, EmailVerificationRequestDTO.class);

    EmailVerificationRequestDTO fromBuilder =
        EmailVerificationRequestDTO.builder().token("verification-token-123").build();

    assertThat(fromJackson.getToken()).isEqualTo("verification-token-123");
    assertThat(fromBuilder.getToken()).isEqualTo("verification-token-123");
    assertThat(fromBuilder.getToken()).isEqualTo(fromJackson.getToken());
  }

  @Test
  void passwordSettingDoesNotExposePublicSetId() {
    boolean hasSetId =
        Arrays.stream(PasswordSetting.class.getMethods())
            .map(Method::getName)
            .anyMatch(name -> name.equals("setId"));

    assertThat(hasSetId).isFalse();
  }
}
