package com.trustguard.signals.service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.trustguard.shared.error.EventPersistenceException;
import com.trustguard.shared.error.TenantContextMissingException;
import com.trustguard.signals.SignalsIntegrationTestBase;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.repository.OutboxRepository;
import com.trustguard.signals.repository.TrustEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionServiceTransactionTest extends SignalsIntegrationTestBase {

    private static final String MESSAGE_PERSIST_FAILED = "The event could not be stored.";
    private static final String MESSAGE_TENANT_MISMATCH = "The event does not belong to the authenticated tenant.";
    private static final String BLOCKING_CONSTRAINT = "zz_b010c_block_outbox";
    private static final int EXTRA_NANOS = 123;
    private static final long BEYOND_ALL_PARTITIONS_DAYS = 100;

    private static final List<String> FORBIDDEN_TYPE_PREFIXES = List.of("org.springframework.amqp", "com.rabbitmq",
            "org.springframework.data.redis", "io.lettuce", "redis.clients");

    @Autowired
    private IngestionService service;

    @Autowired
    private org.springframework.core.env.Environment springEnvironment;

    // ---------- the write path ----------

    @Test
    @DisplayName("persist writes the event and exactly one outbox row while Redis and RabbitMQ are unreachable")
    void persistWritesBothRowsWhileTheBrokerIsUnreachable() {
        Seeded seeded = seed();
        authenticateAs(seeded);
        EventEnvelope envelope = envelopeFor(seeded).build();

        PersistedEvent persisted = service.persist(envelope);

        assertThat(springEnvironment.getProperty("spring.rabbitmq.port")).isEqualTo("1");
        assertThat(springEnvironment.getProperty("spring.data.redis.port")).isEqualTo("1");
        assertThat(persisted.eventId()).isEqualTo(envelope.eventId());
        assertThat(countEvents(envelope.eventId().value())).isEqualTo(1);
        assertThat(countOutbox(envelope.eventId().value())).isEqualTo(1);
        assertThat(SUPERUSER.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?::uuid",
                String.class, envelope.eventId().value())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("persist returns the receive time as stored, truncated to microseconds")
    void persistReturnsTheStoredMicrosecondTime() {
        Seeded seeded = seed();
        authenticateAs(seeded);
        Instant withNanos = dbNow().plusNanos(EXTRA_NANOS);
        EventEnvelope envelope = envelopeFor(seeded).receivedAt(withNanos).build();

        PersistedEvent persisted = service.persist(envelope);

        assertThat(persisted.receivedAt()).isEqualTo(withNanos.truncatedTo(ChronoUnit.MICROS))
                .isNotEqualTo(withNanos);
    }

    @Test
    @DisplayName("the service is a proxied, non-final Service with a method-level REQUIRED transaction")
    void theServiceIsTransactionalAtMethodLevel() throws NoSuchMethodException {
        Transactional onMethod = IngestionService.class.getMethod("persist", EventEnvelope.class)
                .getAnnotation(Transactional.class);

        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(Modifier.isFinal(IngestionService.class.getModifiers())).isFalse();
        assertThat(IngestionService.class.getSimpleName()).endsWith("Service");
        assertThat(IngestionService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(onMethod).isNotNull();
        assertThat(onMethod.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    @DisplayName("inside the transaction the role is trustguard_app, which is neither a superuser nor BYPASSRLS")
    void theDatabaseRoleIsTheAppRole() {
        JdbcTemplate app = new JdbcTemplate(dataSource);

        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(app.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class)).isFalse();
    }

    // ---------- rollback, both directions ----------

    @Test
    @DisplayName("a failing outbox insert, rejected by the database, leaves no event row")
    void aFailingOutboxInsertLeavesNoEventRow() {
        Seeded seeded = seed();
        authenticateAs(seeded);
        EventEnvelope envelope = envelopeFor(seeded).build();
        SUPERUSER.execute("ALTER TABLE outbox_events ADD CONSTRAINT " + BLOCKING_CONSTRAINT
                + " CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> service.persist(envelope))
                    .isInstanceOfSatisfying(EventPersistenceException.class, thrown -> {
                        assertThat(thrown.getMessage()).isEqualTo(MESSAGE_PERSIST_FAILED);
                        assertThat(thrown.getCause()).isNull();
                        assertThat(thrown.retryable()).isFalse();
                    });
        } finally {
            SUPERUSER.execute("ALTER TABLE outbox_events DROP CONSTRAINT " + BLOCKING_CONSTRAINT);
        }

        assertThat(countEvents(envelope.eventId().value())).isZero();
        assertThat(countOutbox(envelope.eventId().value())).isZero();
    }

    @Test
    @DisplayName("a failing event insert, rejected by the composite foreign key, leaves no outbox row")
    void aFailingEventInsertLeavesNoOutboxRow() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        authenticateAs(tenantA);
        EventEnvelope crossed = envelopeFor(tenantA).projectId(tenantB.projectId()).build();

        assertThatThrownBy(() -> service.persist(crossed))
                .isInstanceOfSatisfying(EventPersistenceException.class, thrown -> {
                    assertThat(thrown.getMessage()).isEqualTo(MESSAGE_PERSIST_FAILED);
                    assertThat(thrown.getCause()).isNull();
                });

        assertThat(countEvents(crossed.eventId().value())).isZero();
        assertThat(countOutbox(crossed.eventId().value())).isZero();
    }

    // ---------- isolation ----------

    @Test
    @DisplayName("an envelope of another tenant is rejected by the service, with nothing stored")
    void anEnvelopeOfAnotherTenantIsRejectedByTheService() {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        authenticateAs(tenantA);
        EventEnvelope forB = envelopeFor(tenantB).build();

        assertThatThrownBy(() -> service.persist(forB))
                .isInstanceOfSatisfying(EventPersistenceException.class, thrown -> {
                    assertThat(thrown.getMessage()).isEqualTo(MESSAGE_TENANT_MISMATCH);
                    assertThat(thrown.getCause()).isNull();
                    assertThat(thrown.retryable()).isFalse();
                });

        assertThat(countEvents(forB.eventId().value())).isZero();
        assertThat(countOutbox(forB.eventId().value())).isZero();
    }

    @Test
    @DisplayName("persist without a tenant context throws TenantContextMissingException and stores nothing")
    void persistWithoutATenantContextIsRejected() {
        Seeded seeded = seed();
        // The base class @AfterEach clears the context, and we haven't authenticated yet here.
        EventEnvelope envelope = envelopeFor(seeded).build();

        assertThatThrownBy(() -> service.persist(envelope)).isInstanceOf(TenantContextMissingException.class);

        assertThat(countEvents(envelope.eventId().value())).isZero();
        assertThat(countOutbox(envelope.eventId().value())).isZero();
    }

    // ---------- partitions ----------

    @Test
    @DisplayName("an event received beyond the last partition fails and leaves no outbox row")
    void anEventBeyondTheLastPartitionFailsAndLeavesNoOutboxRow() {
        Seeded seeded = seed();
        authenticateAs(seeded);
        Instant beyond = dbNow().plus(Duration.ofDays(BEYOND_ALL_PARTITIONS_DAYS));
        EventEnvelope envelope = envelopeFor(seeded).receivedAt(beyond).build();

        assertThatThrownBy(() -> service.persist(envelope))
                .isInstanceOfSatisfying(EventPersistenceException.class, thrown -> {
                    assertThat(thrown.getMessage()).isEqualTo(MESSAGE_PERSIST_FAILED);
                    assertThat(thrown.getCause()).isNull();
                    assertThat(thrown.retryable()).isFalse();
                });

        assertThat(countEvents(envelope.eventId().value())).isZero();
        assertThat(countOutbox(envelope.eventId().value())).isZero();
    }

    // ---------- no broker or cache in the write path (Ruling 100) ----------

    @Test
    @DisplayName("no AMQP, Redis or connection factory type appears in a field or constructor of the write path")
    void noBrokerOrCacheTypeInTheWritePath() {
        for (Class<?> type : List.of(IngestionService.class, TrustEventRepository.class, OutboxRepository.class)) {
            for (String name : dependencyTypes(type)) {
                assertThat(name).as(type.getSimpleName() + " dependency " + name)
                        .satisfies(value -> FORBIDDEN_TYPE_PREFIXES
                                .forEach(prefix -> assertThat(value).doesNotStartWith(prefix)))
                        .doesNotContain("ConnectionFactory");
            }
        }
    }

    private static List<String> dependencyTypes(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        return names;
    }
}