package com.trustguard.signals.repository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.error.EventPersistenceException;
import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.signals.constants.IdempotencyConstants;
import com.trustguard.signals.domain.EventEnvelope;

/**
 * The durable idempotency backstop (Rule 6.4, Ruling 37), explicit SQL like the other signals repositories
 * (Ruling 98). It claims (tenant, idempotency key) for an event inside the caller's transaction, so a failed
 * event or outbox insert removes the claim as well.
 *
 * <p>The claim is three statements. An expired row for the key is deleted, using the envelope's receivedAt as
 * "now". Then the row is inserted with ON CONFLICT DO NOTHING: the application role has no UPDATE right (V13), and
 * the conflict is the duplicate signal. Only when no row was inserted is the original event id read.
 *
 * <p>It builds its own JdbcTemplate from the primary DataSource and registers none (Ruling 103). A call outside an
 * active transaction throws {@link TransactionRequiredException}, because without one the tenant setting that row
 * level security needs would not be in place (Ruling 102). Errors from the database are Spring data access
 * exceptions; the service wraps them.
 */
@Repository
public class IdempotencyKeyRepository {
    private static final String DELETE_EXPIRED_SQL = "DELETE FROM idempotency_keys "
            + "WHERE tenant_id = ? AND idempotency_key = ? AND expires_at <= ?";
    private static final String INSERT_SQL = "INSERT INTO idempotency_keys "
            + "(tenant_id, idempotency_key, event_id, expires_at) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING";
    private static final String SELECT_ORIGINAL_SQL = "SELECT event_id FROM idempotency_keys "
            + "WHERE tenant_id = ? AND idempotency_key = ?";
    private static final int ROWS_CLAIMED = 1;
    private static final String MESSAGE_TRANSACTION_REQUIRED =
            "IdempotencyKeyRepository was called without an active transaction.";
    private static final String MESSAGE_CLAIM_UNRESOLVED = "The idempotency claim could not be resolved.";
    private final JdbcTemplate jdbc;

    //creates the repository
    public IdempotencyKeyRepository(DataSource dataSource){
        this.jdbc= new JdbcTemplate(dataSource);
    }

    //claims the envelope's idempotency key, or reports who holds it.
    public Optional<EventId> claimOrFindOriginal(EventEnvelope envelope){
        if(!TransactionSynchronizationManager.isActualTransactionActive()){
            throw new TransactionRequiredException(MESSAGE_TRANSACTION_REQUIRED);
        }
        UUID tenant= UUID.fromString(envelope.tenantId().value());
        String key= envelope.idempotencyKey();
        Instant receivedAt= envelope.receivedAt().truncatedTo(ChronoUnit.MICROS);

        jdbc.update(DELETE_EXPIRED_SQL, ps -> {
            ps.setObject(1, tenant);
            ps.setObject(2, key);
            ps.setObject(3, utc(receivedAt));
        });
        int inserted= jdbc.update(INSERT_SQL, ps-> {
            ps.setObject(1, tenant);
            ps.setString(2, key);
            ps.setObject(3, UUID.fromString(envelope.eventId().value()));
            ps.setObject(4, utc(receivedAt.plus(IdempotencyConstants.KEY_TTL)));
        });
        if(inserted== ROWS_CLAIMED){
            return Optional.empty();
        }
        List<EventId> original= jdbc.query(SELECT_ORIGINAL_SQL, ps -> {
            ps.setObject(1, tenant);
            ps.setString(2, key);
        }, (rs, rowNum) -> new EventId(rs.getObject(1, UUID.class).toString()));
        if(original.isEmpty()){
            throw new EventPersistenceException(MESSAGE_CLAIM_UNRESOLVED, true);
        }
        return Optional.of(original.get(0));
    }
    private static OffsetDateTime utc(Instant instant){
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

}
