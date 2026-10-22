package com.trustguard.signals.util;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final int MAX_DEPTH = 32;
    private static final int MAX_SCALE = 400;
    private static final int MAX_CHARS = 32768;
    private static final int AMPLIFIED_NUMBER_COUNT = 100;
    private static final int CONTROL_CHARACTER_COUNT = 6000;
    private static final int NUL_CHAR = 0x00;
    private static final int DEL_CHAR = 0x7f;

    // ---------- helpers ----------

    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<Object> listOf(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static String quoted(String inner) {
        return "\"" + inner + "\"";
    }

    private static void assertSerializes(Object value, String expected) {
        assertThat(CanonicalJsonSerializer.serialize(value)).isEqualTo(expected);
    }

    private static void assertRejected(Object value) {
        assertThatThrownBy(() -> CanonicalJsonSerializer.serialize(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertOutputLimitExceeded(Object value) {
        assertThatThrownBy(() -> CanonicalJsonSerializer.serialize(value))
                .isInstanceOf(CanonicalJsonSerializer.OutputLimitExceededException.class)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Object parse(String json) {
        return MAPPER.readValue(json, Object.class);
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

    private static Object shuffleKeys(Object value, Random random) {
        if (value instanceof Map<?, ?> map) {
            List<Object> keys = new ArrayList<>(map.keySet());
            Collections.shuffle(keys, random);
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Object key : keys) {
                copy.put((String) key, shuffleKeys(map.get(key), random));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(shuffleKeys(item, random));
            }
            return copy;
        }
        return value;
    }

    // ---------- structure ----------

    @Test
    @DisplayName("object keys are sorted at every nesting level and array order is kept")
    void keysAreSortedAtEveryLevel() {
        Map<String, Object> value = mapOf("b", mapOf("z", 1, "y", 2), "a", listOf(3, 2, 1));

        assertSerializes(value, "{\"a\":[3,2,1],\"b\":{\"y\":2,\"z\":1}}");
    }

    @Test
    @DisplayName("nulls are written, empty containers are compact")
    void nullsAndEmptyContainers() {
        assertSerializes(mapOf("k", null, "j", listOf(null, true, false)),
                "{\"j\":[null,true,false],\"k\":null}");
        assertSerializes(mapOf("a", mapOf(), "b", listOf()), "{\"a\":{},\"b\":[]}");
    }

    @Test
    @DisplayName("root scalars serialize as themselves")
    void rootScalars() {
        assertSerializes(null, "null");
        assertSerializes("x", "\"x\"");
        assertSerializes(true, "true");
        assertSerializes(false, "false");
        assertSerializes(7, "7");
    }

    @Test
    @DisplayName("keys use UTF-16 code unit order, not code point order")
    void keysUseUtf16Order() {
        String emoji = new String(Character.toChars(0x1F600));
        String halfwidth = String.valueOf((char) 0xFFEE);

        assertSerializes(mapOf(halfwidth, 2, emoji, 1),
                "{" + quoted(emoji) + ":1," + quoted(halfwidth) + ":2}");
    }

    // ---------- numbers ----------

    @Test
    @DisplayName("1, 1.0, 1.00 and 1E0 in every numeric type all become 1")
    void equalNumbersCollapse() {
        List<Object> ones = listOf(1, 1L, (short) 1, (byte) 1, 1.0, 1.0f,
                new BigDecimal("1.00"), new BigDecimal("1E0"), BigInteger.ONE,
                new BigDecimal("1.000000000000000000000"));

        for (Object one : ones) {
            assertThat(CanonicalJsonSerializer.serialize(one)).as(one.getClass().getSimpleName()).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("other numbers use plain notation without trailing zeros, and negative zero is 0")
    void otherNumbersUsePlainNotation() {
        assertSerializes(new BigDecimal("0.10"), "0.1");
        assertSerializes(new BigDecimal("12.340"), "12.34");
        assertSerializes(new BigDecimal("1E+2"), "100");
        assertSerializes(new BigDecimal("1000"), "1000");
        assertSerializes(100.0, "100");
        assertSerializes(-0.0, "0");
        assertSerializes(new BigDecimal("-0.00"), "0");
        assertSerializes(new BigDecimal("-1.50"), "-1.5");
        assertSerializes(new BigInteger("123456789012345678901234567890"), "123456789012345678901234567890");
        assertSerializes(Long.MIN_VALUE, "-9223372036854775808");
    }

    @Test
    @DisplayName("doubles use their shortest decimal form and floats are not widened to double")
    void doubleAndFloatUseShortestForm() {
        assertSerializes(0.1, "0.1");
        assertSerializes(0.1f, "0.1");
        assertSerializes(1.0E-5, "0.00001");
        assertSerializes(1.0E21, "1" + "0".repeat(21));
    }

    @Test
    @DisplayName("NaN and infinities are rejected")
    void nonFiniteNumbersAreRejected() {
        assertRejected(Double.NaN);
        assertRejected(Double.POSITIVE_INFINITY);
        assertRejected(Double.NEGATIVE_INFINITY);
        assertRejected(Float.NaN);
        assertRejected(Float.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("every finite double and scale 400 pass, measured after trailing zeros are stripped")
    void scaleBoundAcceptsLegalNumbers() {
        assertThat(CanonicalJsonSerializer.serialize(4.9E-324)).startsWith("0.000").endsWith("49");
        assertThat(CanonicalJsonSerializer.serialize(Double.MAX_VALUE)).hasSize(309);
        assertThat(CanonicalJsonSerializer.serialize(new BigDecimal("1E" + MAX_SCALE))).hasSize(MAX_SCALE + 1);
        assertThat(CanonicalJsonSerializer.serialize(new BigDecimal("10E" + (MAX_SCALE - 1))))
                .hasSize(MAX_SCALE + 1);
        assertThat(CanonicalJsonSerializer.serialize(new BigDecimal("1E-" + MAX_SCALE))).startsWith("0.");
    }

    @Test
    @DisplayName("scale 401 is rejected on both sides")
    void scaleBoundRejectsHugeExponents() {
        assertRejected(new BigDecimal("1E" + (MAX_SCALE + 1)));
        assertRejected(new BigDecimal("1E-" + (MAX_SCALE + 1)));
    }

    // ---------- strings ----------

    @Test
    @DisplayName("quote, backslash, backspace, tab, newline, form feed and carriage return use shorthand escapes")
    void shorthandEscapes() {
        assertSerializes("\"", quoted("\\\""));
        assertSerializes("\\", quoted("\\\\"));
        assertSerializes("\b", quoted("\\b"));
        assertSerializes("\t", quoted("\\t"));
        assertSerializes("\n", quoted("\\n"));
        assertSerializes("\f", quoted("\\f"));
        assertSerializes("\r", quoted("\\r"));
    }

    @Test
    @DisplayName("other control characters use lowercase unicode escapes")
    void controlCharactersUseLowercaseEscapes() {
        assertSerializes(String.valueOf((char) NUL_CHAR), quoted("\\u0000"));
        assertSerializes(String.valueOf((char) 0x01), quoted("\\u0001"));
        assertSerializes(String.valueOf((char) 0x0b), quoted("\\u000b"));
        assertSerializes(String.valueOf((char) 0x1f), quoted("\\u001f"));
    }

    @Test
    @DisplayName("slash, DEL and non-ASCII are written raw, and bytes are UTF-8")
    void rawCharactersAndUtf8Bytes() {
        assertSerializes("a/b", quoted("a/b"));
        assertSerializes(String.valueOf((char) DEL_CHAR), quoted(String.valueOf((char) DEL_CHAR)));
        assertSerializes("\u00e9", quoted("\u00e9"));
        assertThat(CanonicalJsonSerializer.serializeToBytes("\u00e9"))
                .containsExactly((byte) 0x22, (byte) 0xC3, (byte) 0xA9, (byte) 0x22);
    }

    @Test
    @DisplayName("a valid surrogate pair is written raw as four UTF-8 bytes")
    void validSurrogatePairIsRaw() {
        String emoji = new String(Character.toChars(0x1F600));

        assertSerializes(emoji, quoted(emoji));
        assertThat(CanonicalJsonSerializer.serializeToBytes(emoji))
                .containsExactly((byte) 0x22, (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80, (byte) 0x22);
    }

    @Test
    @DisplayName("lone and reversed surrogates are escaped in lowercase")
    void loneSurrogatesAreEscaped() {
        assertSerializes(String.valueOf((char) 0xD800), quoted("\\ud800"));
        assertSerializes(String.valueOf((char) 0xDC00), quoted("\\udc00"));
        assertSerializes(String.valueOf((char) 0xDBFF), quoted("\\udbff"));
        assertSerializes(String.valueOf((char) 0xD800) + "a", quoted("\\ud800a"));
        assertSerializes(String.valueOf((char) 0xDC00) + (char) 0xD800, quoted("\\udc00\\ud800"));
    }

    // ---------- rejection ----------

    @Test
    @DisplayName("unsupported values and non-string keys are rejected without echoing input")
    void unsupportedValuesAreRejected() {
        assertRejected(new Object());
        assertRejected(Set.of(1));
        assertRejected(new AtomicInteger(1));
        assertRejected('c');
        assertRejected(new int[0]);
        assertRejected(Map.of(1, "a"));
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, "a");
        assertRejected(nullKey);
        assertThatThrownBy(() -> CanonicalJsonSerializer.serialize(mapOf("secret-key", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-key");
    }

    @Test
    @DisplayName("nesting of 32 containers passes and 33 is rejected")
    void nestingDepthBound() {
        assertThat(CanonicalJsonSerializer.serialize(nested(MAX_DEPTH))).hasSize(MAX_DEPTH * 2);
        assertRejected(nested(MAX_DEPTH + 1));
    }

    // ---------- output limit (Ruling 61) ----------

    @Test
    @DisplayName("output of exactly 32768 characters passes and 32769 is rejected")
    void outputLimitBoundary() {
        String atLimit = "a".repeat(MAX_CHARS - 2);
        String overLimit = "a".repeat(MAX_CHARS - 1);

        assertThat(CanonicalJsonSerializer.serialize(atLimit)).hasSize(MAX_CHARS);
        assertOutputLimitExceeded(overLimit);
    }

    @Test
    @DisplayName("many amplified numbers are rejected with OutputLimitExceededException")
    void amplifiedNumbersAreRejected() {
        List<Object> amplified = new ArrayList<>();
        for (int i = 0; i < AMPLIFIED_NUMBER_COUNT; i++) {
            amplified.add(new BigDecimal("1E" + MAX_SCALE));
        }

        assertOutputLimitExceeded(amplified);
    }

    @Test
    @DisplayName("escaped control characters count towards the limit as they grow")
    void escapedControlCharactersCountTowardsTheLimit() {
        String controls = String.valueOf((char) 0x01).repeat(CONTROL_CHARACTER_COUNT);

        assertOutputLimitExceeded(controls);
    }

    // ---------- shared vectors (Ruling 59) ----------

    @Test
    @DisplayName("every vector in the shared vectors file serializes to its expected string")
    void sharedVectorsFileMatches() throws IOException {
        try (InputStream in = CanonicalJsonTest.class.getResourceAsStream("canonical-json-vectors.json")) {
            assertThat(in).as("canonical-json-vectors.json on the test classpath").isNotNull();
            Map<?, ?> file = (Map<?, ?>) MAPPER.readValue(in, Object.class);
            List<?> vectors = (List<?>) file.get("vectors");
            assertThat(vectors).isNotEmpty();
            for (Object entry : vectors) {
                Map<?, ?> vector = (Map<?, ?>) entry;
                assertThat(CanonicalJsonSerializer.serialize(vector.get("input")))
                        .as("vector: %s", vector.get("name"))
                        .isEqualTo(vector.get("expected"));
            }
        }
    }

    // ---------- properties (jqwik) ----------

    @Provide
    Arbitrary<Map<String, Object>> payloads() {
        Arbitrary<String> text = Arbitraries.strings()
                .withCharRange('\u0000', '\u007e')
                .withCharRange('\u00a0', '\ud7ff')
                .ofMaxLength(8);
        Arbitrary<Object> integers = Arbitraries.integers().map(value -> (Object) value);
        Arbitrary<Object> decimals = Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(-1_000_000), BigDecimal.valueOf(1_000_000))
                .ofScale(3)
                .map(value -> (Object) value);
        Arbitrary<Object> strings = text.map(value -> (Object) value);
        Arbitrary<Object> flags = Arbitraries.of(true, false).map(value -> (Object) value);
        Arbitrary<Object> leaf = Arbitraries.oneOf(integers, decimals, strings, flags).injectNull(0.1);
        Arbitrary<Object> list = leaf.list().ofMaxSize(4).map(value -> (Object) value);
        Arbitrary<Object> value = Arbitraries.oneOf(leaf, list);
        return Arbitraries.maps(text, value).ofMaxSize(5);
    }

    @Property
    void keyOrderNeverChangesTheOutput(@ForAll("payloads") Map<String, Object> payload, @ForAll int seed) {
        Object shuffled = shuffleKeys(payload, new Random(seed));

        assertThat(CanonicalJsonSerializer.serialize(shuffled))
                .isEqualTo(CanonicalJsonSerializer.serialize(payload));
    }

    @Property
    void canonicalizingTwiceChangesNothing(@ForAll("payloads") Map<String, Object> payload) {
        String once = CanonicalJsonSerializer.serialize(payload);

        String twice = CanonicalJsonSerializer.serialize(parse(once));

        assertThat(twice).isEqualTo(once);
    }
}