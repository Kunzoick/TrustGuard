package com.trustguard.signals.repository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.trustguard.shared.error.EventPersistenceException;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.util.CanonicalJsonSerializer;

/**
 * Writes one row of the append-only trust_events table with explicit SQL (Ruling 98). Every column of V11
 * is written except risk_context_snapshot, which stays NULL at ingest (Rule 6.7). The payload is canonical
 * JSON text cast to jsonb, so PostgreSQL is the backstop for NUL and unpaired surrogates (Rulings 66 and
 * 101). Timestamps are written at microsecond precision (Ruling 105).
 *
 * <p>It builds its own JdbcTemplate from the primary DataSource and registers none (Ruling 103). A write
 * outside an active transaction throws {@link TransactionRequiredException}: without one, the tenant
 * setting that row level security needs would not be in place (Ruling 102). It never reads rows back.
 *
 * <p>Errors from the database are Spring data access exceptions; the service wraps them. A payload that
 * cannot be encoded is an {@link InvalidEventPayloadException} with a fixed message.
 */
@Repository
public class TrustEventRepository {
    private static final String INSERT_SQL = "INSERT INTO trust_events (event_id, tenant_id, project_id, "
            + "actor_type, actor_id, session_id, event_type, environment, occurred_at, received_at, "
            + "sdk_version, ip_address, user_agent, device_fingerprint, idempotency_key, payload) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))";

    private static final int ROWS_PER_INSERT = 1;
    private static final String MESSAGE_TRANSACTION_REQUIRED =
            "TrustEventRepository was called without an active transaction.";
    private static final String MESSAGE_NOT_STORED = "The event was not stored.";
    private static final String MESSAGE_PAYLOAD_NOT_ENCODABLE = "Event payload cannot be encoded.";

    private final JdbcTemplate jdbc;
    //creates the repository
    public TrustEventRepository(DataSource dataSource){
        this.jdbc = new JdbcTemplate(dataSource);
    }
    //inserts one event row
    public void insert(EventEnvelope envelope){
        requireTransaction();
        String payloadJson= canonicalPayload(envelope);
        int rows= jdbc.update(INSERT_SQL, ps -> {
            int index = 0;
            ps.setObject(++index, UUID.fromString(envelope.eventId().value()));
            ps.setObject(++index, UUID.fromString(envelope.tenantId().value()));
            ps.setObject(++index, UUID.fromString(envelope.projectId().value()));
            ps.setString(++index, envelope.actorType().name());
            ps.setString(++index, envelope.actorId().value());
            ps.setString(++index, envelope.sessionId());
            ps.setString(++index, envelope.eventType().name());
            ps.setString(++index, envelope.environment().name());
            ps.setObject(++index, utc(envelope.occurredAt()));
            ps.setObject(++index, utc(envelope.receivedAt()));
            ps.setString(++index, envelope.sdkVersion());
            ps.setString(++index, envelope.ipAddress());
            ps.setString(++index, envelope.userAgent());
            ps.setString(++index, envelope.deviceFingerprint());
            ps.setString(++index, envelope.idempotencyKey());
            ps.setString(++index, payloadJson);
        });
        if(rows != ROWS_PER_INSERT){
            throw new EventPersistenceException(MESSAGE_NOT_STORED, false);
        }
    }
    private static void requireTransaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()){
            throw new TransactionRequiredException(MESSAGE_TRANSACTION_REQUIRED);
        }
    }
    private static String canonicalPayload(EventEnvelope envelope){
        try{
            return CanonicalJsonSerializer.serialize(envelope.payload());
        }catch (IllegalArgumentException ex){
            throw new InvalidEventPayloadException(MESSAGE_PAYLOAD_NOT_ENCODABLE, ex);
        }
    }
    private static OffsetDateTime utc(Instant instant){
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
