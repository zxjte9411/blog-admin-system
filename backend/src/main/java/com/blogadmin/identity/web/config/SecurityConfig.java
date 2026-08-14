package com.blogadmin.identity.web.config;

import com.blogadmin.identity.domain.session.RefreshSessionRepository;
import com.blogadmin.identity.domain.user.UserRepository;
import com.blogadmin.identity.web.security.AccessTokenAuthenticationConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  private static final RequestMatcher PUBLIC_ROUTES = publicRoutes();

  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      UserRepository users,
      RefreshSessionRepository sessions,
      ObjectMapper objectMapper,
      JwtDecoder accessTokenDecoder,
      BearerTokenResolver bearerTokenResolver)
      throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(c -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
                a.requestMatchers(PUBLIC_ROUTES)
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .bearerTokenResolver(bearerTokenResolver)
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problem(response, objectMapper, 401, "Authentication required"))
                    .jwt(
                        jwtConfigurer ->
                            jwtConfigurer
                                .decoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(
                                    new AccessTokenAuthenticationConverter(users, sessions))))
        .build();
  }

  @Bean
  BearerTokenResolver bearerTokenResolver() {
    var delegate = new DefaultBearerTokenResolver();
    return request -> PUBLIC_ROUTES.matches(request) ? null : delegate.resolve(request);
  }

  private static RequestMatcher publicRoutes() {
    var matchers = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(
        request -> request.getDispatcherType() == DispatcherType.ERROR,
        matchers.matcher(HttpMethod.GET, "/actuator/health"),
        matchers.matcher(HttpMethod.GET, "/api/v1/public/**"),
        matchers.matcher("/swagger-ui/**"),
        matchers.matcher("/swagger-ui.html"),
        matchers.matcher("/v3/api-docs/**"),
        matchers.matcher(HttpMethod.OPTIONS, "/**"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/login"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/google"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/refresh"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/logout"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/registrations"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/email-verifications"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/email-verifications/resend"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/password-resets"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/password-resets/{token}"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/invitations/{token}/redeem"),
        matchers.matcher(HttpMethod.POST, "/api/v1/auth/email-changes/{token}"));
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    var c = new CorsConfiguration();
    c.setAllowedOrigins(List.of("*"));
    c.setAllowedMethods(List.of("GET", "OPTIONS"));
    c.setAllowedHeaders(List.of("*"));
    c.setAllowCredentials(false);
    var s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/api/v1/public/**", c);
    return s;
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
