package com.blogadmin.identity.web;

import com.blogadmin.identity.domain.RefreshSessionRepository;
import com.blogadmin.identity.domain.UserRepository;
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
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http, UserRepository users, RefreshSessionRepository sessions, JwtToken jwt)
      throws Exception {
    return http.csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    new AntPathRequestMatcher("/api/v1/auth/registrations", "POST"),
                    new AntPathRequestMatcher("/api/v1/auth/email-verifications", "POST"),
                    new AntPathRequestMatcher("/api/v1/auth/email-verifications/resend", "POST"),
                    new AntPathRequestMatcher("/api/v1/auth/login", "POST"),
                    new AntPathRequestMatcher("/api/v1/auth/refresh", "POST"),
                    new AntPathRequestMatcher("/api/v1/auth/logout", "POST"),
                    new AntPathRequestMatcher("/api/v1/auth/sessions/*", "DELETE")))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new AccessTokenFilter(users, jwt, sessions), UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, exception) ->
                            problem(response, 401, "Authentication required"))
                    .accessDeniedHandler(
                        (request, response, exception) -> problem(response, 403, "Access denied")))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .build();
  }

  private static void problem(HttpServletResponse response, int status, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\""
                + detail
                + "\",\"status\":"
                + status
                + ",\"detail\":\""
                + detail
                + "\"}");
  }
}
