package com.trustguard.signals.repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.trustguard.shared.error.EventPersistenceException;
import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.signals.domain.OutboxMessage;

/**
 * Writes one row of the transactional outbox with explicit SQL (Ruling 98). The row is a claim check
 * (Ruling 99): the tenant, the event's locator (event_id, event_received_at) and the canonical JSON of the
 * {@link OutboxMessage}. Status is PENDING and retry_count 0; the poller that publishes it is B-011.
 *
 * <p>It builds its own JdbcTemplate from the primary DataSource and registers none (Ruling 103). A write
 * outside an active transaction throws {@link TransactionRequiredException}, because without one the tenant
 * setting that row level security needs would not be in place (Ruling 102). It never reads rows back.
 * Errors from the database are Spring data access exceptions; the service wraps them.
 */
@Repository
public class OutboxRepository {

    private static final String INSERT_SQL = "INSERT INTO outbox_events (tenant_id, event_id, "
            + "event_received_at, payload, status, retry_count) VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)";

    private static final String STATUS_PENDING = "PENDING";
    private static final int INITIAL_RETRY_COUNT = 0;
    private static final int ROWS_PER_INSERT = 1;
    private static final String MESSAGE_TRANSACTION_REQUIRED =
            "OutboxRepository was called without an active transaction.";
    private static final String MESSAGE_NOT_STORED = "The outbox row was not stored.";

    private final JdbcTemplate jdbc;

    /**
     * Creates the repository.
     *
     * @param dataSource the primary data source, injected unqualified
     */
    public OutboxRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Inserts one PENDING outbox row.
     *
     * @param message the claim check of the event that was just stored
     * @throws TransactionRequiredException if no transaction is active
     * @throws EventPersistenceException    if the insert reports no stored row
     */
    public void insert(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new TransactionRequiredException(MESSAGE_TRANSACTION_REQUIRED);
        }
        int rows = jdbc.update(INSERT_SQL, ps -> {
            int index = 0;
            ps.setObject(++index, UUID.fromString(message.tenantId().value()));
            ps.setObject(++index, UUID.fromString(message.eventId().value()));
            ps.setObject(++index, OffsetDateTime.ofInstant(
                    message.receivedAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
            ps.setString(++index, message.toJson());
            ps.setString(++index, STATUS_PENDING);
            ps.setInt(++index, INITIAL_RETRY_COUNT);
        });
        if (rows != ROWS_PER_INSERT) {
            throw new EventPersistenceException(MESSAGE_NOT_STORED, false);
        }
    }
}