package com.trustguard.signals.util;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.trustguard.signals.constants.SignalConstants;

/**
 * Canonical JSON serialization (Rule 6.4, Rulings 56, 61 and A/B). Produces one
 * deterministic string for any logically equal value, so equal payloads hash
 * to equal idempotency keys.
 *
 * <p>Rules:
 * <ul>
 *   <li>Object keys sorted in {@link String} natural order (UTF-16 code
 *       units, the same as JavaScript's default sort), at every level.</li>
 *   <li>Arrays keep their order. Nulls are written. No whitespace.</li>
 *   <li>Every number is {@code stripTrailingZeros().toPlainString()}, so 1,
 *       1.0, 1.00 and 1E0 all become {@code 1} and -0.0 becomes {@code 0}.
 *       NaN, infinities and numbers whose absolute scale after stripping
 *       exceeds {@link SignalConstants#MAX_NUMBER_SCALE} are rejected.</li>
 *   <li>Strings: shorthand escapes for backspace, tab, newline, form feed,
 *       carriage return, quote and backslash. Other code units below 0x20
 *       and lone surrogates become lowercase {@code \\uXXXX}. Slash and
 *       non-ASCII characters are written raw. The output therefore never
 *       holds an unpaired surrogate, so encoding it as UTF-8 is lossless.</li>
 *   <li>Output longer than {@link SignalConstants#MAX_CANONICAL_CHARS}
 *       characters aborts with {@link OutputLimitExceededException} as soon as
 *       the limit is passed (Ruling 61), so a small request cannot force a large
 *       allocation.</li>
 *   <li>Supported values: null, String, Boolean, Byte, Short, Integer, Long,
 *       BigInteger, BigDecimal, Float, Double, Map with String keys, List.</li>
 * </ul>
 *
 * <p>Portability limit: integers beyond 2^53 do not survive a JavaScript
 * round trip. Tenants should send such identifiers as strings.
 *
 * <p>Contract: unsupported input throws {@link IllegalArgumentException}.
 * This class is a utility; the exception never crosses the signals module
 * boundary because callers wrap it (Rule 17.3). Messages never contain input
 * values or keys.
 */
public final class CanonicalJsonSerializer {

    private static final String NULL_LITERAL = "null";
    private static final String TRUE_LITERAL = "true";
    private static final String FALSE_LITERAL = "false";
    private static final int FIRST_UNESCAPED_CODE_UNIT = 0x20;
    private static final int UNICODE_ESCAPE_TOP_SHIFT = 12;
    private static final int NIBBLE_BITS = 4;
    private static final int NIBBLE_MASK = 0xF;
    private static final int HEX_RADIX = 16;

