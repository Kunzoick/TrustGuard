package com.trustguard.api.admin.auth;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rules 16.3 and 16.4 on the admin chain, Rulings 128, 129 and 130. A declared body (Content-Length above 0 or
 * Transfer-Encoding) with a wrong type is 415 before it is read; an undeclared body is read up to the bound and is
 * 415 if bytes arrived with a wrong type, checked before 413; the size bound applies to every POST, PUT and PATCH.
 * There is no real-HTTP test of HTTP/2: HTTP/2 is not enabled.
 */
class AdminRequestLimitFilterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final int LIMIT = 10;
    private static final int FAR_OVER_FACTOR = 100;
    private static final long LYING_LARGE_LENGTH = 1_000_000L;
    private static final long ABSENT_LENGTH = -1L;

    private static final String LOGIN_PATH = "/api/admin/auth/login";
    private static final String LOGOUT_PATH = "/api/admin/auth/logout";
    private static final String JSON = "application/json";
    private static final String TEXT = "text/plain";
    private static final String ERROR_CONTENT_TYPE = "application/json;charset=UTF-8";
    private static final String MESSAGE_415 = "Content-Type must be application/json with UTF-8.";
    private static final String MESSAGE_413 = "Request body exceeds the maximum allowed size.";
    private static final String TRANSFER_ENCODING = "Transfer-Encoding";
    private static final String CHUNKED = "chunked";

    private static final int HTTP_OK = 200;
    private static final int HTTP_413 = 413;
    private static final int HTTP_415 = 415;

    private record Result(MockHttpServletResponse response, ServletRequest forwarded) {
    }

    /** A request with a controllable body stream, declared length and raw Content-Type. */
    private static final class TestRequest extends MockHttpServletRequest {

        private final String rawContentType;
        private final InputStream body;
        private final long declaredLength;

        TestRequest(String method, String uri, String rawContentType, InputStream body, long declaredLength) {
            super(method, uri);
            this.rawContentType = rawContentType;
            this.body = body;
            this.declaredLength = declaredLength;
        }

        @Override
        public String getContentType() {
            return rawContentType;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new DelegatingServletInputStream(body);
        }

        @Override
        public int getContentLength() {
            return (int) declaredLength;
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }
    }

    /** Fails the test as soon as anything reads from it. */
    private static final class UnreadableBody extends InputStream {

        @Override
        public int read() {
            throw new AssertionError("The request body must not be read here");
        }
    }

    /** Never ends and counts what it hands out. */
    private static final class EndlessBody extends InputStream {

        private final AtomicLong delivered = new AtomicLong();

        @Override
        public int read() {
            delivered.incrementAndGet();
            return 'a';
        }

        long delivered() {
            return delivered.get();
        }
    }

    // ---------- helpers ----------

    private static AdminRequestLimitFilter filter() {
        return new AdminRequestLimitFilter(LIMIT);
    }

    private static byte[] bytes(int length) {
        return "a".repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    private static TestRequest request(String method, String uri, String contentType, byte[] body) {
        return new TestRequest(method, uri, contentType, new ByteArrayInputStream(body), body.length);
    }

    /** A request that declares nothing: no Content-Length and no Transfer-Encoding. */
    private static TestRequest undeclared(String contentType, InputStream body) {
        return new TestRequest("POST", LOGIN_PATH, contentType, body, ABSENT_LENGTH);
    }

    private static TestRequest chunked(String contentType, InputStream body) {
        TestRequest request = new TestRequest("POST", LOGIN_PATH, contentType, body, ABSENT_LENGTH);
        request.addHeader(TRANSFER_ENCODING, CHUNKED);
        return request;
    }

    private static Result run(AdminRequestLimitFilter filter, HttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> forwarded = new AtomicReference<>();
        FilterChain chain = (forwardedRequest, ignored) -> forwarded.set(forwardedRequest);
        filter.doFilter(request, response, chain);
        return new Result(response, forwarded.get());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(MockHttpServletResponse response) throws Exception {
        return (Map<String, Object>) MAPPER.readValue(response.getContentAsString(StandardCharsets.UTF_8),
                Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(MockHttpServletResponse response) throws Exception {
        return (Map<String, Object>) parse(response).get("error");
    }

    // ---------- methods ----------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "HEAD", "DELETE", "OPTIONS"})
    @DisplayName("methods without a body pass untouched, whatever the Content-Type")
    void methodsWithoutABodyPassUntouched(String method) throws Exception {
        TestRequest request = new TestRequest(method, LOGIN_PATH, TEXT, new UnreadableBody(), 3);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isSameAs(request);
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
    }

    // ---------- declared body: Content-Type (Rule 16.4, Ruling 129) ----------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "post", "patch"})
    @DisplayName("a declared body without a Content-Type is 415, and the body is never read")
    void aBodyWithoutAContentTypeIsRejected(String method) throws Exception {
        TestRequest request = new TestRequest(method, LOGIN_PATH, null, new UnreadableBody(), 3);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_415);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"application/json", "APPLICATION/JSON", "Application/Json",
            "application/json; charset=UTF-8", "application/json;charset=utf-8",
            "application/json; charset=\"UTF-8\""})
    @DisplayName("application/json with no charset or UTF-8 passes, in any letter case")
    void acceptedContentTypesPass(String contentType) throws Exception {
        Result result = run(filter(), request("POST", LOGIN_PATH, contentType, bytes(3)));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"text/plain", "application/jsonx", "application/xml", "application/problem+json",
            "application/json; charset=ISO-8859-1", "application/json; charset=UTF-16",
            "application/json; charset=bogus-charset", "garbage", "", "   ", "application/",
            "multipart/form-data; boundary=x", "application/x-www-form-urlencoded"})
    @DisplayName("any other type on a declared body is 415 and the body is never read")
    void otherContentTypesAreRejectedBeforeTheBodyIsRead(String contentType) throws Exception {
        TestRequest request = new TestRequest("POST", LOGIN_PATH, contentType, new UnreadableBody(), 3);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_415);
    }

    @Test
    @DisplayName("a chunked body (Transfer-Encoding) with a wrong or missing type is 415 and is never read")
    void aChunkedBodyWithTheWrongTypeIsRejected() throws Exception {
        for (String contentType : new String[] {null, TEXT, "application/json; charset=ISO-8859-1"}) {
            Result result = run(filter(), chunked(contentType, new UnreadableBody()));

            assertThat(result.forwarded()).as(String.valueOf(contentType)).isNull();
            assertThat(result.response().getStatus()).as(String.valueOf(contentType)).isEqualTo(HTTP_415);
        }
    }

    @Test
    @DisplayName("a chunked JSON body passes, and a chunked body over the limit is 413")
    void aChunkedJsonBodyIsBounded() throws Exception {
        Result fits = run(filter(), chunked(JSON, new ByteArrayInputStream(bytes(LIMIT))));
        Result over = run(filter(), chunked(JSON, new ByteArrayInputStream(bytes(LIMIT + 1))));

        assertThat(fits.forwarded()).isNotNull();
        assertThat(fits.forwarded().getContentLengthLong()).isEqualTo(LIMIT);
        assertThat(over.forwarded()).isNull();
        assertThat(over.response().getStatus()).isEqualTo(HTTP_413);
    }

    // ---------- Ruling 129: requests without a body ----------

    @Test
    @DisplayName("a POST without a body passes the type check, whatever the Content-Type: logout and unlock send none")
    void aRequestWithoutABodySkipsTheTypeCheck() throws Exception {
        for (String contentType : new String[] {null, TEXT, "garbage", JSON}) {
            for (String method : new String[] {"POST", "PUT", "PATCH"}) {
                TestRequest request = request(method, LOGOUT_PATH, contentType, new byte[0]);

                Result result = run(filter(), request);

                assertThat(result.forwarded()).as(method + " " + contentType).isNotNull();
                assertThat(result.response().getStatus()).as(method + " " + contentType).isEqualTo(HTTP_OK);
                assertThat(result.forwarded().getContentLengthLong()).isZero();
            }
        }
    }

    // ---------- Ruling 130: an undeclared body ----------

    @Test
    @DisplayName("undeclared, no bytes, text/plain: the request passes")
    void anUndeclaredEmptyRequestPasses() throws Exception {
        Result result = run(filter(), undeclared(TEXT, new ByteArrayInputStream(new byte[0])));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
        assertThat(result.forwarded().getContentLengthLong()).isZero();
    }

    @Test
    @DisplayName("undeclared, bytes arrived, text/plain: 415")
    void anUndeclaredBodyWithTheWrongTypeIsRejected() throws Exception {
        Result result = run(filter(), undeclared(TEXT, new ByteArrayInputStream(bytes(3))));

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_415);
    }

    @Test
    @DisplayName("undeclared, endless text/plain body: 415 with exactly limit plus one byte delivered")
    void anUndeclaredEndlessWrongTypeBodyIsBoundedAndRejectedWith415() throws Exception {
        EndlessBody endless = new EndlessBody();

        Result result = run(filter(), undeclared(TEXT, endless));

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_415);
        assertThat(endless.delivered()).isEqualTo(LIMIT + 1);
    }

    @Test
    @DisplayName("undeclared, JSON over the limit: 413")
    void anUndeclaredJsonBodyOverTheLimitIsRejectedWith413() throws Exception {
        Result result = run(filter(), undeclared(JSON, new ByteArrayInputStream(bytes(LIMIT + 1))));

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_413);
    }

    @Test
    @DisplayName("undeclared, small JSON: the request passes with the real length")
    void anUndeclaredSmallJsonBodyPasses() throws Exception {
        Result result = run(filter(), undeclared(JSON, new ByteArrayInputStream(bytes(3))));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
        assertThat(result.forwarded().getContentLengthLong()).isEqualTo(3);
    }

    // ---------- size (Rule 16.3) ----------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    @DisplayName("a body of exactly the limit passes and one byte more is 413")
    void aBodyAtTheLimitPassesAndOneMoreIsRejected(String method) throws Exception {
        Result atLimit = run(filter(), request(method, LOGIN_PATH, JSON, bytes(LIMIT)));
        Result over = run(filter(), request(method, LOGIN_PATH, JSON, bytes(LIMIT + 1)));
        Result farOver = run(filter(), request(method, LOGIN_PATH, JSON, bytes(LIMIT * FAR_OVER_FACTOR)));

        assertThat(atLimit.forwarded()).isNotNull();
        assertThat(atLimit.response().getStatus()).isEqualTo(HTTP_OK);
        for (Result rejected : new Result[] {over, farOver}) {
            assertThat(rejected.forwarded()).isNull();
            assertThat(rejected.response().getStatus()).isEqualTo(HTTP_413);
        }
    }

    @Test
    @DisplayName("at most limit plus one byte is ever read, even from a body that never ends")
    void readsAtMostLimitPlusOneByte() throws Exception {
        EndlessBody endless = new EndlessBody();
        TestRequest request = new TestRequest("POST", LOGIN_PATH, JSON, endless, ABSENT_LENGTH);

        Result result = run(filter(), request);

        assertThat(result.response().getStatus()).isEqualTo(HTTP_413);
        assertThat(endless.delivered()).isEqualTo(LIMIT + 1);
    }

    @Test
    @DisplayName("Content-Length is not trusted: a small lie, an absent length and a large lie behave by the real size")
    void contentLengthIsNotTrusted() throws Exception {
        byte[] oversize = bytes(LIMIT + 1);
        byte[] fits = bytes(3);

        Result smallLie = run(filter(),
                new TestRequest("POST", LOGIN_PATH, JSON, new ByteArrayInputStream(oversize), 1));
        Result absent = run(filter(),
                new TestRequest("POST", LOGIN_PATH, JSON, new ByteArrayInputStream(oversize), ABSENT_LENGTH));
        Result largeLie = run(filter(),
                new TestRequest("POST", LOGIN_PATH, JSON, new ByteArrayInputStream(fits), LYING_LARGE_LENGTH));

        assertThat(smallLie.response().getStatus()).isEqualTo(HTTP_413);
        assertThat(absent.response().getStatus()).isEqualTo(HTTP_413);
        assertThat(largeLie.forwarded()).isNotNull();
        assertThat(largeLie.forwarded().getContentLength()).isEqualTo(fits.length);
    }

    // ---------- the forwarded request ----------

    @Test
    @DisplayName("the forwarded request can be read again, as a stream and as UTF-8 text")
    void theForwardedRequestCanBeReadAgain() throws Exception {
        byte[] body = "h\u00e9llo".getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request("POST", LOGIN_PATH, JSON, body), response, (forwarded, ignored) -> {
            calls.incrementAndGet();
            ServletInputStream first = forwarded.getInputStream();
            assertThat(first.isReady()).isTrue();
            assertThat(first.isFinished()).isFalse();
            assertThat(first.readAllBytes()).isEqualTo(body);
            assertThat(first.isFinished()).isTrue();
            assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(body);
            assertThat(forwarded.getReader().readLine()).isEqualTo("h\u00e9llo");
            assertThat(forwarded.getContentLength()).isEqualTo(body.length);
            assertThat(forwarded.getContentLengthLong()).isEqualTo(body.length);
            assertThatThrownBy(() -> first.setReadListener(null)).isInstanceOf(IllegalStateException.class);
        });

        assertThat(calls).hasValue(1);
    }

    // ---------- error bodies (AC 3) ----------

    @Test
    @DisplayName("415 and 413 use the admin error shape, UTF-8 JSON and fixed messages")
    void errorBodiesKeepTheAdminShape() throws Exception {
        Result unsupported = run(filter(), request("POST", LOGIN_PATH, TEXT, bytes(3)));
        Result tooLarge = run(filter(), request("POST", LOGIN_PATH, JSON, bytes(LIMIT + 1)));

        Object[][] expected = {
                {unsupported, "UNSUPPORTED_MEDIA_TYPE", MESSAGE_415},
                {tooLarge, "PAYLOAD_TOO_LARGE", MESSAGE_413}};
        for (Object[] row : expected) {
            MockHttpServletResponse response = ((Result) row[0]).response();
            Map<String, Object> error = errorOf(response);

            assertThat(parse(response)).containsOnlyKeys("error");
            assertThat(error).containsOnlyKeys("code", "message", "retryable");
            assertThat(error.get("code")).isEqualTo(row[1]);
            assertThat(error.get("message")).isEqualTo(row[2]);
            assertThat(error.get("retryable")).isEqualTo(Boolean.FALSE);
            assertThat(response.getContentType()).isEqualTo(ERROR_CONTENT_TYPE);
            assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        }
        assertThat(unsupported.response().getStatus()).isEqualTo(HTTP_415);
        assertThat(tooLarge.response().getStatus()).isEqualTo(HTTP_413);
    }

    @Test
    @DisplayName("error bodies never echo the Content-Type or the body that was sent")
    void errorBodiesDoNotEchoTheRequest() throws Exception {
        byte[] secretBody = "secret-body-secret-body".getBytes(StandardCharsets.UTF_8);

        Result unsupported = run(filter(), request("POST", LOGIN_PATH, "x/secret-type", secretBody));
        Result undeclaredWrongType = run(filter(),
                undeclared("x/secret-type", new ByteArrayInputStream(secretBody)));
        Result tooLarge = run(filter(), request("POST", LOGIN_PATH, JSON, secretBody));

        for (Result result : new Result[] {unsupported, undeclaredWrongType, tooLarge}) {
            assertThat(result.response().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret");
        }
    }

    // ---------- other behaviour ----------

    @Test
    @DisplayName("the container's ERROR dispatch is skipped")
    void theErrorDispatchIsSkipped() throws Exception {
        TestRequest request = request("POST", LOGIN_PATH, TEXT, bytes(3));
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, LOGIN_PATH);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
    }

    @Test
    @DisplayName("the limit must be positive and below Integer.MAX_VALUE")
    void theConstructorRejectsInvalidLimits() {
        for (int invalid : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new AdminRequestLimitFilter(invalid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(String.valueOf(invalid));
        }
    }

    @Test
    @DisplayName("the filter is no Spring component and holds no logger, so nothing from a request can be logged")
    void theFilterIsNoBeanAndLogsNothing() {
        assertThat(Arrays.stream(AdminRequestLimitFilter.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getName()))
                .noneMatch(name -> name.startsWith("org.springframework"));
        assertThat(Arrays.stream(AdminRequestLimitFilter.class.getDeclaredFields()).map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.startsWith("org.slf4j") || name.startsWith("java.util.logging"));
    }
}