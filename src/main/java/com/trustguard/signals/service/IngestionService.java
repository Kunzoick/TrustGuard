package com.trustguard.signals.service;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.trustguard.shared.error.EventPersistenceException;
import com.trustguard.shared.error.TenantContextMissingException;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.OutboxMessage;
import com.trustguard.signals.repository.OutboxRepository;
import com.trustguard.signals.repository.TrustEventRepository;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;

/**
 * Stores an accepted event and its outbox row in one transaction (Rules 3.3 and 4.1, Rulings 100 and 102).
 * The class name ends in Service and persist is @Transactional at method level, so TenantRlsAspect sets
 * app.tenant_id inside the transaction before this method body runs. The class is not final, so Spring can
 * proxy it.
 *
 * <p>persist inserts the trust_events row first and exactly one outbox_events row second. Either failing rolls
 * both back, so there is no ghost event and no orphan outbox row. There is no RabbitMQ or Redis dependency
 * here: if the broker is down, the outbox row stays PENDING for the poller (B-011).
 *
 * <p>An envelope whose tenant differs from the authenticated TenantContext is rejected before any SQL, with a
 * fixed message; row level security and the composite foreign key are the backstops (Ruling 102). A
 * {@link DataAccessException} from the repositories becomes an {@link EventPersistenceException} with a fixed
 * message and no cause, because PostgreSQL error text quotes the rejected row (Rule 14.7). It is retryable only
 * when the failure was transient.
 */
@Service
public class IngestionService {
    private static final String MESSAGE_NO_TENANT_CONTEXT = "No tenant context is available for this request.";
    private static final String MESSAGE_TENANT_MISMATCH = "The event does not belong to the authenticated tenant.";
    private static final String MESSAGE_PERSIST_FAILED = "The event could not be stored.";
    private final TrustEventRepository events;
    private final OutboxRepository outbox;

    //creates the service
    public IngestionService(TrustEventRepository events, OutboxRepository outbox){
        this.events= Objects.requireNonNull(events, "events");
        this.outbox= Objects.requireNonNull(outbox, "outbox");
    }
    //stores the event and its outbox row in one transaction
    @Transactional(propagation = Propagation.REQUIRED)
    public PersistedEvent persist(EventEnvelope envelope){
        Objects.requireNonNull(envelope, "envelope");
        requireSameTenant(envelope);
        try{
            events.insert(envelope);
            outbox.insert(OutboxMessage.from(envelope));
        }catch (DataAccessException ex){
            throw new EventPersistenceException(MESSAGE_PERSIST_FAILED, ex instanceof TransientDataAccessException);
        }
        return new PersistedEvent(envelope.eventId(), envelope.receivedAt().truncatedTo(ChronoUnit.MICROS));
    }
    private static void requireSameTenant(EventEnvelope envelope){
        TenantContext context= TenantContextHolder.get();
        if(context== null){
            throw new TenantContextMissingException(MESSAGE_NO_TENANT_CONTEXT);
        }
        if(!context.tenantId().equals(envelope.tenantId())){
            throw new EventPersistenceException(MESSAGE_TENANT_MISMATCH, false);
        }
    }
}
