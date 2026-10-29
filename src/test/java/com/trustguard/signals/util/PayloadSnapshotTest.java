package com.trustguard.signals.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.signals.constants.SignalConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PayloadSnapshotTest {

    private static final String SECRET_KEY = "secret-key";
    private static final String BUDGET_MESSAGE = "Payload holds too many values";
    private static final int PAST_THE_BOUND = 2;

    /** Ruling 73: the node budget equals the canonical output limit. */
    private static final int NODE_BUDGET = SignalConstants.MAX_CANONICAL_CHARS;

    /** The root map and the list that holds the integers in a flat payload. */
    private static final int FLAT_PAYLOAD_CONTAINERS = 2;

    private static final int FAN_OUT_TIMEOUT_SECONDS = 10;
    private static final int VALID_LIST_SIZE = 16000;
    private static final int OVERSIZE_LIST_SIZE = 40000;

    // ---------- helpers ----------

    private static Map<String, Object> entry(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Object nested(int depth) {
        Object value = new ArrayList<Object>();
        for (int level = 1; level < depth; level++) {
            List<Object> outer = new ArrayList<>();
            outer.add(value);
            value = outer;
        }
        return value;
    }

    /** Containers nested like {@link #nested(int)}, but each level holds the previous one twice. */
    private static Object sharedFanOut(int containers) {
        Object value = new ArrayList<Object>();
        for (int level = 1; level < containers; level++) {
            List<Object> outer = new ArrayList<>();
            outer.add(value);
            outer.add(value);
            value = outer;
        }
        return value;
    }

    private static Map<String, Object> flatPayload(int integers) {
        return entry("n", Collections.nCopies(integers, 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static boolean snapshotAccepts(Map<String, Object> payload) {
        try {
            PayloadSnapshot.copyOf(payload);
            return true;
        } catch (InvalidEventPayloadException ex) {
            return false;
        }
    }

    private static boolean serializerAccepts(Map<String, Object> payload) {
        try {
            CanonicalJsonSerializer.serialize(payload);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void assertRejectedWithoutEcho(Map<?, ?> payload) {
        assertThatThrownBy(() -> PayloadSnapshot.copyOf(payload))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessageNotContaining(SECRET_KEY)
                .hasMessageNotContaining("AtomicInteger")
                .hasMessageNotContaining("java.");
    }

    // ---------- shape ----------

    @Test
    @DisplayName("a null payload becomes an unmodifiable empty map (Ruling 57)")
    void nullBecomesAnUnmodifiableEmptyMap() {
        Map<String, Object> snapshot = PayloadSnapshot.copyOf(null);

        assertThat(snapshot).isEmpty();
        assertThatThrownBy(() -> snapshot.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the snapshot equals the original in content but shares no container with it")
    void snapshotEqualsTheOriginalButSharesNoContainer() {
        Map<String, Object> inner = entry("deep", "v");
        List<Object> list = new ArrayList<>(Arrays.asList(1, "x", null));
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("m", inner);
        original.put("l", list);
        original.put("n", null);

        Map<String, Object> snapshot = PayloadSnapshot.copyOf(original);

        assertThat(snapshot).isEqualTo(original).isNotSameAs(original);
        assertThat(snapshot.get("m")).isEqualTo(inner).isNotSameAs(inner);
        assertThat(snapshot.get("l")).isEqualTo(list).isNotSameAs(list);
    }

    @Test
    @DisplayName("the root map cannot be modified")
    void rootMapIsUnmodifiable() {
        Map<String, Object> snapshot = PayloadSnapshot.copyOf(entry("k", "v"));

        assertThatThrownBy(() -> snapshot.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.remove("k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a nested map cannot be modified")
    void nestedMapIsUnmodifiable() {
        Map<String, Object> snapshot = PayloadSnapshot.copyOf(entry("m", entry("a", 1)));
        Map<String, Object> inner = asMap(snapshot.get("m"));

        assertThatThrownBy(() -> inner.put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> inner.remove("a")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a nested list cannot be modified")
    void nestedListIsUnmodifiable() {
        Map<String, Object> snapshot = PayloadSnapshot.copyOf(entry("l", new ArrayList<>(List.of("a"))));
        List<Object> list = asList(snapshot.get("l"));

        assertThatThrownBy(() -> list.add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.set(0, "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.remove(0)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an entry of the snapshot cannot be changed through the entry set")
    void entrySetEntriesAreUnmodifiable() {
        Map<String, Object> snapshot = PayloadSnapshot.copyOf(entry("k", "v"));
        Map.Entry<String, Object> only = snapshot.entrySet().iterator().next();

        assertThatThrownBy(() -> only.setValue("other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("changing the original after the copy does not change the snapshot")
    void changingTheOriginalAfterTheCopyDoesNotChangeTheSnapshot() {
        Map<String, Object> inner = entry("a", 1);
        List<Object> list = new ArrayList<>(List.of("x"));
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("m", inner);
        original.put("l", list);

        Map<String, Object> snapshot = PayloadSnapshot.copyOf(original);
        original.put("added", 1);
        inner.put("added", 2);
        list.add("y");

        assertThat(snapshot).containsOnlyKeys("m", "l");
        assertThat(asMap(snapshot.get("m"))).containsOnlyKeys("a");
        assertThat(asList(snapshot.get("l"))).containsExactly("x");
    }

    @Test
    @DisplayName("null values are kept in maps and lists, at every level")
    void nullValuesAreKept() {
        Map<String, Object> inner = entry("nothing", null);
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("n", null);
        original.put("m", inner);
        original.put("l", new ArrayList<>(Arrays.asList(null, "x", null)));

        Map<String, Object> snapshot = PayloadSnapshot.copyOf(original);

        assertThat(snapshot.containsKey("n")).isTrue();
        assertThat(snapshot.get("n")).isNull();
        assertThat(asMap(snapshot.get("m")).containsKey("nothing")).isTrue();
        assertThat(asList(snapshot.get("l"))).containsExactly(null, "x", null);
    }

    @Test
    @DisplayName("key insertion order does not change the snapshot or its canonical form")
    void keyInsertionOrderDoesNotMatter() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", List.of("x"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", List.of("x"));
        second.put("a", 1);

        Map<String, Object> firstSnapshot = PayloadSnapshot.copyOf(first);
        Map<String, Object> secondSnapshot = PayloadSnapshot.copyOf(second);

        assertThat(firstSnapshot).isEqualTo(secondSnapshot);
        assertThat(CanonicalJsonSerializer.serialize(firstSnapshot))
                .isEqualTo(CanonicalJsonSerializer.serialize(secondSnapshot))
                .isEqualTo(CanonicalJsonSerializer.serialize(first));
    }

    @Test
    @DisplayName("an unmodifiable input is accepted and copied")
    void unmodifiableInputIsAccepted() {
        Map<String, Object> input = Map.of("m", Map.of("a", 1), "l", List.of("x"));

        Map<String, Object> snapshot = PayloadSnapshot.copyOf(input);

        assertThat(snapshot).isEqualTo(input).isNotSameAs(input);
    }

    @Test
    @DisplayName("the same value used twice is not a cycle and is copied once per use")
    void aSharedValueIsNotACycle() {
        List<Object> shared = new ArrayList<>(List.of("x"));
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("a", shared);
        original.put("b", shared);

        Map<String, Object> snapshot = PayloadSnapshot.copyOf(original);

        assertThat(snapshot.get("a")).isEqualTo(shared).isNotSameAs(shared);
        assertThat(snapshot.get("b")).isEqualTo(shared).isNotSameAs(snapshot.get("a"));
    }

    // ---------- values ----------

    @Test
    @DisplayName("immutable values pass through as the same instance")
    void immutableValuesPassThroughUnchanged() {
        List<Object> values = List.of("text", true, 1, 1L, (short) 1, (byte) 1, BigInteger.TEN,
                new BigDecimal("1.50"), 1.5f, 1.5d);

        for (Object value : values) {
            Map<String, Object> snapshot = PayloadSnapshot.copyOf(entry("k", value));

            assertThat(snapshot.get("k")).as(value.getClass().getSimpleName()).isSameAs(value);
        }
    }

    @Test
    @DisplayName("only shape and type are checked, never values: NaN and infinity pass through")
    void valuesAreNotChecked() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("nan", Double.NaN);
        original.put("inf", Double.POSITIVE_INFINITY);

        assertThat(PayloadSnapshot.copyOf(original)).containsEntry("inf", Double.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("unsupported value types are rejected at every level, without echoing anything")
    void unsupportedValueTypesAreRejected() {
        List<Object> unsupported = List.of(new Object(), new AtomicInteger(1), new int[0], Set.of(1), 'c',
                new Date(0), Optional.empty(), new BigDecimal("1") { });

        for (Object value : unsupported) {
            assertRejectedWithoutEcho(entry(SECRET_KEY, value));
            assertRejectedWithoutEcho(entry("k", new ArrayList<>(List.of("ok", value))));
            assertRejectedWithoutEcho(entry("k", entry("inner", value)));
        }
    }

    @Test
    @DisplayName("object keys must be non-null strings")
    void nonStringKeysAreRejected() {
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, "x");

        assertRejectedWithoutEcho(Map.of(1, "x"));
        assertRejectedWithoutEcho(nullKey);
        assertRejectedWithoutEcho(entry("k", Map.of(1, "x")));
        assertRejectedWithoutEcho(entry("k", new ArrayList<>(List.of(nullKey))));
    }

    // ---------- depth and cycles ----------

    @Test
    @DisplayName("a self-referencing map is rejected, not a stack overflow")
    void selfReferencingMapIsRejected() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);

        assertThatThrownBy(() -> PayloadSnapshot.copyOf(cycle)).isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    @DisplayName("a self-referencing list is rejected, not a stack overflow")
    void selfReferencingListIsRejected() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        assertThatThrownBy(() -> PayloadSnapshot.copyOf(entry("l", cycle)))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    @DisplayName("32 containers pass and 33 are rejected, counting the root map")
    void depthBoundary() {
        int allowedInside = SignalConstants.MAX_NESTING_DEPTH - 1;

        assertThat(snapshotAccepts(entry("n", nested(allowedInside)))).isTrue();
        assertThat(snapshotAccepts(entry("n", nested(allowedInside + 1)))).isFalse();
    }

    @Test
    @DisplayName("the snapshot accepts exactly the depths the canonical serializer accepts")
    void depthAgreesWithTheSerializerAtEveryLevel() {
        for (int depth = 1; depth <= SignalConstants.MAX_NESTING_DEPTH + PAST_THE_BOUND; depth++) {
            Map<String, Object> payload = entry("n", nested(depth));

            assertThat(snapshotAccepts(payload)).as("nesting " + depth).isEqualTo(serializerAccepts(payload));
        }
    }

    @Test
    @DisplayName("a too-deep payload is rejected without echoing its keys")
    void tooDeepPayloadIsRejectedWithoutEchoingKeys() {
        assertRejectedWithoutEcho(entry(SECRET_KEY, nested(SignalConstants.MAX_NESTING_DEPTH)));
    }

    // ---------- node budget (Ruling 73) ----------

    @Test
    @DisplayName("a shared sub-object with fan-out 2 is stopped by the node budget, quickly")
    void fanOutIsStoppedByTheNodeBudget() {
        Map<String, Object> payload = entry("n", sharedFanOut(SignalConstants.MAX_NESTING_DEPTH - 1));

        assertTimeoutPreemptively(Duration.ofSeconds(FAN_OUT_TIMEOUT_SECONDS), () -> {
            assertThatThrownBy(() -> PayloadSnapshot.copyOf(payload))
                    .isInstanceOf(InvalidEventPayloadException.class)
                    .hasMessage(BUDGET_MESSAGE);
        });
    }

    @Test
    @DisplayName("a flat list of 40000 small integers is rejected by the budget, not by depth")
    void aFlatListOverTheBudgetIsRejected() {
        assertThatThrownBy(() -> PayloadSnapshot.copyOf(flatPayload(OVERSIZE_LIST_SIZE)))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessage(BUDGET_MESSAGE);
    }

    @Test
    @DisplayName("the budget is exactly 32768 values: the root map, the list and 32766 integers pass, one more fails")
    void theBudgetBoundaryIsExact() {
        int fits = NODE_BUDGET - FLAT_PAYLOAD_CONTAINERS;

        Map<String, Object> snapshot = PayloadSnapshot.copyOf(flatPayload(fits));

        assertThat(asList(snapshot.get("n"))).hasSize(fits);
        assertThatThrownBy(() -> PayloadSnapshot.copyOf(flatPayload(fits + 1)))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessage(BUDGET_MESSAGE);
    }

    @Test
    @DisplayName("a list of 16000 integers, within the canonical limit, is accepted")
    void aValidSizedListIsAccepted() {
        Map<String, Object> payload = flatPayload(VALID_LIST_SIZE);

        assertThat(CanonicalJsonSerializer.serialize(payload).length())
                .isLessThanOrEqualTo(SignalConstants.MAX_CANONICAL_CHARS);
        assertThat(asList(PayloadSnapshot.copyOf(payload).get("n"))).hasSize(VALID_LIST_SIZE);
    }

    @Test
    @DisplayName("the budget does not carry over between calls, even after a rejected one")
    void theBudgetDoesNotCarryOverBetweenCalls() {
        int fits = NODE_BUDGET - FLAT_PAYLOAD_CONTAINERS;

        assertThat(snapshotAccepts(flatPayload(fits))).isTrue();
        assertThat(snapshotAccepts(flatPayload(fits))).isTrue();
        assertThat(snapshotAccepts(flatPayload(OVERSIZE_LIST_SIZE))).isFalse();
        assertThat(snapshotAccepts(flatPayload(fits))).isTrue();
    }
}