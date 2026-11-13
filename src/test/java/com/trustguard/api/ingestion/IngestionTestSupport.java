package com.trustguard.api.ingestion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;

import tools.jackson.databind.json.JsonMapper;

/**
 * Shared helpers of the B-010d HTTP tests: key seeding as the superuser, a valid login body, the POST, the response
 * parsing and the row counts of one tenant. The signing key is the one of the integration tests.
 */
public final class IngestionTestSupport {

    /** The endpoint under test. */
    public static final String LOGIN_PATH = "/api/v1/signals/login";
    /** A plain JSON content type. */
    public static final String JSON = "application/json";
    /** The content type every ingestion response must have. */
    public static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private static final String HMAC_SIGNING_KEY = "integration-test-hmac-signing-key";
    private static final int KEY_ID_HEX_LENGTH = 32;
    private static final long EVENT_AGE_SECONDS = 60;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private IngestionTestSupport() {
    }

    /**
     * Seeds an API key for a tenant and project and returns its Authorization header value.
     *
     * @param superuser    the superuser template
     * @param tenantId     the tenant
     * @param projectId    the project
     * @param capabilities the capabilities of the key
     * @return "Bearer sk_live_..." for a PRODUCTION key
     */
    public static String authorization(JdbcTemplate superuser, TenantId tenantId, ProjectId projectId,
                                       String... capabilities) {
        String keyId = randomHex(KEY_ID_HEX_LENGTH);
        superuser.execute((ConnectionCallback<Void>) connection -> {
            Array capabilitiesArray = connection.createArrayOf("text", capabilities);
            try (var ps = connection.prepareStatement(
                    "INSERT INTO api_keys (id, tenant_id, project_id, key_id, key_hash, "
                            + "environment, capabilities, key_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.fromString(tenantId.value()));
                ps.setObject(3, UUID.fromString(projectId.value()));
                ps.setString(4, keyId);
                ps.setString(5, "unused-hash-hmac-is-recomputed-not-read-back");
                ps.setString(6, "PRODUCTION");
                ps.setArray(7, capabilitiesArray);
                ps.executeUpdate();
            }
            return null;
        });
        return "Bearer sk_live_" + keyId + "_" + computeRealHmac(keyId);
    }

    /**
     * A valid LOGIN body for a PRODUCTION key: a USER actor, an event one minute old and a small payload.
     *
     * @return a new mutable map; tests change single fields
     */
    public static Map<String, Object> validBody() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", "v");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorType", "USER");
        body.put("actorId", "user-42");
        body.put("occurredAt", Instant.now().minusSeconds(EVENT_AGE_SECONDS).toString());
        body.put("sdkVersion", "1.0.0");
        body.put("environment", "PRODUCTION");
        body.put("ipAddress", "203.0.113.7");
        body.put("payload", payload);
        return body;
    }

    /**
     * Renders a body as JSON text.
     *
     * @param body the body
     * @return JSON text
     */
    public static String json(Map<String, Object> body) {
        return MAPPER.writeValueAsString(body);
    }

    /**
     * Posts raw text to the login endpoint.
     *
     * @param port          the server port
     * @param contentType   the Content-Type, or null for none
     * @param body          the body text
     * @param authorization the Authorization header value, or null for none
     * @return the response
     * @throws Exception if the request fails
     */
    public static HttpResponse<String> post(int port, String contentType, String body, String authorization)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + LOGIN_PATH))
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Posts a body as application/json.
     *
     * @param port          the server port
     * @param body          the body
     * @param authorization the Authorization header value
     * @return the response
     * @throws Exception if the request fails
     */
    public static HttpResponse<String> postJson(int port, Map<String, Object> body, String authorization)
            throws Exception {
        return post(port, JSON, json(body), authorization);
    }

    /**
     * Parses a JSON response body into a map.
     *
     * @param response the response
     * @return the body as a map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(HttpResponse<String> response) {
        return (Map<String, Object>) MAPPER.readValue(response.body(), Object.class);
    }

    /**
     * The error object of an error response.
     *
     * @param response the response
     * @return the map under "error"
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> error(HttpResponse<String> response) {
        return (Map<String, Object>) parse(response).get("error");
    }

    /**
     * The error code of an error response.
     *
     * @param response the response
     * @return the code
     */
    public static String errorCode(HttpResponse<String> response) {
        return (String) error(response).get("code");
    }

    /**
     * Counts a tenant's trust_events rows, as the superuser.
     *
     * @param superuser the superuser template
     * @param tenantId  the tenant
     * @return the row count
     */
    public static int eventRows(JdbcTemplate superuser, TenantId tenantId) {
        return count(superuser, "trust_events", tenantId);
    }

    /**
     * Counts a tenant's outbox_events rows, as the superuser.
     *
     * @param superuser the superuser template
     * @param tenantId  the tenant
     * @return the row count
     */
    public static int outboxRows(JdbcTemplate superuser, TenantId tenantId) {
        return count(superuser, "outbox_events", tenantId);
    }

    /**
     * Counts a tenant's idempotency_keys rows, as the superuser.
     *
     * @param superuser the superuser template
     * @param tenantId  the tenant
     * @return the row count
     */
    public static int claimRows(JdbcTemplate superuser, TenantId tenantId) {
        return count(superuser, "idempotency_keys", tenantId);
    }

    private static int count(JdbcTemplate superuser, String table, TenantId tenantId) {
        return superuser.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?::uuid",
                Integer.class, tenantId.value());
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String computeRealHmac(String keyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(keyId.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}