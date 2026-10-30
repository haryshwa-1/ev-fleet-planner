package com.moveinsync.evfleet.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.app.error.GlobalExceptionHandler;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT security. Role rules live here in one table; tenant isolation lives in the
 * services, which take the token's tenant on every lookup.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // no cookies, so no CSRF to defend against
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/shifts/*/plan").hasRole("ADMIN")
                        .requestMatchers("/shifts/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/me/**").hasRole("EMPLOYEE")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint((req, res, e) -> write(res, json, HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, e) -> write(res, json, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "Your role may not do this")));
        return http.build();
    }

    private static void write(HttpServletResponse res, ObjectMapper json, HttpStatus status, String code,
                              String detail) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(res.getOutputStream(), GlobalExceptionHandler.problem(status, code, detail));
    }

    private static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static SecretKey key(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
