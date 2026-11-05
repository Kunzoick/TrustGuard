package com.trustguard.signals.service;

import java.time.Instant;

import com.trustguard.shared.domain.EventId;

/**
 * The result of storing an event: its id and the receive time exactly as stored. PostgreSQL keeps
 * microseconds, so receivedAt is already truncated to microseconds (Ruling 105).
 *
 * @param eventId    the stored event's id
 * @param receivedAt the stored receive time, at microsecond precision
 */
public record PersistedEvent(EventId eventId, Instant receivedAt) {
}