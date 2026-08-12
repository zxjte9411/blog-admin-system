package com.blogadmin.identity.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http.csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    new org.springframework.security.web.util.matcher.AntPathRequestMatcher(
                        "/api/v1/auth/registrations", "POST"),
                    new org.springframework.security.web.util.matcher.AntPathRequestMatcher(
                        "/api/v1/auth/email-verifications", "POST"),
                    new org.springframework.security.web.util.matcher.AntPathRequestMatcher(
                        "/api/v1/auth/email-verifications/resend", "POST")))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .build();
  }
}
