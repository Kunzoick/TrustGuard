package com.trustguard.signals.domain;
import java.time.Instant;
import java.util.Objects;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;

/**
 * The platform-owned facts about one ingested event (Rule 6.1, Ruling 70). B-010 builds it: it
 * resolves and validates the project, generates the event id and stamps the receive time. The
 * mapper takes tenant, project, event id and receivedAt only from here, never from the client.
 *
 * @param tenantId   the authenticated tenant
 * @param projectId  the project the event belongs to, already resolved and validated
 * @param eventId    the platform-generated event id
 * @param receivedAt when the platform received the event
 */
public record IngestionContext(TenantId tenantId, ProjectId projectId, EventId eventId, Instant receivedAt) {
    //rejects a null component. Messages name the component only
    public IngestionContext{
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
