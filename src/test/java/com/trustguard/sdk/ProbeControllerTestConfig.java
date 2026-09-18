package com.trustguard.sdk;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test-only controller, imported exclusively by
 * ApiKeyAuthIntegrationTest via @Import. Never registered in
 * src/main/java. Exposes exactly one endpoint under a neutral
 * /api/v1/probe/ path — this path does not match any of the
 * TRACK/CHECK/FEEDBACK capability mappings in ApiKeyAuthFilter, so
 * it falls into the "authenticated, no specific capability
 * required" branch (CF-004/Adjustment 2). This deliberately
 * decouples "was the controller reached" from any particular
 * capability semantics, so a future change to the TRACK/CHECK/
 * FEEDBACK mapping cannot silently break this test for an unrelated
 * reason.
 */
@TestConfiguration
public class ProbeControllerTestConfig {

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe/ping")
        public Map<String, Object> ping() {
            return Map.of("pong", true);
        }
    }
}