package com.trustguard.sdk;

import tools.jackson.databind.ObjectMapper;
import com.trustguard.sdk.filter.ApiKeyAuthFilter;
import com.trustguard.sdk.filter.SecurityEventLogger;
import com.trustguard.sdk.service.KeyHashVerificationService;
import com.trustguard.sdk.service.KeyLookupService;
import com.trustguard.sdk.service.KeyRevocationService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CF-008 — parseKey() must reject malformed keyId/HMAC field
 * content, not just segment count, before any lookup or HMAC
 * computation occurs (AC2).
 *
 * Uses @MethodSource rather than @ValueSource because the test
 * fixtures are built with String.repeat(), which is a method call
 * evaluated at runtime — not a compile-time constant expression.
 * @ValueSource requires its array to be resolvable at compile time,
 * so it cannot accept these values directly.
 */
class ApiKeyParsingTest {

    private final KeyHashVerificationService hashService =
            Mockito.mock(KeyHashVerificationService.class);
    private final KeyRevocationService revocationService =
            Mockito.mock(KeyRevocationService.class);
    private final KeyLookupService lookupService = Mockito.mock(KeyLookupService.class);
    private final SecurityEventLogger securityEventLogger =
            Mockito.mock(SecurityEventLogger.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
            hashService, revocationService, lookupService, securityEventLogger, objectMapper);

    static Stream<String> malformedKeyProvider() {
        return Stream.of(
                // wrong keyId length (31 chars)
                "sk_live_" + "a".repeat(31) + "_" + "b".repeat(64),
                // wrong keyId length (33 chars)
                "sk_live_" + "a".repeat(33) + "_" + "b".repeat(64),
                // non-hex keyId (contains 'g')
                "sk_live_" + "g".repeat(32) + "_" + "b".repeat(64),
                // uppercase keyId — rejected, since real keys are always
                // lowercase per HexFormat.of().formatHex() output
                "sk_live_" + "A".repeat(32) + "_" + "b".repeat(64),
                // wrong HMAC length (63 chars)
                "sk_live_" + "a".repeat(32) + "_" + "b".repeat(63),
                // wrong HMAC length (65 chars)
                "sk_live_" + "a".repeat(32) + "_" + "b".repeat(65),
                // non-hex HMAC (contains 'z')
                "sk_live_" + "a".repeat(32) + "_" + "z".repeat(64),
                // wrong prefix
                "sk_prod_" + "a".repeat(32) + "_" + "b".repeat(64),
                // missing sk_ prefix entirely
                "live_" + "a".repeat(32) + "_" + "b".repeat(64),
                // extra underscore segment
                "sk_live_extra_" + "a".repeat(32) + "_" + "b".repeat(64),
                // missing underscore (all one segment)
                "sklivea".repeat(10)
        );
    }

    @ParameterizedTest
    @MethodSource("malformedKeyProvider")
    void malformed_key_variants_return_401_before_any_downstream_call(String malformedKey) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + malformedKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(hashService, revocationService, lookupService, chain);
    }

    @Test
    void well_formed_key_passes_parsing_and_reaches_hmac_verification() throws Exception {
        Mockito.when(hashService.verify(Mockito.anyString(), Mockito.anyString())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization",
                "Bearer sk_live_" + "a".repeat(32) + "_" + "b".repeat(64));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Mockito.verify(hashService).verify("a".repeat(32), "b".repeat(64));
    }
}