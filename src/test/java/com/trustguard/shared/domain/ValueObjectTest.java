package com.trustguard.shared.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for all six shared domain value objects(rule 17.5) -> immutability via records; validation logic embedded in the compact
 * constructor. Organized by record via @Nested classes so a failure clearly identifiers which value object
 * broke, rather than being lost inside one large flat test class.
 */
 class ValueObjectTest {
    @Nested
    @DisplayName("TenantId")
    class TenantIdTest {

        @Test
        @DisplayName("accepts a well-formed UUID")
        void acceptsValidUuid() {
            String uuid = UUID.randomUUID().toString();
            TenantId tenantId = new TenantId(uuid);
            assertThat(tenantId.value()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("normalizes uppercase UUID to canonical lowercase")
        void normalizesUppercaseToLowercase() {
            String uuid = UUID.randomUUID().toString();
            TenantId tenantId = new TenantId(uuid.toUpperCase());
            assertThat(tenantId.value()).isEqualTo(uuid.toLowerCase());
        }

        @Test
        @DisplayName("rejects null value")
        void rejectsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TenantId(null))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects blank value")
        void rejectsBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TenantId("   "))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects malformed UUID")
        void rejectsMalformedUuid() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TenantId("not-a-uuid"))
                    .withMessageContaining("must be a valid UUID");
        }

        @Test
        @DisplayName("two instances with the same UUID are equal")
        void equalityByValue() {
            String uuid = UUID.randomUUID().toString();
            assertThat(new TenantId(uuid)).isEqualTo(new TenantId(uuid));
        }
    }

    @Nested
    @DisplayName("ProjectId")
    class ProjectIdTest {

        @Test
        @DisplayName("accepts a well-formed UUID")
        void acceptsValidUuid() {
            String uuid = UUID.randomUUID().toString();
            ProjectId projectId = new ProjectId(uuid);
            assertThat(projectId.value()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("normalizes uppercase UUID to canonical lowercase")
        void normalizesUppercaseToLowercase() {
            String uuid = UUID.randomUUID().toString();
            ProjectId projectId = new ProjectId(uuid.toUpperCase());
            assertThat(projectId.value()).isEqualTo(uuid.toLowerCase());
        }

        @Test
        @DisplayName("rejects null value")
        void rejectsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ProjectId(null))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects blank value")
        void rejectsBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ProjectId(""))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects malformed UUID")
        void rejectsMalformedUuid() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ProjectId("12345"))
                    .withMessageContaining("must be a valid UUID");
        }
    }

    @Nested
    @DisplayName("ActorId")
    class ActorIdTest {

        @Test
        @DisplayName("accepts a tenant-owned non-UUID string (e.g. an email-style identifier)")
        void acceptsArbitraryNonUuidString() {
            ActorId actorId = new ActorId("user_47281@tenant-internal-id");
            assertThat(actorId.value()).isEqualTo("user_47281@tenant-internal-id");
        }

        @Test
        @DisplayName("accepts a well-formed UUID too, since USER actorIds may coincidentally be UUIDs")
        void acceptsUuidAsOneValidForm() {
            String uuid = UUID.randomUUID().toString();
            assertThat(new ActorId(uuid).value()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("accepts a 64-character hex string, matching the ANONYMOUS HMAC digest format")
        void acceptsHexDigestFormat() {
            String hexDigest = "a".repeat(64);
            assertThat(new ActorId(hexDigest).value()).isEqualTo(hexDigest);
        }

        @Test
        @DisplayName("rejects null value")
        void rejectsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ActorId(null))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects blank value")
        void rejectsBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ActorId("  "))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects a value exceeding the maximum length")
        void rejectsExcessiveLength() {
            String tooLong = "a".repeat(257);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ActorId(tooLong))
                    .withMessageContaining("must not exceed 256 characters");
        }

        @Test
        @DisplayName("accepts a value at exactly the maximum length")
        void acceptsExactlyMaxLength() {
            String exactlyMax = "a".repeat(256);
            assertThat(new ActorId(exactlyMax).value()).hasSize(256);
        }
    }

    @Nested
    @DisplayName("CorrelationId")
    class CorrelationIdTest {

        @Test
        @DisplayName("accepts a well-formed UUID")
        void acceptsValidUuid() {
            String uuid = UUID.randomUUID().toString();
            assertThat(new CorrelationId(uuid).value()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("normalizes uppercase UUID to canonical lowercase")
        void normalizesUppercaseToLowercase() {
            String uuid = UUID.randomUUID().toString();
            assertThat(new CorrelationId(uuid.toUpperCase()).value()).isEqualTo(uuid.toLowerCase());
        }

        @Test
        @DisplayName("rejects null value")
        void rejectsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CorrelationId(null))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects malformed UUID")
        void rejectsMalformedUuid() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CorrelationId("xyz"))
                    .withMessageContaining("must be a valid UUID");
        }
    }

    @Nested
    @DisplayName("EventId")
    class EventIdTest {

        @Test
        @DisplayName("accepts a well-formed UUID")
        void acceptsValidUuid() {
            String uuid = UUID.randomUUID().toString();
            assertThat(new EventId(uuid).value()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("normalizes uppercase UUID to canonical lowercase")
        void normalizesUppercaseToLowercase() {
            String uuid = UUID.randomUUID().toString();
            assertThat(new EventId(uuid.toUpperCase()).value()).isEqualTo(uuid.toLowerCase());
        }

        @Test
        @DisplayName("rejects blank value")
        void rejectsBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EventId(""))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects malformed UUID")
        void rejectsMalformedUuid() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EventId("event-123"))
                    .withMessageContaining("must be a valid UUID");
        }
    }

    @Nested
    @DisplayName("KeyId")
    class KeyIdTest {

        @Test
        @DisplayName("accepts a base62-style opaque identifier")
        void acceptsOpaqueIdentifier() {
            String opaqueKeyId = "aZ9k3Lm2Qx7Vb1Nc8Rt4Wp6Yd0Ef5Hj";
            assertThat(new KeyId(opaqueKeyId).value()).isEqualTo(opaqueKeyId);
        }

        @Test
        @DisplayName("rejects null value")
        void rejectsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new KeyId(null))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects blank value")
        void rejectsBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new KeyId(" "))
                    .withMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("rejects a value exceeding the maximum length")
        void rejectsExcessiveLength() {
            String tooLong = "k".repeat(129);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new KeyId(tooLong))
                    .withMessageContaining("must not exceed 128 characters");
        }

        @Test
        @DisplayName("accepts a value at exactly the maximum length")
        void acceptsExactlyMaxLength() {
            String exactlyMax = "k".repeat(128);
            assertThat(new KeyId(exactlyMax).value()).hasSize(128);
        }
    }
}
