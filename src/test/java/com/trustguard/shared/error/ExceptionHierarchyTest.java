package com.trustguard.shared.error;

import com.trustguard.shared.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the shared code()/retryable() accessor wiring in
 * TrustGuardException and TrustGuardInfrastructureException works
 * correctly (CF-002). Scoped per Agent 4's ruling: this does not test
 * every one of the eleven typed subtypes individually — it proves the
 * base class contract through a representative sample, plus a direct
 * check on OutboxPublishException since CF-001 just changed its wiring.
 */
class ExceptionHierarchyTest {

    @Test
    @DisplayName("a TrustGuardException subtype carries its ErrorCode and retryable flag")
    void trustGuardExceptionCarriesCodeAndRetryable() {
        ActorNotFoundException exception = new ActorNotFoundException("actor not found");

        assertThat(exception.code()).isEqualTo(ErrorCode.ACTOR_NOT_FOUND);
        assertThat(exception.retryable()).isFalse();
        assertThat(exception.getMessage()).isEqualTo("actor not found");
    }

    @Test
    @DisplayName("a TrustGuardException subtype preserves a wrapped cause")
    void trustGuardExceptionPreservesCause() {
        RuntimeException cause = new RuntimeException("root cause");
        CapabilityInsufficientException exception =
                new CapabilityInsufficientException("capability check failed", cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.code()).isEqualTo(ErrorCode.CAPABILITY_INSUFFICIENT);
    }

    @Test
    @DisplayName("a TrustGuardInfrastructureException subtype carries its ErrorCode and retryable flag")
    void trustGuardInfrastructureExceptionCarriesCodeAndRetryable() {
        RedisUnavailableException exception = new RedisUnavailableException("redis unreachable");

        assertThat(exception.code()).isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
        assertThat(exception.retryable()).isTrue();
    }

    @Test
    @DisplayName("a TrustGuardInfrastructureException subtype preserves a wrapped cause")
    void trustGuardInfrastructureExceptionPreservesCause() {
        RuntimeException cause = new RuntimeException("connection refused");
        ActorStateBuildException exception =
                new ActorStateBuildException("rebuild failed", cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.retryable()).isTrue();
    }

    @Test
    @DisplayName("OutboxPublishException uses OUTBOX_PUBLISH_FAILED after the CF-001 fix, and is retryable")
    void outboxPublishExceptionUsesNewErrorCode() {
        OutboxPublishException exception = new OutboxPublishException("publish failed after 5 attempts");

        assertThat(exception.code()).isEqualTo(ErrorCode.OUTBOX_PUBLISH_FAILED);
        assertThat(exception.retryable()).isTrue();
    }

    @Test
    @DisplayName("OutboxPublishException with a cause still resolves to OUTBOX_PUBLISH_FAILED")
    void outboxPublishExceptionWithCauseUsesNewErrorCode() {
        RuntimeException cause = new RuntimeException("broker unreachable");
        OutboxPublishException exception = new OutboxPublishException("publish failed", cause);

        assertThat(exception.code()).isEqualTo(ErrorCode.OUTBOX_PUBLISH_FAILED);
        assertThat(exception.getCause()).isSameAs(cause);
    }
}