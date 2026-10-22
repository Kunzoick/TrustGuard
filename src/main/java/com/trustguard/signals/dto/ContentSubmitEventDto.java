package com.trustguard.signals.dto;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;
import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.validation.ActorIdMatchesActorType;
import com.trustguard.signals.validation.ValidPayload;
import com.trustguard.signals.validation.WellFormedText;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A client CONTENT_SUBMIT event (Rule 6.1, Ruling 53). receivedAt in the JSON is silently
 * ignored; tenantId, eventId and eventType are not client fields. Constraints are repeated
 * on the sibling records, and a parameterized test guards parity.
 *
 * @param actorType         the actor kind
 * @param actorId           the tenant's actor id; required for USER and SERVICE, absent for ANONYMOUS
 * @param sessionId         the client session id
 * @param occurredAt        when the event happened on the client
 * @param sdkVersion        the sdk version string
 * @param environment       the deployment environment
 * @param ipAddress         the client ip address
 * @param userAgent         the client user agent
 * @param deviceFingerprint the client device fingerprint
 * @param payload           the event-specific payload
 * @param idempotencyKey    the tenant-supplied idempotency key
 * @param projectId         the project id, a UUID
 */
@JsonIgnoreProperties("receivedAt")
@ActorIdMatchesActorType
public record ContentSubmitEventDto(
        @NotNull ActorType actorType,
        @Size(min = 1, max = SignalConstants.MAX_TEXT_LENGTH) @WellFormedText String actorId,
        @Size(min = 1, max = SignalConstants.MAX_TEXT_LENGTH) @WellFormedText String sessionId,
        @NotNull Instant occurredAt,
        @NotBlank @Size(min = 1, max = SignalConstants.MAX_TEXT_LENGTH) @WellFormedText String sdkVersion,
        @NotNull Environment environment,
        @NotBlank @Size(min = 1, max = SignalConstants.MAX_IP_ADDRESS_LENGTH) @WellFormedText String ipAddress,
        @Size(min = 1, max = SignalConstants.MAX_USER_AGENT_LENGTH) @WellFormedText String userAgent,
        @Size(min = 1, max = SignalConstants.MAX_TEXT_LENGTH) @WellFormedText String deviceFingerprint,
        @ValidPayload Map<String, Object> payload,
        @Size(min = 1, max = SignalConstants.MAX_TEXT_LENGTH) @WellFormedText String idempotencyKey,
        @Pattern(regexp = SignalConstants.UUID_REGEX) String projectId)
        implements TrustEventDto {

    @JsonIgnore
    @Override
    public EventType eventType() {
        return EventType.CONTENT_SUBMIT;
    }

    /**
     * Redacted: only the actor type and environment, so a logged event never prints the
     * ip address, user agent, session id or payload (Rule 14.7).
     *
     * @return a short description without client data
     */
    @Override
    public String toString() {
        return "ContentSubmitEventDto[actorType=" + actorType + ", environment=" + environment + "]";
    }
}