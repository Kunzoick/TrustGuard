package com.trustguard.signals.repository;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.signals.SignalsIntegrationTestBase;
import com.trustguard.signals.domain.EventEnvelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class TrustEventRepositoryTest extends SignalsIntegrationTestBase {

    private static final String RLS_VIOLATION = "42501";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final int EXTRA_NANOS = 789;

    @Autowired
    private TrustEventRepository repository;

    private void insertAsTenant(EventEnvelope envelope) {
        runInTenant(envelope.tenantId(), () -> repository.insert(envelope));
    }

    private static Map<String, Object> row(String eventId) {
        return SUPERUSER.queryForMap("SELECT * FROM trust_events WHERE event_id = ?::uuid", eventId);
    }

    // ---------- the row ----------

    @Test
    @DisplayName("the insert stores every column and leaves the risk snapshot NULL")
    void theInsertStoresEveryColumn() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();

        insertAsTenant(envelope);

        Map<String, Object> stored = row(envelope.eventId().value());
        assertThat(stored.get("tenant_id")).isEqualTo(UUID.fromString(seeded.tenantId().value()));
        assertThat(stored.get("project_id")).isEqualTo(UUID.fromString(seeded.projectId().value()));
        assertThat(stored).containsEntry("actor_type", "USER").containsEntry("actor_id", "user-42")
                .containsEntry("session_id", "session-1").containsEntry("event_type", "LOGIN")
                .containsEntry("environment", "PRODUCTION").containsEntry("sdk_version", "1.0.0")
                .containsEntry("ip_address", "203.0.113.7").containsEntry("user_agent", "Mozilla/5.0")
                .containsEntry("device_fingerprint", "fp-1")
                .containsEntry("idempotency_key", envelope.idempotencyKey());
        assertThat(stored.get("risk_context_snapshot")).isNull();
    }

    @Test
    @DisplayName("optional columns are stored as NULL")
    void optionalColumnsAreStoredAsNull() {
        EventEnvelope envelope = envelopeFor(seed()).sessionId(null).userAgent(null).deviceFingerprint(null).build();

        insertAsTenant(envelope);

        Map<String, Object> stored = row(envelope.eventId().value());
        assertThat(stored.get("session_id")).isNull();
        assertThat(stored.get("user_agent")).isNull();
        assertThat(stored.get("device_fingerprint")).isNull();
    }

    @Test
    @DisplayName("the payload is stored as jsonb, with nested values and nulls kept")
    void thePayloadIsStoredAsJsonb() {
        EventEnvelope envelope = envelopeFor(seed()).build();

        insertAsTenant(envelope);

        String id = envelope.eventId().value();
        assertThat(SUPERUSER.queryForObject("SELECT jsonb_typeof(payload) FROM trust_events WHERE event_id = ?::uuid",
                String.class, id)).isEqualTo("object");
        assertThat(SUPERUSER.queryForObject("SELECT payload->>'k' FROM trust_events WHERE event_id = ?::uuid",
                String.class, id)).isEqualTo("v");
        assertThat(SUPERUSER.queryForObject(
                "SELECT jsonb_array_length(payload->'l') FROM trust_events WHERE event_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);
        assertThat(SUPERUSER.queryForObject(
                "SELECT (payload->'l'->1) = 'null'::jsonb FROM trust_events WHERE event_id = ?::uuid",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("a payload that cannot be encoded is rejected before any SQL, with a fixed message")
    void anUnencodablePayloadIsRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("score", Double.NaN);
        EventEnvelope envelope = envelopeFor(seed()).payload(payload).build();

        assertThatThrownBy(() -> insertAsTenant(envelope))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessage("Event payload cannot be encoded.")
                .hasMessageNotContaining("NaN");
        assertThat(countEvents(envelope.eventId().value())).isZero();
    }

    // ---------- role, transaction, isolation ----------

    @Test
    @DisplayName("inside the transaction the role is trustguard_app, not a superuser and not BYPASSRLS")
    void theRoleIsTheAppRole() {
        Seeded seeded = seed();
        JdbcTemplate app = new JdbcTemplate(dataSource);

        String user = callInTenant(seeded.tenantId(), () -> app.queryForObject("SELECT current_user", String.class));
        Map<String, Object> role = callInTenant(seeded.tenantId(), () -> app.queryForMap(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user"));

        assertThat(user).isEqualTo(APP_ROLE);
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
    }

    @Test
    @DisplayName("a write outside a transaction is rejected")
    void aWriteOutsideATransactionIsRejected() {
        EventEnvelope envelope = envelopeFor(seed()).build();

        assertThatThrownBy(() -> repository.insert(envelope)).isInstanceOf(TransactionRequiredException.class);
        assertThat(countEvents(envelope.eventId().value())).isZero();
    }

    @Test
    @DisplayName("an envelope of another tenant fails row level security WITH CHECK")
    void aForeignTenantEnvelopeFailsRowLevelSecurity() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope forB = envelopeFor(tenantB).build();

        Throwable thrown = catchThrowable(() -> runInTenant(tenantA.tenantId(), () -> repository.insert(forB)));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(thrown)).isEqualTo(RLS_VIOLATION);
        assertThat(countEvents(forB.eventId().value())).isZero();
    }

    @Test
    @DisplayName("an envelope whose project belongs to another tenant fails the composite foreign key")
    void aProjectOfAnotherTenantFailsTheForeignKey() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope crossed = envelopeFor(tenantA).projectId(tenantB.projectId()).build();

        Throwable thrown = catchThrowable(() -> insertAsTenant(crossed));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(sqlState(thrown)).isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(countEvents(crossed.eventId().value())).isZero();
    }

    @Test
    @DisplayName("a second tenant sees no rows of the first")
    void aSecondTenantSeesNoRows() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope envelope = envelopeFor(tenantA).build();
        insertAsTenant(envelope);
        JdbcTemplate app = new JdbcTemplate(dataSource);
        String sql = "SELECT count(*) FROM trust_events WHERE event_id = ?::uuid";

        int seenByB = callInTenant(tenantB.tenantId(), () -> app.queryForObject(sql, Integer.class,
                envelope.eventId().value()));
        int seenByA = callInTenant(tenantA.tenantId(), () -> app.queryForObject(sql, Integer.class,
                envelope.eventId().value()));

        assertThat(seenByB).isZero();
        assertThat(seenByA).isEqualTo(1);
        assertThat(countEvents(envelope.eventId().value())).isEqualTo(1);
    }

    // ---------- partitions and precision ----------

    @Test
    @DisplayName("an event lands in the monthly partition that matches its received_at")
    void anEventLandsInTheMatchingPartition() {
        EventEnvelope envelope = envelopeFor(seed()).build();

        insertAsTenant(envelope);

        Map<String, Object> placement = SUPERUSER.queryForMap(
                "SELECT tableoid::regclass::text AS partition, "
                        + "to_char(received_at AT TIME ZONE 'UTC', 'YYYY_MM') AS month "
                        + "FROM trust_events WHERE event_id = ?::uuid", envelope.eventId().value());
        assertThat(placement.get("partition")).isEqualTo("trust_events_" + placement.get("month"));
    }

    @Test
    @DisplayName("received_at is stored at microsecond precision, never rounded up (Ruling 105)")
    void receivedAtIsStoredAtMicrosecondPrecision() {
        EventEnvelope base = envelopeFor(seed()).build();
        EventEnvelope envelope = EventEnvelope.builder().eventId(base.eventId()).tenantId(base.tenantId())
                .projectId(base.projectId()).actorType(base.actorType()).actorId(base.actorId())
                .sessionId(base.sessionId()).eventType(base.eventType()).environment(base.environment())
                .occurredAt(base.occurredAt()).receivedAt(base.receivedAt().plusNanos(EXTRA_NANOS))
                .sdkVersion(base.sdkVersion()).ipAddress(base.ipAddress()).userAgent(base.userAgent())
                .deviceFingerprint(base.deviceFingerprint()).idempotencyKey(base.idempotencyKey())
                .payload(base.payload()).build();

        insertAsTenant(envelope);

        OffsetDateTime stored = SUPERUSER.queryForObject(
                "SELECT received_at FROM trust_events WHERE event_id = ?::uuid", OffsetDateTime.class,
                envelope.eventId().value());
        assertThat(envelope.receivedAt().getNano() % 1000).isEqualTo(EXTRA_NANOS);
        assertThat(stored.toInstant()).isEqualTo(envelope.receivedAt().truncatedTo(ChronoUnit.MICROS))
                .isNotEqualTo(envelope.receivedAt());
    }

    // ---------- text hygiene backstop (Rulings 66 and 101) ----------

    private Throwable insertWithPayloadText(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", text);
        EventEnvelope envelope = envelopeFor(seed()).payload(payload).build();
        Throwable thrown = catchThrowable(() -> insertAsTenant(envelope));
        assertThat(countEvents(envelope.eventId().value())).isZero();
        return thrown;
    }

    @Test
    @DisplayName("PostgreSQL rejects NUL in the jsonb payload; the exception type is recorded")
    void postgresRejectsNulInThePayload() {
        Throwable thrown = insertWithPayloadText("a" + (char) 0);

        observe("NUL in jsonb payload -> " + thrown.getClass().getName() + " sqlState=" + sqlState(thrown));
        assertThat(thrown).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("PostgreSQL rejects an unpaired surrogate in the jsonb payload; the exception type is recorded")
    void postgresRejectsAnUnpairedSurrogateInThePayload() {
        for (char surrogate : new char[] {(char) 0xD800, (char) 0xDC00}) {
            Throwable thrown = insertWithPayloadText("a" + surrogate);

            observe("unpaired surrogate U+" + Integer.toHexString(surrogate) + " in jsonb payload -> "
                    + thrown.getClass().getName() + " sqlState=" + sqlState(thrown));
            assertThat(thrown).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    @DisplayName("observation only: what happens to NUL and an unpaired surrogate in a TEXT column")
    void observationOfTextColumns() {
        List<String> cases = List.of("NUL:" + (char) 0, "unpaired high surrogate:" + (char) 0xD800);
        for (String label : cases) {
            String text = "a" + label.charAt(label.length() - 1);
            EventEnvelope envelope = envelopeFor(seed()).sessionId(text).build();

            Throwable thrown = catchThrowable(() -> insertAsTenant(envelope));
            String stored = thrown == null ? SUPERUSER.queryForObject(
                    "SELECT session_id FROM trust_events WHERE event_id = ?::uuid", String.class,
                    envelope.eventId().value()) : null;

            String outcome = thrown == null ? "STORED as code points " + codePoints(stored)
                    : "REJECTED " + thrown.getClass().getName() + " sqlState=" + sqlState(thrown);
            observe("TEXT column session_id, " + label.substring(0, label.indexOf(':')) + " -> " + outcome);
        }
    }

    private static String codePoints(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(point -> out.append("U+").append(Integer.toHexString(point)).append(' '));
        return out.toString().trim();
    }
}