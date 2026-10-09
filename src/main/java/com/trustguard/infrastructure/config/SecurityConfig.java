package com.trustguard.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.trustguard.sdk.filter.ApiKeyAuthFilter;
import com.trustguard.sdk.filter.SecurityEventLogger;
import com.trustguard.sdk.service.KeyHashVerificationService;
import com.trustguard.sdk.service.KeyLookupService;
import com.trustguard.sdk.service.KeyRevocationService;

import jakarta.servlet.DispatcherType;
import tools.jackson.databind.ObjectMapper;

/**
 * Chains 2 and 3 of the three-chain layout (Rule 16.7: admin and tenant security never share code).
 * Chain 1 (/api/admin/**) lives in AdminSecurityConfig (RULING 17).
 * <p>
 * Chain 2 (order 2) authenticates /api/v1/** with the API key filter. Chain 3 (order 3, last) matches
 * every other path, permits the container's ERROR dispatch plus exactly the liveness and readiness
 * probes used by the Dockerfile HEALTHCHECK (RULING 20 as amended, Rule 15.5), and denies everything
 * else, so an unmapped path can never be open.
 * <p>
 * CSRF is disabled because TrustGuard is a stateless API with no browser session state: Rule 16.2
 * disables CORS entirely in V1 and there is no cookie-based session to protect against forgery.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Tenant chain, scoped to /api/v1/**. The API key filter is created with new here and registered
     * only through HttpSecurity (never as a servlet filter bean).
     *
     * @param http                    HttpSecurity
     * @param hashVerificationService HMAC verification
     * @param revocationService       revocation checks
     * @param lookupService           key lookup
     * @param securityEventLogger     security event writer
     * @param objectMapper            Jackson 3 mapper
     * @return the tenant chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           KeyHashVerificationService hashVerificationService,
                                           KeyRevocationService revocationService,
                                           KeyLookupService lookupService,
                                           SecurityEventLogger securityEventLogger,
                                           ObjectMapper objectMapper) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(hashVerificationService, revocationService,
                lookupService, securityEventLogger, objectMapper);
        http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * Default chain: fail-closed for every path not matched by chains 1 and 2.
     *
     * @param http HttpSecurity
     * @return the default-deny chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultDenyFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}