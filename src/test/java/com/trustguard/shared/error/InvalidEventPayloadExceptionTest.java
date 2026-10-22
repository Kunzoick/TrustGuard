package com.trustguard.shared.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.shared.enums.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidEventPayloadExceptionTest {

    @Test
    @DisplayName("carries VALIDATION_FAILED, is not retryable and keeps its message")
    void carriesCodeAndMessage() {
        InvalidEventPayloadException exception = new InvalidEventPayloadException("bad payload");

        assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.retryable()).isFalse();
        assertThat(exception.getMessage()).isEqualTo("bad payload");
        assertThat(exception).isInstanceOf(TrustGuardException.class);
    }

    @Test
    @DisplayName("keeps a wrapped cause and the same code")
    void keepsCause() {
        IllegalArgumentException cause = new IllegalArgumentException("inner");

        InvalidEventPayloadException exception = new InvalidEventPayloadException("bad payload", cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.retryable()).isFalse();
    }
}