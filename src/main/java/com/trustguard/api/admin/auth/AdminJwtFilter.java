package com.trustguard.api.admin.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.trustguard.api.admin.repository.AdminUser;
import com.trustguard.api.admin.repository.AdminUserRepository;
import com.trustguard.shared.enums.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin JWT filter (N6). NOT a Spring bean (N2): instantiated with new in AdminSecurityConfig so it
 * is registered only through HttpSecurity. Never touches TenantContextHolder. The admin row is loaded
 * on every request with no caching, so revocation has no window.
 */
public class AdminJwtFilter extends OncePerRequestFilter {

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final String MDC_ADMIN_ID = "adminId";

    private final AdminJwtService jwtService;
    private final AdminUserRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param jwtService   token verifier
     * @param repository   admin repository
     * @param clock        time source
     * @param objectMapper Jackson 3 mapper
     */
    public AdminJwtFilter(AdminJwtService jwtService, AdminUserRepository repository, Clock clock,
                          ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return AdminAuthConstants.LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UsernamePasswordAuthenticationToken authentication;
        try {
            authentication = authenticate(request);
        } catch (AdminAuthenticationException e) {
            writeError(response, objectMapper, e);
            return;
        }
        try {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            MDC.put(MDC_ADMIN_ID, String.valueOf(authentication.getPrincipal()));
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            MDC.remove(MDC_ADMIN_ID);
        }
    }

    private UsernamePasswordAuthenticationToken authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(AdminAuthConstants.BEARER_PREFIX)) {
            throw new AdminAuthenticationException(ErrorCode.ADMIN_AUTHENTICATION_FAILED, HTTP_UNAUTHORIZED,
                    "Missing or malformed Authorization header.");
        }
        AdminJwtService.AdminTokenClaims claims = jwtService.verify(
                header.substring(AdminAuthConstants.BEARER_PREFIX.length()));
        Instant now = clock.instant();
        Instant sessionEnd = claims.issuedAt().plus(AdminAuthConstants.MAX_SESSION_AGE);
        if (!now.isBefore(sessionEnd)) {
            throw sessionExpired();
        }
        AdminUser admin = repository.findById(claims.adminId()).orElseThrow(() ->
                new AdminAuthenticationException(ErrorCode.ADMIN_AUTHENTICATION_FAILED, HTTP_UNAUTHORIZED,
                        "Invalid or missing admin token."));
        if (admin.isLocked() || admin.getTokenVersion() != claims.tokenVersion()) {
            throw sessionExpired();
        }
        return new UsernamePasswordAuthenticationToken(admin.getId().toString(), null,
                List.of(new SimpleGrantedAuthority(AdminAuthConstants.ROLE_ADMIN)));
    }

    private static AdminAuthenticationException sessionExpired() {
        return new AdminAuthenticationException(ErrorCode.ADMIN_SESSION_EXPIRED, HTTP_UNAUTHORIZED,
                "Admin session has expired.");
    }

    /**
     * Writes an admin error response directly (also used by the chain's entry point).
     *
     * @param response     servlet response
     * @param objectMapper Jackson 3 mapper
     * @param e            the failure to render
     * @throws IOException if the body cannot be written
     */
    static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                           AdminAuthenticationException e) throws IOException {
        response.setStatus(e.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(e.toResponseBody()));
    }
}