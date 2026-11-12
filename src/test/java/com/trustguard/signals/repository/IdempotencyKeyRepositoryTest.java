package com.trustguard.signals.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.error.TransactionRequiredException;
import com.trustguard.signals.SignalsIntegrationTestBase;
import com.trustguard.signals.constants.IdempotencyConstants;
import com.trustguard.signals.domain.EventEnvelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyRepositoryTest extends SignalsIntegrationTestBase {

    private static final String OLD_EVENT_ID = "01900000-0000-7000-8000-0000000000aa";
    private static final String SELECT_EVENT_ID = "SELECT event_id::text FROM idempotency_keys "
            + "WHERE tenant_id = ?::uuid AND idempotency_key = ?";
    private static final String INSERT_ROW = "INSERT INTO idempotency_keys (tenant_id, idempotency_key, event_id, "
            + "expires_at) VALUES (?::uuid, ?, ?::uuid, ?)";
    private static final String COUNT_ROWS = "SELECT count(*) FROM idempotency_keys "
            + "WHERE tenant_id = ?::uuid AND idempotency_key = ?";
    private static final String COUNT_BY_KEY_ONLY = "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final long ONE_SECOND = 1;

    @Autowired
    private IdempotencyKeyRepository repository;

    private Optional<EventId> claim(Seeded seeded, EventEnvelope envelope) {
        return callInTenant(seeded.tenantId(), () -> repository.claimOrFindOriginal(envelope));
    }

    private int countByKeyOnlyAs(Seeded seeded, String key) {
        return callInTenant(seeded.tenantId(), () -> new JdbcTemplate(dataSource)
                .queryForObject(COUNT_BY_KEY_ONLY, Integer.class, key));
    }

    @Test
    @DisplayName("the first claim stores the key with expires_at = receivedAt + 24 hours and reports no original")
    void theFirstClaimStoresTheKey() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();

        assertThat(claim(seeded, envelope)).isEmpty();

        OffsetDateTime expires = SUPERUSER.queryForObject("SELECT expires_at FROM idempotency_keys "
                        + "WHERE tenant_id = ?::uuid AND idempotency_key = ?", OffsetDateTime.class,
                seeded.tenantId().value(), envelope.idempotencyKey());
        assertThat(expires.toInstant())
                .isEqualTo(envelope.receivedAt().truncatedTo(ChronoUnit.MICROS).plus(IdempotencyConstants.KEY_TTL));
        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, seeded.tenantId().value(),
                envelope.idempotencyKey())).isEqualTo(envelope.eventId().value());
    }

    @Test
    @DisplayName("a second claim of the same key returns the first event id and changes nothing")
    void aSecondClaimReturnsTheOriginal() {
        Seeded seeded = seed();
        EventEnvelope first = envelopeFor(seeded).build();
        EventEnvelope second = envelopeFor(seeded).idempotencyKey(first.idempotencyKey()).build();

        assertThat(claim(seeded, first)).isEmpty();
        Optional<EventId> original = claim(seeded, second);

        assertThat(original).contains(first.eventId());
        assertThat(SUPERUSER.queryForObject(COUNT_ROWS, Integer.class, seeded.tenantId().value(),
                first.idempotencyKey())).isEqualTo(1);
        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, seeded.tenantId().value(),
                first.idempotencyKey())).isEqualTo(first.eventId().value());
    }

    @Test
    @DisplayName("an expired row, even one expiring exactly at receivedAt, is replaced by the new claim")
    void anExpiredRowIsReplaced() {
        Seeded seeded = seed();
        EventEnvelope boundary = envelopeFor(seeded).build();
        EventEnvelope expiredEarlier = envelopeFor(seeded).build();
        insertRow(seeded, boundary.idempotencyKey(), boundary.receivedAt().truncatedTo(ChronoUnit.MICROS));
        insertRow(seeded, expiredEarlier.idempotencyKey(),
                expiredEarlier.receivedAt().minusSeconds(ONE_SECOND));

        assertThat(claim(seeded, boundary)).isEmpty();
        assertThat(claim(seeded, expiredEarlier)).isEmpty();

        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, seeded.tenantId().value(),
                boundary.idempotencyKey())).isEqualTo(boundary.eventId().value());
        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, seeded.tenantId().value(),
                expiredEarlier.idempotencyKey())).isEqualTo(expiredEarlier.eventId().value());
    }

    @Test
    @DisplayName("a row that has not expired is kept and its event id is returned")
    void aLiveRowIsKept() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();
        insertRow(seeded, envelope.idempotencyKey(), envelope.receivedAt().plusSeconds(ONE_SECOND));

        Optional<EventId> original = claim(seeded, envelope);

        assertThat(original).contains(new EventId(OLD_EVENT_ID));
        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, seeded.tenantId().value(),
                envelope.idempotencyKey())).isEqualTo(OLD_EVENT_ID);
    }

    @Test
    @DisplayName("two tenants can claim the same key: neither claim sees the other's row")
    void tenantsCanClaimTheSameKey() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope forA = envelopeFor(tenantA).idempotencyKey("shared-key").build();
        EventEnvelope forB = envelopeFor(tenantB).idempotencyKey("shared-key").build();

        assertThat(claim(tenantA, forA)).isEmpty();
        assertThat(claim(tenantB, forB)).isEmpty();

        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, tenantA.tenantId().value(),
                "shared-key")).isEqualTo(forA.eventId().value());
        assertThat(SUPERUSER.queryForObject(SELECT_EVENT_ID, String.class, tenantB.tenantId().value(),
                "shared-key")).isEqualTo(forB.eventId().value());
    }

    @Test
    @DisplayName("row level security: as the app role, another tenant counting by key alone sees nothing")
    void anotherTenantCannotSeeTheRow() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope forA = envelopeFor(tenantA).build();
        assertThat(claim(tenantA, forA)).isEmpty();

        assertThat(countByKeyOnlyAs(tenantB, forA.idempotencyKey())).isZero();
        assertThat(countByKeyOnlyAs(tenantA, forA.idempotencyKey())).isEqualTo(1);
    }

    @Test
    @DisplayName("row level security: claiming an envelope of another tenant fails with 42501 and stores nothing")
    void claimingForAnotherTenantIsRejectedByTheDatabase() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        EventEnvelope forB = envelopeFor(tenantB).build();

        assertThatThrownBy(() -> runInTenant(tenantA.tenantId(), () -> repository.claimOrFindOriginal(forB)))
                .satisfies(thrown -> assertThat(sqlState(thrown)).isEqualTo(INSUFFICIENT_PRIVILEGE));

        assertThat(SUPERUSER.queryForObject(COUNT_ROWS, Integer.class, tenantB.tenantId().value(),
                forB.idempotencyKey())).isZero();
        assertThat(SUPERUSER.queryForObject(COUNT_ROWS, Integer.class, tenantA.tenantId().value(),
                forB.idempotencyKey())).isZero();
    }

    @Test
    @DisplayName("a rolled back transaction removes the claim, so the identical claim succeeds again")
    void aRollbackRemovesTheClaim() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();

        assertThatThrownBy(() -> runInTenant(seeded.tenantId(), () -> {
            repository.claimOrFindOriginal(envelope);
            throw new IllegalStateException("force a rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(SUPERUSER.queryForObject(COUNT_ROWS, Integer.class, seeded.tenantId().value(),
                envelope.idempotencyKey())).isZero();
        assertThat(claim(seeded, envelope)).isEmpty();
    }

    @Test
    @DisplayName("a claim without an active transaction is rejected")
    void aClaimWithoutATransactionIsRejected() {
        Seeded seeded = seed();
        EventEnvelope envelope = envelopeFor(seeded).build();

        assertThatThrownBy(() -> repository.claimOrFindOriginal(envelope))
                .isInstanceOf(TransactionRequiredException.class);

        assertThat(SUPERUSER.queryForObject(COUNT_ROWS, Integer.class, seeded.tenantId().value(),
                envelope.idempotencyKey())).isZero();
    }

    private void insertRow(Seeded seeded, String key, Instant expiresAt) {
        SUPERUSER.update(INSERT_ROW, seeded.tenantId().value(), key, OLD_EVENT_ID,
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }
}