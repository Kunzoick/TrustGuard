package com.trustguard.infrastructure.filter;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.trustguard.shared.enums.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Content-Type and body-size gate for the tenant chain (Rules 16.3 and 16.4, Rulings 83 and 85).
 * Created with {@code new} in SecurityConfig and registered after the API key filter, so an
 * unauthenticated request is answered 401 before this filter can answer 415 or 413 (Rule 5.7). It is
 * never a bean.
 *
 * <p>Only POST, PUT and PATCH are checked; every other method passes untouched. The Content-Type must
 * be application/json (compared case-insensitively) and any charset must be UTF-8, otherwise the answer
 * is 415 and no body byte has been read. That also applies to a POST with no body, as Rule 16.4 reads.
 *
 * <p>The size check reads at most limit plus one byte into memory, so the answer is deterministic and
 * never depends on Content-Length, which is not trusted: chunked and length-less bodies are covered. A
 * body of exactly the limit passes; one byte more gets 413. The controller then receives a wrapper
 * over the bytes that can be read again. /api/v1/signals/** gets the signals limit; every other path
 * gets the default limit.
 *
 * <p>Error bodies keep the platform shape with fixed messages. No value from the request is echoed and
 * nothing from the body is logged (Rule 14.7). The ERROR dispatch is skipped, as OncePerRequestFilter
 * does by default. Limits must be positive and below Integer.MAX_VALUE.
 */
public final class RequestLimitFilter extends OncePerRequestFilter {
    private static final String SIGNALS_PATH_PREFIX = "/api/v1/signals/";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final int HTTP_UNSUPPORTED_MEDIA_TYPE = 415;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final String RESPONSE_CONTENT_TYPE = "application/json;charset=UTF-8";
    private static final String MESSAGE_UNSUPPORTED_MEDIA_TYPE = "Content-Type must be application/json with UTF-8.";
    private static final String MESSAGE_PAYLOAD_TOO_LARGE = "Request body exceeds the maximum allowed size.";

    private final int signalsBytes;
    private final int defaultBytes;
    /**
     * Creates the filter.
     *
     * @param signalsBytes body limit for /api/v1/signals/**, positive and below Integer.MAX_VALUE
     * @param defaultBytes body limit for every other path, positive and below Integer.MAX_VALUE
     */
    public RequestLimitFilter(int signalsBytes, int defaultBytes){
        this.signalsBytes= signalsBytes;
        this.defaultBytes= defaultBytes;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain Chain)
            throws ServletException, IOException {
        if(!carriesBody(request)){
            Chain.doFilter(request, response);
            return;
        }
        if(!isJsonUtf8(request.getContentType())){
            reject(response, HTTP_UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    MESSAGE_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        int limit= limitFor(request);
        byte[] body= request.getInputStream().readNBytes(limit + 1);
        if(body.length > limit){
            reject(response, HTTP_PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, MESSAGE_PAYLOAD_TOO_LARGE);
            return;
        }
        Chain.doFilter(new CachedBodyRequest(request, body), response);
    }
    private static boolean carriesBody(HttpServletRequest request){
        return BODY_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));
    }
    private static boolean isJsonUtf8(String contentType){
        if(contentType== null || contentType.isBlank()){
            return false;
        }
        try{
            MediaType mediaType= MediaType.parseMediaType(contentType);
            if(!MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType)){
                return false;
            }
            Charset charset= mediaType.getCharset();
            return charset== null || StandardCharsets.UTF_8.equals(charset);
        }catch(IllegalArgumentException ex){
            return false;
        }
    }
    private int limitFor(HttpServletRequest request){
        return request.getRequestURI().startsWith(SIGNALS_PATH_PREFIX) ? signalsBytes : defaultBytes;
    }
    private static void reject(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException{
        response.setStatus(status);
        response.setContentType(RESPONSE_CONTENT_TYPE);
        response.getWriter().write("{\"error\":{\"code\":\"" + code.name() + "\",\"message\":\"" + message
                + "\",\"retryable\":false}}");
    }
    //hands the already-read body to the rest of the chain, readable any number of times
    private static final class CachedBodyRequest extends HttpServletRequestWrapper{
        private final byte[] body;
        CachedBodyRequest(HttpServletRequest request, byte[] body){
            super(request);
            this.body= body.clone();
        }
        @Override
        public ServletInputStream getInputStream(){
            ByteArrayInputStream source= new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }
                @Override
                public boolean isReady(){
                    return true;
                }
                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new IllegalStateException("Non-blocking reads are not supported");
                }
                @Override
                public int read(){
                    return source.read();
                }
                @Override
                public int read(byte[] target, int offset, int length){
                    return source.read(target, offset, length);
                }
            };
        }
        @Override
        public BufferedReader getReader(){
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override
        public int getContentLength(){
            return body.length;
        }
        @Override
        public long getContentLengthLong(){
            return body.length;
        }
    }
}
