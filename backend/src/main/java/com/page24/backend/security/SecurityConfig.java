package com.page24.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.page24.backend.exception.ApiErrorResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@Profile("!lambda")
@EnableMethodSecurity
public class SecurityConfig {
    private final ObjectMapper json = new ObjectMapper();

    @Bean
    JwtDecoder jwtDecoder(@Value("${careplan.security.issuer}") String issuer,
                          @Value("${careplan.security.jwk-set-uri}") String jwks,
                          @Value("${careplan.security.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                        && jwt.getExpiresAt() != null && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter carePlanJwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new CarePlanRolesConverter());
        return converter;
    }

    @Bean
    @Order(1)
    SecurityFilterChain metrics(HttpSecurity http, UserDetailsService metricsUsers) throws Exception {
        return http.securityMatcher("/actuator/prometheus")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .userDetailsService(metricsUsers)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("MONITOR"))
                .httpBasic(b -> b.authenticationEntryPoint((q, r, e) -> {
                    r.setHeader("WWW-Authenticate", "Basic realm=\"metrics\"");
                    error(r, 401, "AUTHENTICATION_REQUIRED", "Authentication required");
                })).build();
    }

    @Bean
    UserDetailsService metricsUsers(@Value("${careplan.security.metrics-user}") String user,
                                   @Value("${careplan.security.metrics-password}") String password) {
        return new InMemoryUserDetailsManager(User.withUsername(user)
                .password("{bcrypt}" + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode(password)).roles("MONITOR").build());
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/export", "/api/v1/export/**", "/api/v1/reports/**").hasRole("PHARMACIST")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/regenerate", "/api/v1/orders/*/careplan/upload").hasRole("PHARMACIST")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/patients/**", "/api/v1/providers/**").hasRole("PHARMACIST")
                        .requestMatchers("/api/v1/**").hasAnyRole("ASSISTANT", "PHARMACIST")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((q, r, ex) -> unauthorized(r))
                        .accessDeniedHandler((q, r, ex) -> error(r, 403, "ACCESS_DENIED", "Permission denied")))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((q, r, ex) -> unauthorized(r))
                        .accessDeniedHandler((q, r, ex) -> error(r, 403, "ACCESS_DENIED", "Permission denied")))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${careplan.security.allowed-origins}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setExposedHeaders(List.of("Content-Disposition"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        error(response, 401, "AUTHENTICATION_REQUIRED", "Valid access token required");
    }

    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), new ApiErrorResponse("error", code, message, null, status));
    }
}
