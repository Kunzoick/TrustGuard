package com.trustguard.shared.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ErrorCode registry's structural integrity (CF-002): every
 * constant has a usable description, and the total count matches the
 * expected 30 codes following CF-001's addition of
 * OUTBOX_PUBLISH_FAILED. The count check is intentionally an exact
 * number rather than a lower bound — per Rule 21.3, codes are never
 * removed once published, so an exact count catches both an accidental
 * removal and an undocumented silent addition in a future batch.
 */
class ErrorCodeTest {

    @Test
    @DisplayName("every ErrorCode constant has a non-null, non-blank description")
    void everyErrorCodeHasADescription() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.description())
                    .as("description for %s", code.name())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("registry contains exactly 30 codes after CF-001 added OUTBOX_PUBLISH_FAILED")
    void registryContainsExpectedCount() {
        assertThat(ErrorCode.values()).hasSize(30);
    }

    @Test
    @DisplayName("OUTBOX_PUBLISH_FAILED exists and describes an outbox/RabbitMQ publish failure")
    void outboxPublishFailedCodeExists() {
        assertThat(ErrorCode.OUTBOX_PUBLISH_FAILED.description())
                .containsIgnoringCase("outbox")
                .containsIgnoringCase("rabbitmq");
    }
}