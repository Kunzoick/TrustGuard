package com.trustguard.signals.repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.signals.SignalsIntegrationTestBase;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.OutboxMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class OutboxTest extends SignalsIntegrationTestBase {

    private static final String RLS_VIOLATION = "42501";
    private static final int MESSAGE_FIELD_COUNT = 10;

    @Autowired
    private OutboxRepository repository;

    private void insertAsTenant(Seeded tenant, OutboxMessage message) {
        runInTenant(tenant.tenantId(), () -> repository.insert(message));
    }

    private static Map<String, Object> row(String eventId) {
        return SUPERUSER.queryForMap("SELECT * FROM outbox_events WHERE event_id = ?::uuid", eventId);
    }

    @Test
    @DisplayName("the insert writes one PENDING row with retry_count 0 and no published_at")
    void theInsertWritesOnePendingRow() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();

        insertAsTenant(seeded, OutboxMessage.from(envelope));

        assertThat(countOutbox(envelope.eventId().value())).isEqualTo(1);
        Map<String, Object> stored = row(envelope.eventId().value());
        assertThat(stored.get("tenant_id")).isEqualTo(UUID.fromString(seeded.tenantId().value()));
        assertThat(stored).containsEntry("status", "PENDING").containsEntry("retry_count", 0);
        assertThat(stored.get("published_at")).isNull();
        assertThat(stored.get("created_at")).isNotNull();
        assertThat(stored.get("id")).isNotNull();
    }

    @Test
    @DisplayName("event_received_at equals the message's microsecond receivedAt, the locator of the event row")
    void theLocatorMatchesTheMessage() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();
        OutboxMessage message = OutboxMessage.from(envelope);

        insertAsTenant(seeded, message);

        OffsetDateTime stored = SUPERUSER.queryForObject(
                "SELECT event_received_at FROM outbox_events WHERE event_id = ?::uuid", OffsetDateTime.class,
                envelope.eventId().value());
        assertThat(stored.toInstant()).isEqualTo(message.receivedAt());
    }

    @Test
    @DisplayName("the stored payload is the claim check: exactly ten fields, schemaVersion 1")
    void theStoredPayloadIsTheClaimCheck() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();

        insertAsTenant(seeded, OutboxMessage.from(envelope));

        String id = envelope.eventId().value();
        assertThat(SUPERUSER.queryForObject(
                "SELECT count(*) FROM outbox_events o, jsonb_object_keys(o.payload) k WHERE o.event_id = ?::uuid",
                Integer.class, id)).isEqualTo(MESSAGE_FIELD_COUNT);
        assertThat(SUPERUSER.queryForObject(
                "SELECT payload->>'schemaVersion' FROM outbox_events WHERE event_id = ?::uuid", String.class, id))
                .isEqualTo("1");
        assertThat(SUPERUSER.queryForObject(
                "SELECT payload->>'eventId' FROM outbox_events WHERE event_id = ?::uuid", String.class, id))
                .isEqualTo(id);
    }

    @Test
    @DisplayName("a write outside a transaction is rejected")
    void aWriteOutsideATransactionIsRejected() {
        EventEnvelope envelope = envelopeFor(seed()).build();
        OutboxMessage message = OutboxMessage.from(envelope);

        assertThatThrownBy(() -> repository.insert(message)).isInstanceOf(TransactionRequiredException.class);
        assertThat(countOutbox(envelope.eventId().value())).isZero();
    }

    @Test
    @DisplayName("a message of another tenant fails row level security WITH CHECK")
    void aForeignTenantMessageFailsRowLevelSecurity() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope forB = envelopeFor(tenantB).build();
        OutboxMessage message = OutboxMessage.from(forB);

        Throwable thrown = catchThrowable(() -> insertAsTenant(tenantA, message));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(thrown)).isEqualTo(RLS_VIOLATION);
        assertThat(countOutbox(forB.eventId().value())).isZero();
    }

    @Test
    @DisplayName("a second tenant sees no outbox rows of the first")
    void aSecondTenantSeesNoRows() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope envelope = envelopeFor(tenantA).build();
        insertAsTenant(tenantA, OutboxMessage.from(envelope));
        JdbcTemplate app = new JdbcTemplate(dataSource);
        String sql = "SELECT count(*) FROM outbox_events WHERE event_id = ?::uuid";

        int seenByB = callInTenant(tenantB.tenantId(), () -> app.queryForObject(sql, Integer.class,
                envelope.eventId().value()));

        assertThat(seenByB).isZero();
        assertThat(countOutbox(envelope.eventId().value())).isEqualTo(1);
    }
}