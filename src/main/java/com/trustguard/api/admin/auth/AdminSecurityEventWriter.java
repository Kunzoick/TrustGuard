package com.trustguard.api.admin.auth;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes admin events to the shared security_events table (RULING 18: shared table, separate code).
 * Details are serialized with the ObjectMapper, never string-built.
 * <p>
 * RULING 22: the insert runs in its own REQUIRES_NEW transaction, so it is structurally independent of
 * any caller transaction. Audit loss is loud: a failure increments security_event.write.failure (tag
 * writer=admin) and logs at ERROR. No rule in the Contract names this metric. The name follows the
 * Rule 17.10 precedent (async.uncaught.exception) and was specified by Agent 4. Rule 14.3's four
 * mandatory dashboard metrics are not extended by it.
 */
@Component
public class AdminSecurityEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityEventWriter.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTemplate;
    private final Counter writeFailureCounter;

    /**
     * Creates the writer. The first parameter name must stay jdbcTemplate: it disambiguates the primary
     * JdbcTemplate from the auth-resolver one, exactly as SecurityEventLogger does.
     *
     * @param jdbcTemplate       primary (trustguard_app) template
     * @param objectMapper       Jackson 3 mapper
     * @param transactionManager the primary JPA transaction manager
     * @param meterRegistry      metrics registry
     */
    public AdminSecurityEventWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;
        this.writeFailureCounter = Counter.builder(AdminAuthConstants.METRIC_EVENT_WRITE_FAILURE)
                .tag(AdminAuthConstants.METRIC_TAG_WRITER, AdminAuthConstants.METRIC_WRITER_ADMIN)
                .description("Security event inserts that failed and were lost")
                .register(meterRegistry);
    }

    /**
     * Inserts one event in its own REQUIRES_NEW transaction. Throws on failure.
     *
     * @param eventType event type
     * @param actorId   username or admin id
     * @param ipAddress remote address
     * @param details   internal details, never secrets
     */
    public void record(String eventType, String actorId, String ipAddress, Map<String, ?> details) {
        String detailsJson = objectMapper.writeValueAsString(details);
        requiresNewTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                "INSERT INTO security_events (id, event_type, actor_id, ip_address, details) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), eventType, truncate(actorId, AdminAuthConstants.MAX_ACTOR_ID_LENGTH),
                truncate(ipAddress, AdminAuthConstants.MAX_IP_LENGTH), detailsJson));
    }

    /**
     * Best-effort insert (RULING 22): a failure increments the failure counter, then logs at ERROR,
     * and never propagates.
     *
     * @param eventType event type
     * @param actorId   username or admin id
     * @param ipAddress remote address
     * @param details   internal details, never secrets
     */
    public void recordBestEffort(String eventType, String actorId, String ipAddress, Map<String, ?> details) {
        try {
            record(eventType, actorId, ipAddress, details);
        } catch (RuntimeException e) {
            writeFailureCounter.increment();
            log.error("Failed to write {} security event; the audit trail entry is lost.", eventType, e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}