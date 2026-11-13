package com.trustguard.api.admin;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import com.trustguard.api.admin.auth.AdminJwtFilter;
import com.trustguard.api.admin.auth.AdminRequestLimitFilter;
import com.trustguard.infrastructure.filter.RequestLimitFilter;

import jakarta.servlet.Filter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-007c AC 2, 4, 6 and 7. The chain order is read from the real FilterChainProxy; the requests go over a real port
 * with the JDK HttpClient, so the container's own handling is part of what is tested. The admin limit filter runs
 * before authentication, so the requests without a token get 413 and 415 (accepted, Ruling 90). The HTTP transport is
 * HTTP/1.1; HTTP/2 is not enabled and has no test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminRequestLimitChainTest extends AdminIntegrationTestBase {

    /** Must equal trustguard.request-limits.admin-bytes in application.yml. */
    private static final int ADMIN_LIMIT = 32_768;

    private static final String LOGIN_PATH = "/api/admin/auth/login";
    private static final String LOGOUT_PATH = "/api/admin/auth/logout";
    private static final String PING_PATH = "/api/admin/probe/ping";
    private static final String TENANT_SIGNALS_PATH = "/api/v1/signals/login";
    private static final String JSON = "application/json";
    private static final String ERROR_CONTENT_TYPE = "application/json;charset=UTF-8";

    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_413 = 413;
    private static final int HTTP_415 = 415;
    private static final int FIRST_SUCCESS = 200;
    private static final int LAST_SUCCESS = 299;
    private static final int BODY_EXCERPT_CHARS = 80;
    private static final Set<Integer> LIMIT_ANSWERS = Set.of(HTTP_413, HTTP_415);

    private static final Path TRACE_TRACK_REPORT = Path.of("target", "b007c-trace-track-report.txt");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FilterChainProxy filterChainProxy;

    // ---------- helpers ----------

    private HttpResponse<String> send(String method, String path, String contentType, BodyPublisher body,
                                      String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return send("POST", path, contentType, BodyPublishers.ofString(body), null);
    }

    private HttpResponse<String> postChunked(String path, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return send("POST", path, contentType,
                BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)), null);
    }

    private static String jsonOfExactly(int bytes) {
        String prefix = "{\"p\":\"";
        String suffix = "\"}";
        return prefix + "a".repeat(bytes - prefix.length() - suffix.length()) + suffix;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(HttpResponse<String> response) throws Exception {
        Map<String, Object> body = (Map<String, Object>) MAPPER.readValue(response.body(), Object.class);
        assertThat(body).containsOnlyKeys("error");
        return (Map<String, Object>) body.get("error");
    }

    private static void assertLimitError(HttpResponse<String> response, int status, String code) throws Exception {
        Map<String, Object> error = errorOf(response);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains(ERROR_CONTENT_TYPE);
        assertThat(error).containsOnlyKeys("code", "message", "retryable");
        assertThat(error.get("code")).isEqualTo(code);
        assertThat(error.get("retryable")).isEqualTo(Boolean.FALSE);
    }

    private static void assertNotAnsweredByTheLimitFilter(HttpResponse<String> response) {
        assertThat(response.statusCode()).isNotIn(LIMIT_ANSWERS);
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

    private long chainsContaining(Class<?> filterType) {
        return filterChainProxy.getFilterChains().stream()
                .map(SecurityFilterChain::getFilters)
                .filter(filters -> classesOf(filters).contains(filterType))
                .count();
    }

    private static String excerpt(String body) {
        String flat = body.replace('\n', ' ').replace('\r', ' ');
        return flat.substring(0, Math.min(BODY_EXCERPT_CHARS, flat.length()));
    }

    // ---------- placement (AC 2 and 4) ----------

    @Test
    @DisplayName("in the admin chain the limit filter runs before the JWT filter, and no tenant filter is there")
    void theLimitFilterRunsBeforeAuthenticationInTheAdminChainOnly() {
        List<Class<?>> adminChain = classesOf(chainFor("POST", LOGIN_PATH));
        int limitIndex = adminChain.indexOf(AdminRequestLimitFilter.class);
        int jwtIndex = adminChain.indexOf(AdminJwtFilter.class);

        assertThat(limitIndex).isNotNegative();
        assertThat(jwtIndex).isNotNegative();
        assertThat(limitIndex).isLessThan(jwtIndex);
        assertThat(adminChain.stream().filter(AdminRequestLimitFilter.class::equals)).hasSize(1);
        assertThat(adminChain).doesNotContain(RequestLimitFilter.class);

        List<Class<?>> tenantChain = classesOf(chainFor("POST", TENANT_SIGNALS_PATH));
        assertThat(tenantChain).contains(RequestLimitFilter.class).doesNotContain(AdminRequestLimitFilter.class);

        assertThat(chainsContaining(AdminRequestLimitFilter.class)).isEqualTo(1);
        assertThat(chainsContaining(RequestLimitFilter.class)).isEqualTo(1);
    }

    // ---------- real HTTP without a token (AC 2) ----------

    @Test
    @DisplayName("an oversize body without any token is 413 PAYLOAD_TOO_LARGE in the admin error shape")
    void anOversizeBodyIsRejectedBeforeAuthentication() throws Exception {
        assertLimitError(post(LOGIN_PATH, JSON, jsonOfExactly(ADMIN_LIMIT + 1)), HTTP_413, "PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("a body of exactly the limit is not answered by the limit filter")
    void aBodyAtTheLimitPassesTheFilter() throws Exception {
        assertNotAnsweredByTheLimitFilter(post(LOGIN_PATH, JSON, jsonOfExactly(ADMIN_LIMIT)));
    }

    @Test
    @DisplayName("a wrong-type body without any token is 415 UNSUPPORTED_MEDIA_TYPE")
    void aWrongTypeIsRejectedBeforeAuthentication() throws Exception {
        assertLimitError(post(LOGIN_PATH, "text/plain", "x"), HTTP_415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("a chunked body with the wrong type is 415, and a chunked body over the limit is 413")
    void chunkedBodiesAreChecked() throws Exception {
        assertLimitError(postChunked(LOGIN_PATH, "text/plain", "{}"), HTTP_415, "UNSUPPORTED_MEDIA_TYPE");
        assertLimitError(postChunked(LOGIN_PATH, JSON, jsonOfExactly(ADMIN_LIMIT + 1)), HTTP_413,
                "PAYLOAD_TOO_LARGE");
    }

    // ---------- Ruling 129 (AC 7) ----------

    @Test
    @DisplayName("bodiless POSTs to logout and unlock, with no Content-Type, are 401 from authentication, not 415")
    void bodilessPostsAreNotAnsweredWith415() throws Exception {
        for (String path : List.of(LOGOUT_PATH, "/api/admin/admins/" + UUID.randomUUID() + "/unlock")) {
            HttpResponse<String> response = send("POST", path, null, BodyPublishers.noBody(), null);

            assertThat(response.statusCode()).as(path).isEqualTo(HTTP_UNAUTHORIZED);
            assertThat(errorOf(response).get("code")).as(path).isEqualTo("ADMIN_AUTHENTICATION_FAILED");
        }
    }

    @Test
    @DisplayName("a POST with Content-Length 0 and a text/plain type passes the type check")
    void aZeroLengthBodyPassesTheTypeCheck() throws Exception {
        HttpResponse<String> response = post(LOGOUT_PATH, "text/plain", "");

        assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED);
        assertThat(errorOf(response).get("code")).isEqualTo("ADMIN_AUTHENTICATION_FAILED");
    }

    @Test
    @DisplayName("an admin can log in over HTTP with JSON, then ping and log out with that one token and no body")
    void theNormalAdminFlowStillWorks() throws Exception {
        String username = seedAdmin();
        String loginBody = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";

        HttpResponse<String> login = post(LOGIN_PATH, JSON, loginBody);

        assertThat(login.statusCode()).isEqualTo(HTTP_OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> loginResult = (Map<String, Object>) MAPPER.readValue(login.body(), Object.class);
        String token = (String) loginResult.get("token");
        assertThat(token).isNotBlank();

        HttpResponse<String> ping = send("GET", PING_PATH, "text/plain", BodyPublishers.noBody(), token);
        HttpResponse<String> logout = send("POST", LOGOUT_PATH, null, BodyPublishers.noBody(), token);

        assertThat(ping.statusCode()).isEqualTo(HTTP_OK);
        assertThat(logout.statusCode()).isEqualTo(HTTP_OK);
    }

    // ---------- TRACE and TRACK (AC 6) ----------

    @Test
    @DisplayName("a default StrictHttpFirewall rejects TRACE and TRACK")
    void theDefaultFirewallRejectsTraceAndTrack() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();

        for (String method : List.of("TRACE", "TRACK")) {
            assertThatThrownBy(() -> firewall.getFirewalledRequest(new MockHttpServletRequest(method, PING_PATH)))
                    .as(method + " through a default StrictHttpFirewall")
                    .isInstanceOf(RequestRejectedException.class);
        }
    }

    @Test
    @DisplayName("TRACE and TRACK to an admin path are not answered 2xx; the observed evidence goes to target/")
    void traceAndTrackAreNotAccepted() throws Exception {
        String token = loginForToken(seedAdmin());
        StringBuilder report = new StringBuilder();
        List<Integer> statuses = new ArrayList<>();

        for (String method : List.of("TRACE", "TRACK")) {
            for (boolean authenticated : List.of(false, true)) {
                HttpResponse<String> response = send(method, PING_PATH, null, BodyPublishers.noBody(),
                        authenticated ? token : null);
                statuses.add(response.statusCode());
                report.append("HTTP ").append(method).append(authenticated ? " authenticated" : " anonymous")
                        .append(" -> status=").append(response.statusCode())
                        .append(" allow=").append(response.headers().firstValue("Allow").orElse("-"))
                        .append(" body=[").append(excerpt(response.body())).append("]\n");
            }
            report.append("UNIT default StrictHttpFirewall ").append(method).append(" -> ")
                    .append(firewallOutcome(method)).append('\n');
        }
        Files.createDirectories(TRACE_TRACK_REPORT.getParent());
        Files.writeString(TRACE_TRACK_REPORT, report.toString());

        assertThat(statuses).allSatisfy(status -> assertThat(status >= FIRST_SUCCESS &&
                status <= LAST_SUCCESS).as("status "+ status+ " must not be 2xx").isFalse());
    }

    private static String firewallOutcome(String method) {
        try {
            new StrictHttpFirewall().getFirewalledRequest(new MockHttpServletRequest(method, PING_PATH));
            return "did not throw";
        } catch (RequestRejectedException ex) {
            return "threw " + ex.getClass().getSimpleName();
        }
    }
}