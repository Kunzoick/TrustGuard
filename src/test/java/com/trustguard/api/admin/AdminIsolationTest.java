package com.trustguard.api.admin;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.trustguard.api.admin.auth.AdminJwtFilter;
import com.trustguard.sdk.filter.ApiKeyAuthFilter;

import jakarta.servlet.Filter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criteria 7, 8, 11, 12, 17, 21 and RULING 20. Test-only imports from sdk are fine: the ArchUnit rules
 * for api.admin analyse production classes only.
 */
class AdminIsolationTest extends AdminIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void admin_jwt_cannot_reach_tenant_routes() throws Exception {
        String token = loginForToken(seedAdmin());

        mockMvc.perform(get("/api/v1/probe/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));
    }

    @Test
    void tenant_api_key_cannot_reach_admin_routes() throws Exception {
        String keyId = HexFormat.of().formatHex(randomBytes(16));
        seedTenantKey(keyId);
        String apiKey = "sk_live_" + keyId + "_" + hmac(keyId);

        mockMvc.perform(get("/api/admin/probe/ping").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
    }

    @Test
    void unmapped_paths_and_non_probe_health_endpoints_are_denied() throws Exception {
        mockMvc.perform(get("/no-such-path")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
    }

    @Test
    void liveness_and_readiness_need_no_authorization_and_run_no_auth_filter() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")
                        .header("Origin", "https://evil.example")
                        .header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")
                        .header("Origin", "https://evil.example")
                        .header("Authorization", "Bearer garbage"))
                .andExpect(status().isOk());
    }

    @Test
    void neither_auth_filter_is_a_servlet_filter_bean() {
        for (Filter filter : context.getBeansOfType(Filter.class).values()) {
            assertTrue(!(filter instanceof AdminJwtFilter) && !(filter instanceof ApiKeyAuthFilter),
                    "auth filters must not be Filter beans: " + filter.getClass());
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String hmac(String keyId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(keyId.getBytes(StandardCharsets.UTF_8)));
    }

    private static void seedTenantKey(String keyId) {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        JDBC.update("INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)", tenantId, "Isolation Tenant",
                "GENERAL");
        JDBC.update("INSERT INTO projects (id, tenant_id, name, environment) VALUES (?, ?, ?, ?)", projectId,
                tenantId, "Isolation Project", "PRODUCTION");
        JDBC.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            Array capabilities = connection.createArrayOf("text", new String[] {"TRACK", "CHECK"});
            try (var ps = connection.prepareStatement("INSERT INTO api_keys (id, tenant_id, project_id, key_id, "
                    + "key_hash, environment, capabilities, key_version) VALUES (?, ?, ?, ?, ?, ?, ?, 1)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setObject(3, projectId);
                ps.setString(4, keyId);
                ps.setString(5, "unused-hash");
                ps.setString(6, "PRODUCTION");
                ps.setArray(7, capabilities);
                ps.executeUpdate();
            }
            return null;
        });
    }
}