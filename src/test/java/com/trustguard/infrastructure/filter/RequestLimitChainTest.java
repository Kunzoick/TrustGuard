package com.trustguard.infrastructure.filter;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Array;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.trustguard.sdk.ProbeControllerTestConfig;
import com.trustguard.sdk.filter.ApiKeyAuthFilter;

import jakarta.servlet.Filter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010a AC 7 and 9 on a real server: authentication wins over the Content-Type and size checks (Rule 5.7), the
 * checks apply to authenticated requests although no controller exists, the admin chain is untouched, and TRACE
 * and TRACK are not accepted. The filter order is read from the real FilterChainProxy.
 *
 * <p>A request that passes the filter is asserted only as "the filter did not answer": an unmapped POST may be
 * 404 or 405, and a later controller would make it 202. The EVENT_HMAC_KEY comes from the test
 * application.properties (Ruling 88).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(ProbeControllerTestConfig.class)
class RequestLimitChainTest {

    private static final String HMAC_SIGNING_KEY = "integration-test-hmac-signing-key";
    private static final String APP_PASSWORD = "test-only-not-for-production";
    private static final String ADMIN_JWT_SECRET = "dGVzdC1vbmx5LWFkbWluLWp3dC1zZWNyZXQtMzItYnl0ZXM=";

    /** Must equal trustguard.request-limits in application.yml. */
    private static final int SIGNALS_LIMIT = 65_536;
    private static final int DEFAULT_LIMIT = 16_384;

    private static final String SIGNALS_PATH = "/api/v1/signals/login";
    private static final String RISK_PATH = "/api/v1/risk/check";
    private static final String PROBE_PATH = "/api/v1/probe/ping";
    private static final String ADMIN_LOGIN_PATH = "/api/admin/auth/login";
    private static final String JSON = "application/json";

    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_413 = 413;
    private static final int HTTP_415 = 415;
    private static final int FIRST_CLIENT_ERROR = 400;
    private static final int LAST_CLIENT_ERROR = 499;
    private static final int ADMIN_OVERSIZE_BYTES = 70_000;
    private static final int KEY_ID_HEX_LENGTH = 32;

    /** Every status the authentication, capability and limit filters can answer with. */
    private static final Set<Integer> FILTER_ANSWERS =
            Set.of(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_413, HTTP_415);

