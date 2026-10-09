package com.trustguard.api.admin;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only beans: a controllable @Primary clock and authenticated admin probe endpoints, so token
 * validation can be tested without side effects (logout mutates token_version). The boom route throws
 * an unhandled exception to prove the ERROR dispatch surfaces as 500, not 403 (RULING 20 amended).
 */
@TestConfiguration
public class AdminTestSupportConfig {

    /**
     * Controllable clock that wins over the production clock bean.
     *
     * @return the mutable clock
     */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }

    @RestController
    static class AdminProbeController {

        @GetMapping("/api/admin/probe/ping")
        public Map<String, Object> ping() {
            return Map.of("pong", true);
        }

        @GetMapping("/api/admin/probe/boom")
        public Map<String, Object> boom() {
            throw new RuntimeException("forced failure for the error-dispatch test");
        }
    }
}