    /**
     * Thrown when the canonical output would exceed
     * {@link SignalConstants#MAX_CANONICAL_CHARS} characters (Ruling 61). It is
     * an {@link IllegalArgumentException} so existing handling still applies;
     * B-009b maps it to PAYLOAD_TOO_LARGE. The message holds no input content.
     */
    public static final class OutputLimitExceededException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        OutputLimitExceededException() {
            super("Canonical output exceeds the maximum size");
        }
    }

    private CanonicalJsonSerializer() {
    }

    /**
     * Serializes a parsed JSON value to its canonical string.
     *
     * @param value null, String, Boolean, Number, Map with String keys, or List
     * @return the canonical JSON text
     * @throws OutputLimitExceededException if the output would exceed the size limit
     * @throws IllegalArgumentException     if the value or a nested value is unsupported
     */
    public static String serialize(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    /**
     * Serializes a parsed JSON value to canonical UTF-8 bytes.
     *
     * @param value null, String, Boolean, Number, Map with String keys, or List
     * @return the canonical JSON as UTF-8 bytes
     * @throws OutputLimitExceededException if the output would exceed the size limit
     * @throws IllegalArgumentException     if the value or a nested value is unsupported
     */
    public static byte[] serializeToBytes(Object value) {
        return serialize(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireWithinLimit(StringBuilder out) {
        if (out.length() > SignalConstants.MAX_CANONICAL_CHARS) {
            throw new OutputLimitExceededException();
        }
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append(NULL_LITERAL);
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? TRUE_LITERAL : FALSE_LITERAL);
        } else if (value instanceof Number number) {
            writeNumber(out, number);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map, depth + 1);
        } else if (value instanceof List<?> list) {
            writeArray(out, list, depth + 1);
        } else {
            throw new IllegalArgumentException("Unsupported value type: " + value.getClass().getName());
        }
        requireWithinLimit(out);
    }

    private static void requireDepth(int depth) {
        if (depth > SignalConstants.MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("Nesting exceeds the maximum depth");
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
        requireDepth(depth);
        String[] keys = new String[map.size()];
        int index = 0;
        for (Object key : map.keySet()) {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("Object keys must be non-null strings");
            }
            keys[index] = stringKey;
            index++;
        }
        Arrays.sort(keys);
        out.append('{');
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(out, keys[i]);
            out.append(':');
            write(out, map.get(keys[i]), depth);
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int depth) {
        requireDepth(depth);
        out.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(out, item, depth);
        }
        out.append(']');
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number instanceof Integer || number instanceof Long
                || number instanceof Short || number instanceof Byte) {
            out.append(number.longValue());
        } else if (number instanceof BigInteger bigInteger) {
            out.append(bigInteger.toString());
        } else if (number instanceof BigDecimal bigDecimal) {
            appendDecimal(out, bigDecimal);
        } else if (number instanceof Double doubleNumber) {
            double raw = doubleNumber;
            requireFinite(Double.isFinite(raw));
            appendDecimal(out, BigDecimal.valueOf(raw));
        } else if (number instanceof Float floatNumber) {
            float raw = floatNumber;
            requireFinite(Float.isFinite(raw));
            appendDecimal(out, new BigDecimal(Float.toString(raw)));
        } else {
            throw new IllegalArgumentException("Unsupported number type: " + number.getClass().getName());
        }
    }

    private static void requireFinite(boolean finite) {
        if (!finite) {
            throw new IllegalArgumentException("Numbers must be finite");
        }
    }

    private static void appendDecimal(StringBuilder out, BigDecimal decimal) {
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (Math.abs((long) stripped.scale()) > SignalConstants.MAX_NUMBER_SCALE) {
            throw new IllegalArgumentException("Number exponent exceeds the maximum scale");
        }
        out.append(stripped.toPlainString());
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        int index = 0;
        while (index < text.length()) {
            index = appendCharacter(out, text, index);
            requireWithinLimit(out);
        }
        out.append('"');
    }

    /** Appends the character at index, plus its low surrogate when it starts a valid pair. */
    private static int appendCharacter(StringBuilder out, String text, int index) {
        char current = text.charAt(index);
        boolean startsPair = Character.isHighSurrogate(current)
                && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1));
        if (startsPair) {
            out.append(current).append(text.charAt(index + 1));
            return index + 2;
        }
        appendSingle(out, current);
        return index + 1;
    }

    private static void appendSingle(StringBuilder out, char current) {
        switch (current) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\b' -> out.append("\\b");
            case '\t' -> out.append("\\t");
            case '\n' -> out.append("\\n");
            case '\f' -> out.append("\\f");
            case '\r' -> out.append("\\r");
            default -> {
                if (current < FIRST_UNESCAPED_CODE_UNIT || Character.isSurrogate(current)) {
                    appendUnicodeEscape(out, current);
                } else {
                    out.append(current);
                }
            }
        }
    }

    private static void appendUnicodeEscape(StringBuilder out, char current) {
        out.append("\\u");
        for (int shift = UNICODE_ESCAPE_TOP_SHIFT; shift >= 0; shift -= NIBBLE_BITS) {
            out.append(Character.forDigit((current >> shift) & NIBBLE_MASK, HEX_RADIX));
        }
    }
}