    private static final Path TRACE_TRACK_REPORT = Path.of("target", "b010a-trace-track-report.txt");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("trustguard_test")
                    .withUsername("trustguard")
                    .withPassword("trustguard");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.trustguardAppPassword", () -> APP_PASSWORD);
        registry.add("spring.flyway.placeholders.trustguardAuthResolverPassword", () -> APP_PASSWORD);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("trustguard.auth-resolver.datasource.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("trustguard.auth-resolver.datasource.username", POSTGRES::getUsername);
        registry.add("trustguard.auth-resolver.datasource.password", POSTGRES::getPassword);
        registry.add("trustguard.auth-resolver.datasource.minimum-idle", () -> "2");
        registry.add("trustguard.auth-resolver.datasource.maximum-pool-size", () -> "5");
        registry.add("trustguard.auth-resolver.datasource.pool-name", () -> "test-request-limit-pool");

        registry.add("trustguard.security.hmac-signing-key", () -> HMAC_SIGNING_KEY);
        registry.add("trustguard.admin.jwt-secret", () -> ADMIN_JWT_SECRET);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private ApplicationContext applicationContext;

    private String fullAuthorization;
    private String trackOnlyAuthorization;

    @BeforeEach
    void seedKeys() {
        JdbcTemplate jdbc = superuserTemplate();
        fullAuthorization = seedKey(jdbc, "TRACK", "CHECK", "FEEDBACK");
        trackOnlyAuthorization = seedKey(jdbc, "TRACK");
    }

    // ---------- helpers ----------

    private HttpResponse<String> send(String method, String path, String contentType, BodyPublisher body,
                                      String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String contentType, String body, String authorization)
            throws Exception {
        return send("POST", path, contentType, BodyPublishers.ofString(body), authorization);
    }

    private static void assertFilterDidNotAnswer(HttpResponse<String> response) {
        assertThat(response.statusCode()).isNotIn(FILTER_ANSWERS);
    }

    private static String jsonOfExactly(int bytes) {
        String prefix = "{\"p\":\"";
        String suffix = "\"}";
        return prefix + "a".repeat(bytes - prefix.length() - suffix.length()) + suffix;
    }

    @SuppressWarnings("unchecked")
    private static String codeOf(HttpResponse<String> response) throws Exception {
        Map<String, Object> body = (Map<String, Object>) MAPPER.readValue(response.body(), Object.class);
        return (String) ((Map<String, Object>) body.get("error")).get("code");
    }

    private static JdbcTemplate superuserTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static String seedKey(JdbcTemplate jdbc, String... capabilities) {
        String keyId = randomHex(KEY_ID_HEX_LENGTH);
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)",
                tenantId, "Request Limit Test Tenant", "GENERAL");
        jdbc.update("INSERT INTO projects (id, tenant_id, name, environment) VALUES (?, ?, ?, ?)",
                projectId, tenantId, "Request Limit Test Project", "PRODUCTION");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            Array capabilitiesArray = connection.createArrayOf("text", capabilities);
            try (var ps = connection.prepareStatement(
                    "INSERT INTO api_keys (id, tenant_id, project_id, key_id, key_hash, "
                            + "environment, capabilities, key_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setObject(3, projectId);
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

    private List<Filter> chainFor(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        return filterChainProxy.getFilterChains().stream()
                .filter(chain -> chain.matches(request))
                .findFirst()
                .orElseThrow()
                .getFilters();
    }

    private static List<Class<?>> classesOf(List<Filter> filters) {
        List<Class<?>> classes = new ArrayList<>();
        for (Filter filter : filters) {
            classes.add(filter.getClass());
        }
        return classes;
    }

    // ---------- authentication order (Rule 5.7) ----------

    @Test
    @DisplayName("an unauthenticated oversize or wrong-Content-Type request is 401, not 413 or 415")
    void unauthenticatedRequestsAreAnsweredBeforeTheLimits() throws Exception {
        HttpResponse<String> oversize = post(SIGNALS_PATH, JSON, jsonOfExactly(SIGNALS_LIMIT + 1), null);
        HttpResponse<String> wrongType = post(SIGNALS_PATH, "text/plain", "x", null);
        HttpResponse<String> both = post(SIGNALS_PATH, "text/plain", jsonOfExactly(SIGNALS_LIMIT + 1), null);

        for (HttpResponse<String> response : List.of(oversize, wrongType, both)) {
            assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED);
            assertThat(codeOf(response)).isEqualTo("INVALID_API_KEY");
        }
    }

    @Test
    @DisplayName("a wrong capability is 403 before the size and Content-Type checks")
    void aWrongCapabilityWinsOverTheLimits() throws Exception {
        HttpResponse<String> oversize =
                post(RISK_PATH, JSON, jsonOfExactly(DEFAULT_LIMIT + 1), trackOnlyAuthorization);
        HttpResponse<String> wrongType = post(RISK_PATH, "text/plain", "x", trackOnlyAuthorization);

        for (HttpResponse<String> response : List.of(oversize, wrongType)) {
            assertThat(response.statusCode()).isEqualTo(HTTP_FORBIDDEN);
            assertThat(codeOf(response)).isEqualTo("CAPABILITY_INSUFFICIENT");
        }
    }

    // ---------- authenticated requests ----------

    @Test
    @DisplayName("an authenticated POST, PUT or PATCH with the wrong Content-Type is 415 although no controller exists")
    void authenticatedWrongContentTypeIs415() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH")) {
            for (String contentType : new String[] {"text/plain", "application/json; charset=ISO-8859-1", null}) {
                HttpResponse<String> response =
                        send(method, SIGNALS_PATH, contentType, BodyPublishers.ofString("{}"), fullAuthorization);

                assertThat(response.statusCode()).as(method + " " + contentType).isEqualTo(HTTP_415);
                assertThat(codeOf(response)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
            }
        }
    }

