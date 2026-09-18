package com.trustguard.sdk.filter;
import com.trustguard.sdk.exception.ApiKeyAuthenticationException;
import com.trustguard.sdk.service.KeyHashVerificationService;
import com.trustguard.sdk.service.KeyLookupService;
import com.trustguard.sdk.service.KeyRevocationService;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * rule 5.7- implements the invariant authorization check order exactly. Each step either proceeds or throws
 * ApiKeyAuthenticationException. caught once at the top level and written directly as a JSON response
 * never rethrown into the servlet container
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {
    private static final Logger log= LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final Pattern KEY_ID_PATTERN= Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HMAC_PATTERN= Pattern.compile("[0-9a-f]{64}");

    private final KeyHashVerificationService hashVerificationService;
    private final KeyRevocationService revocationService;
    private final KeyLookupService lookupService;
    private final SecurityEventLogger securityEventLogger;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(
            KeyHashVerificationService hashVerificationService,
            KeyRevocationService revocationService,
            KeyLookupService lookupService,
            SecurityEventLogger securityEventLogger,
            ObjectMapper objectMapper) {
        this.hashVerificationService = hashVerificationService;
        this.revocationService = revocationService;
        this.lookupService = lookupService;
        this.securityEventLogger = securityEventLogger;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            //Step 1- Origin header check
            if (request.getHeader("Origin") != null) {
                throwBrowserOriginRejected(request);
            }
            //Step 2- Extract Bearer Token
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, 401,
                        "Missing or malformed Authorization header.");
            }
            String rawKey = authHeader.substring("Bearer ".length());

            //Step 3 - parse key & providedHmac
            ParsedKey parsedKey = parseKey(rawKey);

            //Step 4- verify HMAC
            if (!hashVerificationService.verify(parsedKey.keyId(), parsedKey.providedHmac())) {
                throw new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, 401,
                        "Invalid API key.");
            }
            //Step 5- Revocation check(redis -> postgreSQL fallback)
            if (revocationService.isRevoked(parsedKey.keyId())) {
                throw new ApiKeyAuthenticationException(ErrorCode.KEY_REVOKED, 401, "API key has been revoked");
            }
            //Step 6- Resolve TenantContext
            Optional<TenantContext> resolved = lookupService.resolve(parsedKey.keyId());
            if (resolved.isEmpty()) {
                throw new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, 401, "API key not found.");
            }
            //Reconstruct with the correct keyId- see KeylookupService
            //flag: the redis-cache-hit path cannot populate this field itself since the cache value schema omits keyId.
            TenantContext context = withKeyId(resolved.get(), parsedKey.keyId());

            //step 7- verify capability for this endpoint
            requireCapability(request, context);

            //Step 8- verify environment matches key environment
            requireEnvironmentMatch(parsedKey, context);

            //Step 9-11- Set context, proceed
            try {
                TenantContextHolder.set(context);
                MDC.put("tenantId", context.tenantId().value());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken
                        (context.tenantId()
                        .value(), null, List.of()));
                chain.doFilter(request, response);
            } finally {
                TenantContextHolder.clear();
                MDC.clear();
                SecurityContextHolder.clearContext();
            }
        } catch (ApiKeyAuthenticationException e) {
            writeErrorResponse(response, e);
        }
    }
    /*
    CF-007- audit logging is best effort and must never alter the authentication decision. if the security_events write fails
    the request is still rejected with 403; inly the audit trail write itself is lost, and that failure is logged at ERROR
    so it's visible in operational logs even though it cant block the response
     */
    private void throwBrowserOriginRejected(HttpServletRequest request){
        try{
            securityEventLogger.logBrowserOriginRejected(request);
        }catch (RuntimeException e){
            log.error("Failed to write BROWSER_ORIGIN_REJECTED security event. Request is still rejected with 403.", e);
        }
        throw new ApiKeyAuthenticationException(ErrorCode.BROWSER_ORIGIN_REJECTED, 403,
                "TrustGuard SDK is server-side only. Do not call TrustGuard directly from browser code.");
    }

    private ParsedKey parseKey(String rawKey){
        String[] segments= rawKey.split("_");
        if(segments.length != 4 || !segments[0].equals("sk") || !(segments[1].equals("live") ||
                segments[1].equals("test"))){
            throw new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, 401, "Malformed API key format.");
        }
        String keyId= segments[2];
        String providedHmac= segments[3];
        if(!KEY_ID_PATTERN.matcher(keyId).matches()){
            throw new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, 401,
                    "API key ID must be exactly 32 lowercase hex characters.");
        }
        if(!HMAC_PATTERN.matcher(providedHmac).matches()){
            throw new ApiKeyAuthenticationException(ErrorCode.INVALID_API_KEY, 401,
                    "API key signature must be exactly 64 lowercase hex characters.");
        }
        return new ParsedKey(segments[1],keyId, providedHmac);
    }
    /**
     * Flag: capability-to-endpoint maping is a minimal path-prefix scheme for V1
     */
    private void requireCapability(HttpServletRequest request, TenantContext context){
        String path= request.getRequestURI();
        Capability required;
        if(path.startsWith("/api/v1/signals/")){
            required= Capability.TRACK;
        }else if(path.startsWith("/api/v1/risk/")){
            required= Capability.CHECK;
        }else if(path.startsWith("/api/v1/actors/") || path.startsWith("/api/v1/decisions/")){
            required= Capability.FEEDBACK;
        }else{
            //no capability requirement inferres for this path- proceed. Admin routes are out of scope
            //for this filter per brief
            return;
        }
        if(!context.capabilities().contains(required)){
            throw new ApiKeyAuthenticationException(ErrorCode.CAPABILITY_INSUFFICIENT, 403,
                    "This API key lacks the "+ required+ " capability for this endpoint.");
        }
    }
    /**
     * Flag: the brief does not specify how the request declares its intended environment for comparision
     * against the key environment
     */
    private void requireEnvironmentMatch(ParsedKey parsedKey, TenantContext context) {
        boolean prefixIsLive= "live".equals(parsedKey.environment());
        boolean contextIsProduction= Environment.PRODUCTION.equals(context.environment());
        if(prefixIsLive != contextIsProduction){
            throw new ApiKeyAuthenticationException(ErrorCode.ENVIRONMENT_MISMATCH, 401,
                    "API key environment does not match the required environment.");
        }
    }
        private TenantContext withKeyId(TenantContext original, String keyId){
            return new TenantContext(original.tenantId(), original.projectId(), keyId, original.environment(),
                    original.capabilities(), original.configVersion());
        }
        private void writeErrorResponse(HttpServletResponse response, ApiKeyAuthenticationException e)
                throws IOException{
            response.setStatus(e.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> body= Map.of("error", Map.of("code", e.code().name(), "message",
                    e.getMessage(), "retryable", false));
            response.getWriter().write(objectMapper.writeValueAsString(body));
        }
        private record ParsedKey(String environment, String keyId, String providedHmac){}
}
