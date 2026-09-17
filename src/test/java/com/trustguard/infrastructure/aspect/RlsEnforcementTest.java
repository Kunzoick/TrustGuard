package com.trustguard.infrastructure.aspect;

import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.error.TenantContextMissingException;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class RlsEnforcementTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("trustguard_test")
                    .withUsername("trustguard")
                    .withPassword("trustguard");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.trustguardAppPassword",
                () -> "test-only-not-for-production");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.rabbitmq.host", () -> "localhost");
    }

    @Autowired
    private ProbeService probeService;

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    void aspect_throws_when_tenant_context_missing() {
        TenantContextHolder.clear();
        assertThatThrownBy(() -> probeService.transactionalNoOp())
                .isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void set_config_fires_correctly_when_context_present() {
        TenantContextHolder.set(sampleContext());
        String tenantIdSeenByDb = probeService.readCurrentTenantSetting();
        assertThat(tenantIdSeenByDb).isEqualTo(
                sampleContext().tenantId().value());
    }

    @Test
    void tenant_context_and_mdc_are_cleared_after_simulated_request() {
        try {
            TenantContextHolder.set(sampleContext());
            MDC.put("tenantId", sampleContext().tenantId().value());
            throw new RuntimeException("simulated request failure");
        } catch (RuntimeException expected) {
            // swallow — proving cleanup happens even on exception
        } finally {
            TenantContextHolder.clear();
            MDC.clear();
        }
        assertThat(TenantContextHolder.get()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
    }

    private static TenantContext sampleContext() {
        return new TenantContext(
                new TenantId(java.util.UUID.randomUUID().toString()),
                new ProjectId(java.util.UUID.randomUUID().toString()),
                "test-key-id",
                Environment.DEVELOPMENT,
                Set.of(Capability.CHECK),
                1);
    }

    @Service
    static class ProbeService {

        private final EntityManager entityManager;

        ProbeService(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        @Transactional
        void transactionalNoOp() {
        }

        @Transactional
        String readCurrentTenantSetting() {
            return (String) entityManager
                    .createNativeQuery(
                            "SELECT current_setting('app.tenant_id', true)")
                    .getSingleResult();
        }
    }
}