    @Test
    @DisplayName("on the signals path 65536 bytes pass the filter and 65537 are 413")
    void theSignalsLimitIsEnforced() throws Exception {
        HttpResponse<String> atLimit = post(SIGNALS_PATH, JSON, jsonOfExactly(SIGNALS_LIMIT), fullAuthorization);
        HttpResponse<String> over = post(SIGNALS_PATH, JSON, jsonOfExactly(SIGNALS_LIMIT + 1), fullAuthorization);

        assertFilterDidNotAnswer(atLimit);
        assertThat(over.statusCode()).isEqualTo(HTTP_413);
        assertThat(codeOf(over)).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("chunked requests without a Content-Length are limited the same way")
    void chunkedBodiesAreLimited() throws Exception {
        byte[] atLimit = jsonOfExactly(SIGNALS_LIMIT).getBytes(StandardCharsets.UTF_8);
        byte[] over = jsonOfExactly(SIGNALS_LIMIT + 1).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> passes = send("POST", SIGNALS_PATH, JSON,
                BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(atLimit))),
                fullAuthorization);
        HttpResponse<String> rejected = send("POST", SIGNALS_PATH, JSON,
                BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(over))),
                fullAuthorization);

        assertFilterDidNotAnswer(passes);
        assertThat(rejected.statusCode()).isEqualTo(HTTP_413);
    }

    @Test
    @DisplayName("outside the signals path the default limit of 16384 bytes applies")
    void theDefaultLimitIsEnforced() throws Exception {
        HttpResponse<String> atLimit = post(RISK_PATH, JSON, jsonOfExactly(DEFAULT_LIMIT), fullAuthorization);
        HttpResponse<String> over = post(RISK_PATH, JSON, jsonOfExactly(DEFAULT_LIMIT + 1), fullAuthorization);

        assertFilterDidNotAnswer(atLimit);
        assertThat(over.statusCode()).isEqualTo(HTTP_413);
        assertThat(codeOf(over)).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("requests without a body are not touched: GET with a wrong Content-Type still reaches the controller")
    void methodsWithoutABodyAreNotTouched() throws Exception {
        HttpResponse<String> get = send("GET", PROBE_PATH, "text/plain", BodyPublishers.noBody(), fullAuthorization);
        HttpResponse<String> delete =
                send("DELETE", PROBE_PATH, "text/plain", BodyPublishers.noBody(), fullAuthorization);

        assertThat(get.statusCode()).isEqualTo(HTTP_OK);
        assertThat(delete.statusCode()).isNotIn(HTTP_413, HTTP_415);
    }

    @Test
    @DisplayName("413 and 415 are UTF-8 JSON in the platform shape")
    void errorResponsesHaveTheRightContentType() throws Exception {
        HttpResponse<String> tooLarge = post(SIGNALS_PATH, JSON, jsonOfExactly(SIGNALS_LIMIT + 1), fullAuthorization);
        HttpResponse<String> unsupported = post(SIGNALS_PATH, "text/plain", "x", fullAuthorization);

        for (HttpResponse<String> response : List.of(tooLarge, unsupported)) {
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json;charset=UTF-8");
            assertThat(response.body()).contains("\"retryable\":false").doesNotContain("Bearer");
        }
    }

    // ---------- admin chain (AC 7) ----------

    @Test
    @DisplayName("the admin chain is unchanged: an oversize admin body is not answered with our 413")
    void theAdminChainIsUnchanged() throws Exception {
        String body = "{\"username\":\"nobody\",\"password\":\"" + "a".repeat(ADMIN_OVERSIZE_BYTES) + "\"}";

        HttpResponse<String> response = post(ADMIN_LOGIN_PATH, JSON, body, null);

        assertThat(response.statusCode()).isNotEqualTo(HTTP_413);
        assertThat(response.body()).doesNotContain("PAYLOAD_TOO_LARGE");
    }

    // ---------- placement (AC 7) ----------

    @Test
    @DisplayName("the request limit filter sits after the API key filter in the tenant chain only")
    void theFilterSitsAfterTheApiKeyFilterInChainTwoOnly() {
        List<Class<?>> tenantChain = classesOf(chainFor("POST", SIGNALS_PATH));
        int authIndex = tenantChain.indexOf(ApiKeyAuthFilter.class);
        int limitIndex = tenantChain.indexOf(RequestLimitFilter.class);

        assertThat(authIndex).isNotNegative();
        assertThat(limitIndex).isGreaterThan(authIndex);
        assertThat(tenantChain.stream().filter(RequestLimitFilter.class::equals)).hasSize(1);

        for (String uri : List.of(ADMIN_LOGIN_PATH, "/actuator/health/liveness", "/nowhere")) {
            assertThat(classesOf(chainFor("POST", uri))).as(uri).doesNotContain(RequestLimitFilter.class);
        }
        long chainsWithTheFilter = filterChainProxy.getFilterChains().stream()
                .map(SecurityFilterChain::getFilters)
                .filter(filters -> classesOf(filters).contains(RequestLimitFilter.class))
                .count();
        assertThat(chainsWithTheFilter).isEqualTo(1);
    }

    @Test
    @DisplayName("the request limit filter is not a bean")
    void theFilterIsNotABean() {
        assertThat(applicationContext.getBeanNamesForType(RequestLimitFilter.class)).isEmpty();
    }

    // ---------- TRACE and TRACK (AC 9) ----------

    @Test
    @DisplayName("TRACE and TRACK to a /api/v1 path are rejected; the observed statuses go to target/")
    void traceAndTrackAreNotAccepted() throws Exception {
        StringBuilder report = new StringBuilder();
        List<Integer> statuses = new ArrayList<>();

        for (String method : List.of("TRACE", "TRACK")) {
            for (boolean authenticated : List.of(false, true)) {
                HttpResponse<String> response = send(method, SIGNALS_PATH, null, BodyPublishers.noBody(),
                        authenticated ? fullAuthorization : null);
                statuses.add(response.statusCode());
                report.append(method).append(authenticated ? " authenticated" : " anonymous")
                        .append(" -> ").append(response.statusCode())
                        .append(" allow=").append(response.headers().firstValue("Allow").orElse("-"))
                        .append(" bodyChars=").append(response.body().length()).append('\n');
            }
        }
        Files.createDirectories(TRACE_TRACK_REPORT.getParent());
        Files.writeString(TRACE_TRACK_REPORT, report.toString());

        assertThat(statuses).allSatisfy(status -> assertThat(status).isBetween(FIRST_CLIENT_ERROR, LAST_CLIENT_ERROR));
    }
}