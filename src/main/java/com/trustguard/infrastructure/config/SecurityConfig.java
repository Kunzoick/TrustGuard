package com.trustguard.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import com.trustguard.sdk.filter.ApiKeyAuthFilter;
import com.trustguard.sdk.filter.SecurityEventLogger;
import com.trustguard.sdk.service.KeyHashVerificationService;
import com.trustguard.sdk.service.KeyLookupService;
import com.trustguard.sdk.service.KeyRevocationService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


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
    public SecurityFilterChain filterChain(HttpSecurity http, KeyHashVerificationService hashVerificationService,
                                           KeyRevocationService revocationService, KeyLookupService lookupService,
                                           SecurityEventLogger securityEventLogger, ObjectMapper objectMapper) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter= new ApiKeyAuthFilter(hashVerificationService, revocationService, lookupService, securityEventLogger, objectMapper);
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                        "/actuator/health/**").permitAll().requestMatchers("/api/v1/**",
                        "/api/admin/**").authenticated().anyRequest().permitAll());
        return http.build();
    }
    /**
     * Registered directly at HIGHEST_PRECEDENCE + 1, ahead of spring security's own filter chain internals including
     * UsernamePasswordAuthenticationFilter. this is a FilterRegistrationBean at the servlet container level, not a spring filter added
     * via HttpSecurity.addFilterBefore.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            KeyHashVerificationService hashVerificationService,
            KeyRevocationService revocationService,
            KeyLookupService lookupService,
            SecurityEventLogger securityEventLogger,
            ObjectMapper objectMapper){
        FilterRegistrationBean<ApiKeyAuthFilter> registration= new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(hashVerificationService, revocationService, lookupService, securityEventLogger, objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/api/v1/*\", \"/api/admin/*");
        return registration;
    }
}