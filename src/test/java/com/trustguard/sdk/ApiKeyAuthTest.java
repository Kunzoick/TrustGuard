package com.trustguard.sdk;
import tools.jackson.databind.ObjectMapper;
import com.trustguard.sdk.filter.ApiKeyAuthFilter;
import com.trustguard.sdk.filter.SecurityEventLogger;
import com.trustguard.sdk.service.KeyHashVerificationService;
import com.trustguard.sdk.service.KeyLookupService;
import com.trustguard.sdk.service.KeyRevocationService;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
class ApiKeyAuthTest {
    private final KeyHashVerificationService hashService= Mockito.mock(KeyHashVerificationService.class);
    private final KeyRevocationService revocationService= Mockito.mock(KeyRevocationService.class);
    private final KeyLookupService lookupService= Mockito.mock(KeyLookupService.class);
    private final SecurityEventLogger securityEventLogger= Mockito.mock(SecurityEventLogger.class);
    private final ObjectMapper objectMapper= new ObjectMapper();
    private final ApiKeyAuthFilter filter= new ApiKeyAuthFilter(hashService, revocationService, lookupService, securityEventLogger, objectMapper);
    private static final String VALID_KEY= "sk_live_" + "a".repeat(32) + "_" + "b".repeat(64);

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        MDC.clear();
    }
    @Test
    void origin_header_present_returns_403_before_any_key_processing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(securityEventLogger).logBrowserOriginRejected(request);
        verifyNoInteractions(hashService, revocationService, lookupService);
        verifyNoInteractions(chain);
    }

    @Test
    void malformed_key_returns_401_before_any_lookup_or_hmac() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-valid-key-format");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(hashService, revocationService, lookupService);
    }

    @Test
    void invalid_hmac_returns_401_and_never_reaches_revocation_check() throws Exception {
        when(hashService.verify(anyString(), anyString())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(revocationService, lookupService);
    }

    @Test
    void revoked_key_returns_401_and_never_reaches_tenant_resolution() throws Exception {
        when(hashService.verify(anyString(), anyString())).thenReturn(true);
        when(revocationService.isRevoked(anyString())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(lookupService);
    }

    @Test
    void x_tenant_id_header_is_ignored_tenant_comes_from_resolved_key() throws Exception {
        TenantContext resolvedContext = sampleContext();
        when(hashService.verify(anyString(), anyString())).thenReturn(true);
        when(revocationService.isRevoked(anyString())).thenReturn(false);
        when(lookupService.resolve(anyString())).thenReturn(Optional.of(resolvedContext));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        request.addHeader("X-Tenant-Id", UUID.randomUUID().toString()); // must be ignored
        request.setRequestURI("/api/v1/other");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) ->
                assertThat(TenantContextHolder.get().tenantId()).isEqualTo(resolvedContext.tenantId());

        filter.doFilter(request, response, chain);
    }

    @Test
    void context_and_mdc_cleared_after_successful_request() throws Exception {
        when(hashService.verify(anyString(), anyString())).thenReturn(true);
        when(revocationService.isRevoked(anyString())).thenReturn(false);
        when(lookupService.resolve(anyString())).thenReturn(Optional.of(sampleContext()));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        request.setRequestURI("/api/v1/other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertThat(TenantContextHolder.get()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void context_and_mdc_cleared_after_failed_request() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-valid-key-format");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(TenantContextHolder.get()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }
    @Test
    void context_mdc_and_security_context_cleared_when_chain_throws_after_context_installed() {
        when(hashService.verify(anyString(), anyString())).thenReturn(true);
        when(revocationService.isRevoked(anyString())).thenReturn(false);
        when(lookupService.resolve(anyString())).thenReturn(Optional.of(
                new TenantContext(
                        new TenantId(UUID.randomUUID().toString()),
                        new ProjectId(UUID.randomUUID().toString()),
                        "a".repeat(32),
                        Environment.PRODUCTION,
                        Set.of(Capability.CHECK),
                        1)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer sk_live_" + "a".repeat(32) + "_" + "b".repeat(64));
        request.setRequestURI("/api/v1/risk/check");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            // Assert context IS populated at this point, before we
            // throw — proves the finally block, not an earlier
            // short-circuit, is what's being tested.
            assertThat(TenantContextHolder.get()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            throw new RuntimeException("simulated downstream failure");
        };

        // doFilterInternal declares checked exceptions; the thrown
        // RuntimeException propagates out of chain.doFilter() and is
        // NOT caught by the filter's own catch block (which only
        // catches ApiKeyAuthenticationException) — it must propagate
        // to the caller after finally runs, per the brief's "aspect
        // must NOT catch exceptions from the join point" spirit
        // applied here to the filter's chain.doFilter() call.
        assertThatCode(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated downstream failure");

        assertThat(TenantContextHolder.get()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static TenantContext sampleContext() {
        return new TenantContext(
                new TenantId(UUID.randomUUID().toString()),
                new ProjectId(UUID.randomUUID().toString()),
                "a".repeat(32),
                Environment.DEVELOPMENT,
                Set.of(Capability.CHECK),
                1);
    }
}
