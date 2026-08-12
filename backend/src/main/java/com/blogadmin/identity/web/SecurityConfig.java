package com.blogadmin.identity.web;

import com.blogadmin.identity.domain.RefreshSessionRepository;
import com.blogadmin.identity.domain.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      UserRepository users,
      RefreshSessionRepository sessions,
      JwtToken jwt,
      ObjectMapper objectMapper)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new AccessTokenFilter(users, jwt, sessions), UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, exception) ->
                            problem(response, objectMapper, 401, "Authentication required"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problem(response, objectMapper, 403, "Access denied")))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/auth/password-resets/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/email-changes/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .build();
  }

  private static void problem(
      HttpServletResponse response, ObjectMapper objectMapper, int status, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    objectMapper.writeValue(
        response.getWriter(), new ProblemJson("about:blank", detail, status, detail));
  }

  private record ProblemJson(String type, String title, int status, String detail) {}
}
