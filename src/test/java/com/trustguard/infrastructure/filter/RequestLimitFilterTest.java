package com.trustguard.infrastructure.filter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

class RequestLimitFilterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final int SIGNALS_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 4;
    private static final int FAR_OVER_FACTOR = 100;
    private static final long LYING_LARGE_LENGTH = 1_000_000L;
    private static final long ABSENT_LENGTH = -1L;

    private static final String SIGNALS_PATH = "/api/v1/signals/login";
    private static final String RISK_PATH = "/api/v1/risk/check";
    private static final String JSON = "application/json";
    private static final String ERROR_CONTENT_TYPE = "application/json;charset=UTF-8";
    private static final String MESSAGE_415 = "Content-Type must be application/json with UTF-8.";
    private static final String MESSAGE_413 = "Request body exceeds the maximum allowed size.";

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

    private static RequestLimitFilter filter() {
        return new RequestLimitFilter(SIGNALS_LIMIT, DEFAULT_LIMIT);
    }

    private static byte[] bytes(int length) {
        return "a".repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    private static TestRequest request(String method, String uri, String contentType, byte[] body) {
        return new TestRequest(method, uri, contentType, new ByteArrayInputStream(body), body.length);
    }

    private static Result run(RequestLimitFilter filter, HttpServletRequest request) throws Exception {
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

    static Stream<Arguments> limitCases() {
        return Stream.of(
                Arguments.of(SIGNALS_PATH, SIGNALS_LIMIT),
                Arguments.of(RISK_PATH, DEFAULT_LIMIT),
                Arguments.of("/api/v1/signalsx/login", DEFAULT_LIMIT));
    }

    // ---------- methods ----------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "HEAD", "DELETE", "OPTIONS"})
    @DisplayName("methods without a body pass untouched, whatever the Content-Type")
    void methodsWithoutABodyPassUntouched(String method) throws Exception {
        TestRequest request = new TestRequest(method, SIGNALS_PATH, "text/plain", new UnreadableBody(), 0);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isSameAs(request);
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "post", "patch"})
    @DisplayName("POST, PUT and PATCH are checked, and a missing Content-Type is rejected before the body is read")
    void bodyMethodsAreChecked(String method) throws Exception {
        TestRequest request = new TestRequest(method, SIGNALS_PATH, null, new UnreadableBody(), 3);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_415);
    }

    // ---------- Content-Type (Rule 16.4) ----------

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"application/json", "APPLICATION/JSON", "Application/Json",
            "application/json; charset=UTF-8", "application/json;charset=utf-8",
            "application/json; charset=\"UTF-8\""})
    @DisplayName("application/json with no charset or UTF-8 passes, in any letter case")
    void acceptedContentTypesPass(String contentType) throws Exception {
        Result result = run(filter(), request("POST", SIGNALS_PATH, contentType, bytes(3)));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"text/plain", "application/jsonx", "application/xml", "application/problem+json",
            "application/json; charset=ISO-8859-1", "application/json; charset=UTF-16",
            "application/json; charset=bogus-charset", "garbage", "", "   ", "application/",
            "multipart/form-data; boundary=x", "application/x-www-form-urlencoded"})
    @DisplayName("anything else is 415 and the body is never read")
    void otherContentTypesAreRejectedBeforeTheBodyIsRead(String contentType) throws Exception {
        TestRequest request = new TestRequest("POST", SIGNALS_PATH, contentType, new UnreadableBody(), 3);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_415);
    }

    // ---------- size (Rule 16.3, Ruling 83) ----------

    @ParameterizedTest(name = "{0} limit {1}")
    @MethodSource("limitCases")
    @DisplayName("a body of exactly the limit passes and one byte more is 413")
    void aBodyAtTheLimitPassesAndOneMoreIsRejected(String path, int limit) throws Exception {
        Result atLimit = run(filter(), request("POST", path, JSON, bytes(limit)));
        Result over = run(filter(), request("POST", path, JSON, bytes(limit + 1)));
        Result farOver = run(filter(), request("POST", path, JSON, bytes(limit * FAR_OVER_FACTOR)));

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
        TestRequest request = new TestRequest("POST", SIGNALS_PATH, JSON, endless, ABSENT_LENGTH);

        Result result = run(filter(), request);

        assertThat(result.response().getStatus()).isEqualTo(HTTP_413);
        assertThat(endless.delivered()).isEqualTo(SIGNALS_LIMIT + 1);
    }

    @Test
    @DisplayName("Content-Length is not trusted: a small lie, an absent length and a large lie all behave by the real size")
    void contentLengthIsNotTrusted() throws Exception {
        byte[] oversize = bytes(SIGNALS_LIMIT + 1);
        byte[] fits = bytes(3);

        Result smallLie = run(filter(),
                new TestRequest("POST", SIGNALS_PATH, JSON, new ByteArrayInputStream(oversize), 1));
        Result absent = run(filter(),
                new TestRequest("POST", SIGNALS_PATH, JSON, new ByteArrayInputStream(oversize), ABSENT_LENGTH));
        Result largeLie = run(filter(),
                new TestRequest("POST", SIGNALS_PATH, JSON, new ByteArrayInputStream(fits), LYING_LARGE_LENGTH));
        Result absentButSmall = run(filter(),
                new TestRequest("POST", SIGNALS_PATH, JSON, new ByteArrayInputStream(fits), ABSENT_LENGTH));

        assertThat(smallLie.response().getStatus()).isEqualTo(HTTP_413);
        assertThat(absent.response().getStatus()).isEqualTo(HTTP_413);
        assertThat(largeLie.forwarded()).isNotNull();
        assertThat(largeLie.forwarded().getContentLength()).isEqualTo(fits.length);
        assertThat(absentButSmall.forwarded().getContentLengthLong()).isEqualTo(fits.length);
    }

    @Test
    @DisplayName("an empty body with the right Content-Type passes")
    void anEmptyBodyPasses() throws Exception {
        Result result = run(filter(), request("POST", SIGNALS_PATH, JSON, new byte[0]));

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.forwarded().getContentLength()).isZero();
    }

    // ---------- the forwarded request ----------

    @Test
    @DisplayName("the forwarded request can be read again, as a stream and as UTF-8 text")
    void theForwardedRequestCanBeReadAgain() throws Exception {
        byte[] body = "h\u00e9llo".getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request("POST", SIGNALS_PATH, JSON, body), response, (forwarded, ignored) -> {
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

    // ---------- error bodies (AC 8) ----------

    @Test
    @DisplayName("415 and 413 use the platform body shape, UTF-8 JSON and fixed messages")
    void errorBodiesKeepThePlatformShape() throws Exception {
        Result unsupported = run(filter(), request("POST", SIGNALS_PATH, "text/plain", bytes(3)));
        Result tooLarge = run(filter(), request("POST", SIGNALS_PATH, JSON, bytes(SIGNALS_LIMIT + 1)));

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

        Result unsupported = run(filter(), request("POST", SIGNALS_PATH, "x/secret-type", secretBody));
        Result tooLarge = run(filter(), request("POST", SIGNALS_PATH, JSON, secretBody));

        for (Result result : new Result[] {unsupported, tooLarge}) {
            assertThat(result.response().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret");
        }
    }

    // ---------- other behaviour ----------

    @Test
    @DisplayName("the container's ERROR dispatch is skipped")
    void theErrorDispatchIsSkipped() throws Exception {
        TestRequest request = request("POST", SIGNALS_PATH, "text/plain", new byte[0]);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, SIGNALS_PATH);

        Result result = run(filter(), request);

        assertThat(result.forwarded()).isNotNull();
        assertThat(result.response().getStatus()).isEqualTo(HTTP_OK);
    }

    @Test
    @DisplayName("the filter is no Spring component and holds no logger, so nothing from a body can be logged")
    void theFilterIsNoBeanAndLogsNothing() {
        assertThat(Arrays.stream(RequestLimitFilter.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getName()))
                .noneMatch(name -> name.startsWith("org.springframework"));
        assertThat(Arrays.stream(RequestLimitFilter.class.getDeclaredFields()).map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.startsWith("org.slf4j") || name.startsWith("java.util.logging"));
    }
}