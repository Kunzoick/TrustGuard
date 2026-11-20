package com.trustguard.api.admin.auth;
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
 * Content-Type and body-size gate for the admin chain (Rules 16.3 and 16.4, Rulings 90, 128 and 129). It is the admin
 * chain's own filter: it copies the pattern of the tenant chain's RequestLimitFilter and shares no code with it
 * (Rule 16.7). Created with {@code new} in AdminSecurityConfig and registered before AdminJwtFilter, never a bean.
 *
 * <p>It runs BEFORE authentication, because POST /api/admin/auth/login is unauthenticated and must be bounded before
 * it is read. So an unauthenticated caller gets 413 or 415 instead of 401 on admin paths; that is accepted.
 *
 * <p>Only POST, PUT and PATCH are checked. The size bound applies to every one of them: at most limit plus one byte
 * is read, Content-Length is never trusted, a body of exactly the limit passes and one byte more gets 413. The
 * Content-Type check (Ruling 129, which departs from Ruling 85 for this chain) applies only when the request carries
 * a body: a Content-Length above 0, or a Transfer-Encoding header. Then the type must be application/json, compared
 * case-insensitively, with no charset or UTF-8, otherwise the answer is 415 and no body byte has been read. A POST
 * without a body, such as logout or unlock, passes the type check. The controller then receives a wrapper over the
 * bytes that can be read again.
 *
 * <p>Error bodies keep the admin platform shape with fixed messages. No value from the request is echoed and nothing
 * from the request is logged (Rule 14.7): the filter has no logger. It sets no MDC key. The ERROR dispatch is
 * skipped, as OncePerRequestFilter does by default. The limit must be positive and below Integer.MAX_VALUE.
 */
public final class AdminRequestLimitFilter extends OncePerRequestFilter{
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";
    private static final int HTTP_UNSUPPORTED_MEDIA_TYPE = 415;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final String RESPONSE_CONTENT_TYPE = "application/json;charset=UTF-8";
    private static final String MESSAGE_UNSUPPORTED_MEDIA_TYPE = "Content-Type must be application/json with UTF-8.";
    private static final String MESSAGE_PAYLOAD_TOO_LARGE = "Request body exceeds the maximum allowed size.";
    private static final String MESSAGE_INVALID_LIMIT = "The admin request body limit must be a positive number "
            + "of bytes below Integer.MAX_VALUE.";
    private final int limitBytes;

    //creates the filter
    public AdminRequestLimitFilter(int limitBytes){
        if(limitBytes < 1 || limitBytes== Integer.MAX_VALUE){
            throw new IllegalStateException(MESSAGE_INVALID_LIMIT);
        }
        this.limitBytes= limitBytes;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!carriesBodyMethod(request)) {
            chain.doFilter(request, response);
            return;
        }
        boolean declared = declaresBody(request);
        boolean jsonUtf8 = isJsonUtf8(request.getContentType());
        if (declared && !jsonUtf8) {
            rejectUnsupportedType(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limitBytes + 1);
        if (!declared && body.length > 0 && !jsonUtf8) {
            rejectUnsupportedType(response);
            return;
        }
        if (body.length > limitBytes) {
            reject(response, HTTP_PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, MESSAGE_PAYLOAD_TOO_LARGE);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }
    private static boolean carriesBodyMethod(HttpServletRequest request){
        return BODY_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));
    }
    //a body is announced by a content-length above 0 or by a transfer_encoding header
    private static boolean declaresBody(HttpServletRequest request){
        return request.getContentLengthLong() > 0 || request.getHeader(HEADER_TRANSFER_ENCODING) !=null;
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
        }catch (IllegalArgumentException ex){
            return false;
        }
    }
    private static void rejectUnsupportedType(HttpServletResponse response) throws IOException {
        reject(response, HTTP_UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                MESSAGE_UNSUPPORTED_MEDIA_TYPE);
    }
    private static void reject(HttpServletResponse response, int status, ErrorCode code,  String message)
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
                public boolean isFinished(){
                    return source.available()== 0;
                }
                @Override
                public boolean isReady(){
                    return true;
                }
                @Override
                public void setReadListener(ReadListener readListener){
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
