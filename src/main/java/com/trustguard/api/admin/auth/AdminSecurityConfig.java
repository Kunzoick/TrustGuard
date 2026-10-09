package com.trustguard.api.admin.auth;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.trustguard.api.admin.repository.AdminUserRepository;
import com.trustguard.shared.enums.ErrorCode;

import tools.jackson.databind.ObjectMapper;

/**
 * Chain 1 (order 1): the admin filter chain, owned by the api module (RULING 17). Shares no code with
 * the tenant chain. AdminJwtFilter is created with new here and never exposed as a Filter bean (N2).
 */
@Configuration
public class AdminSecurityConfig {
    private static final String ADMIN_PATTERN= "/api/admin/**";
    private static final int HTTP_UNAUTHORIZED= 401;
    /**
     * System cock for admin token and lockout timing; tests replace it with a @PrimaryBean
     * @return UTC system clock
     */
    @Bean
    public Clock adminClock() {
        return Clock.systemUTC();
    }
    /**
     * Builds the admin chain.
     *
     * @param http         HttpSecurity
     * @param jwtService   token verifier
     * @param repository   admin repository
     * @param clock        time source
     * @param objectMapper Jackson 3 mapper
     * @return the admin chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, AdminJwtService jwtService,
                                                AdminUserRepository repository,
                                                Clock clock, ObjectMapper objectMapper) throws Exception{
        AdminJwtFilter jwtFilter= new AdminJwtFilter(jwtService, repository, clock, objectMapper);
        http
                .securityMatcher(ADMIN_PATTERN).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AdminAuthConstants.LOGIN_PATH)
                        .permitAll().anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e)
                        -> AdminJwtFilter.writeError(response, objectMapper,
                                new AdminAuthenticationException(ErrorCode.ADMIN_AUTHENTICATION_FAILED,
                        HTTP_UNAUTHORIZED, "Authentication required."))))
                        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
                return http.build();
    }
}
