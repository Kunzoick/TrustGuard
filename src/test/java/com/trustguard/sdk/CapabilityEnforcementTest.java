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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class CapabilityEnforcementTest {

    private final KeyHashVerificationService hashService = Mockito.mock(KeyHashVerificationService.class);
    private final KeyRevocationService revocationService = Mockito.mock(KeyRevocationService.class);
    private final KeyLookupService lookupService = Mockito.mock(KeyLookupService.class);
    private final SecurityEventLogger securityEventLogger = Mockito.mock(SecurityEventLogger.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            hashService, revocationService, lookupService, securityEventLogger, objectMapper);

    private static final String VALID_KEY = "sk_live_" + "a".repeat(32) + "_" + "b".repeat(64);

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void track_only_key_calling_check_endpoint_returns_403() throws Exception {
        mockResolution(Set.of(Capability.TRACK));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        request.setRequestURI("/api/v1/risk/check");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, Mockito.mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void check_only_key_calling_track_endpoint_returns_403() throws Exception {
        mockResolution(Set.of(Capability.CHECK));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        request.setRequestURI("/api/v1/signals/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, Mockito.mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void track_key_calling_track_endpoint_succeeds() throws Exception {
        mockResolution(Set.of(Capability.TRACK));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        request.setRequestURI("/api/v1/signals/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
    }

    private void mockResolution(Set<Capability> capabilities) {
        when(hashService.verify(anyString(), anyString())).thenReturn(true);
        when(revocationService.isRevoked(anyString())).thenReturn(false);
        when(lookupService.resolve(anyString())).thenReturn(Optional.of(new TenantContext(
                new TenantId(UUID.randomUUID().toString()),
                new ProjectId(UUID.randomUUID().toString()),
                "a".repeat(32),
                Environment.DEVELOPMENT,
                capabilities,
                1)));
    }
}