package com.trustguard.signals.service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.IngestionContext;
import com.trustguard.signals.dto.LoginEventDto;
import com.trustguard.signals.mapper.EventEnvelopeMapper;
import com.trustguard.signals.parser.IngestionRequestParser;
import com.trustguard.signals.util.EventIdGenerator;
import com.trustguard.signals.validation.IngestionRequestGuard;
import com.trustguard.tenant.context.TenantContext;

/**
 * Runs one ingestion request from raw bytes to a result (Rules 3.3, 6.2 and 6.4, Rulings 109, 112 and 117). It is
 * deliberately not transactional and its name does not end in Service: the transaction lives in
 * {@link IngestionService#accept}, and this class acts on both sides of its commit.
 *
 * <p>Order: parse the body; read the receive time from the clock once; guard (environment, timestamp window);
 * resolve the project; generate the event id; map to the envelope; look the idempotency key up in Redis; accept in
 * one transaction; and only after that call has returned, so the transaction has committed, remember the key in
 * Redis. A failure before or inside accept throws before any Redis write, so a rolled back request leaves no Redis
 * key and an identical retry is accepted. Redis failures never reach here: {@link IdempotencyService} absorbs them.
 *
 * <p>The tenant context comes from the caller, which reads it from the holder; this class never creates one. A
 * duplicate found in PostgreSQL is not written back to Redis, so the Redis TTL cannot outlive the row's expires_at.
 */
@Component
public class IngestionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionCoordinator.class);
    private static final String FIELD_PROJECT_ID = "projectId";
    private static final String MESSAGE_VALIDATION_FAILED = "Request validation failed.";
    private static final String DETAIL_PROJECT_MISMATCH = "projectId does not match the API key's project";
    private static final String MESSAGE_DUPLICATE = "Duplicate event ignored. tenantId={} originalEventId={} "
            + "duplicateEventId={}";
    private final IngestionRequestParser parser;
    private final IngestionRequestGuard guard;
    private final EventEnvelopeMapper mapper;
    private final EventIdGenerator eventIds;
    private final Clock clock;
    private final IdempotencyService cache;
    private final IngestionService ingestion;

    /**
     * Creates the coordinator.
     *
     * @param parser    strict request parser
     * @param guard     environment and timestamp guard
     * @param mapper    envelope mapper
     * @param eventIds  event id generator
     * @param clock     the time source for receivedAt
     * @param cache     the Redis fast path
     * @param ingestion the transactional claim and store
     */
    public IngestionCoordinator(IngestionRequestParser parser, IngestionRequestGuard guard,
                                EventEnvelopeMapper mapper, EventIdGenerator eventIds, Clock clock,
                                IdempotencyService cache, IngestionService ingestion) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    }
    //Ingests one login event
    public IngestionResult acceptLogin(byte[] body, TenantContext tenant){
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(tenant, "tenant");
        LoginEventDto dto = parser.parse(body, LoginEventDto.class);
        Instant receivedAt = clock.instant();
        guard.check(dto, tenant, receivedAt);
        ProjectId projectId = resolveProject(dto, tenant);
        EventId eventId = eventIds.generate();
        EventEnvelope envelope = mapper.map(dto, new IngestionContext(tenant.tenantId(), projectId, eventId,
                receivedAt));
        Optional<EventId> cached= cache.find(tenant.tenantId(), envelope.idempotencyKey());
        if(cached.isPresent()){
            return duplicate(tenant, cached.get(), eventId);
        }
        IngestionResult result= ingestion.accept(envelope);
        if(result instanceof IngestionResult.Duplicate found){
            return duplicate(tenant, found.originalEventId(), eventId);
        }
        IngestionResult.Accepted accepted= (IngestionResult.Accepted) result;
        Duration ttl= IdempotencyService.remainingTtl(accepted.receivedAt(), clock.instant());
        cache.remember(tenant.tenantId(), envelope.idempotencyKey(), accepted.eventId(), ttl);
        return accepted;
    }
    private static ProjectId resolveProject(LoginEventDto dto,  TenantContext tenant){
        if(dto.projectId()== null){
            return tenant.projectId();
        }
        ProjectId requested= new ProjectId(dto.projectId());
        if(!requested.equals(tenant.projectId())){
            throw new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, MESSAGE_VALIDATION_FAILED,
                    List.of(new FieldDetail(FIELD_PROJECT_ID, DETAIL_PROJECT_MISMATCH)));
        }
        return requested;
    }
    private static IngestionResult.Duplicate duplicate(TenantContext tenant, EventId original, EventId discarded){
        LOG.info(MESSAGE_DUPLICATE, tenant.tenantId().value(), original.value(), discarded.value());
        return new IngestionResult.Duplicate(original);
    }
}
