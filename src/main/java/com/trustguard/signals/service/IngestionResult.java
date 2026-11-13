package com.trustguard.signals.service;
import java.time.Instant;
import java.util.Objects;

import com.trustguard.shared.domain.EventId;

/**
 * The outcome of accepting an event (Rule 6.4): it was stored, or an earlier event with the same idempotency
 * key already exists.
 */
public sealed interface IngestionResult {
    //the event and its outbox row were stored
    record Accepted(EventId eventId, Instant receivedAt) implements IngestionResult{
        //rejects a missing component
            public Accepted{
                Objects.requireNonNull(eventId, "eventId");
                Objects.requireNonNull(receivedAt, "receivedAt");
            }
        }
        //nothing was stored: the idempotency key ws already claimed
    record Duplicate(EventId originalEventId) implements IngestionResult{
            public Duplicate{
                Objects.requireNonNull(originalEventId, "originalEventId");
            }
        }
}
