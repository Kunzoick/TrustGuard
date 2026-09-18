package com.trustguard.sdk.filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;
/*
writes to the security_events table, for auth-path events that c=occur before TenantContext exists.
Uses the main trustguard_app connection pool, DataSource- securiy is not RLS-scoped, so no bypass role needed here.
 */
@Component
public class SecurityEventLogger {
    private final JdbcTemplate jdbcTemplate;
    public SecurityEventLogger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    public void logBrowserOriginRejected(HttpServletRequest request){
        jdbcTemplate.update( "INSERT INTO security_events (id, event_type, ip_address, details) "
                + "VALUES (?, ?, ?, ?::jsonb)",UUID.randomUUID(), "BROWSER_ORIGIN_REJECTED", request.getRemoteAddr(),
                String.format( "{\"origin\":\"%s\",\"path\":\"%s\"}", sanitize(request.getHeader("Origin")),
                        sanitize(request.getRequestURI())));
    }
    private static String sanitize(String value){
        if(value== null){
            return "";
        }
        return value.replace("\"", "\\\"");
    }
}
