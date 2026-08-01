package com.trustguard.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Skeleton only, per the B-003 batch brief. Real API key authentication
 * (B-006) and admin JWT authentication (B-007) are NOT implemented here.
 * This exists solely so Docker's HEALTHCHECK and the Docker Compose
 * readiness probe (Rule 15.7) can reach /actuator/health/** without
 * Spring Security's default auto-configuration blocking every request
 * with a generated login page.
 *
 * CSRF is disabled because TrustGuard is a stateless API with no
 * browser session state — Rule 16.2 disables CORS entirely in V1, and
 * there is no cookie-based session to protect against forgery.
 * Everything other than the health endpoints requires authentication by
 * default; no other endpoints exist yet in this batch regardless.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}