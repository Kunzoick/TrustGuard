package com.trustguard.signals.util;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.signals.constants.SignalConstants;

/**
 * Deep unmodifiable snapshot of an event payload (Ruling 69). Nested maps and lists are copied
 * into unmodifiable structures, so nothing that holds the original can change the snapshot and
 * nothing that holds the snapshot can change itself. Null values are kept, which is why
 * {@code Map.copyOf} and {@code List.copyOf} are not used.
 *
 * <p>Only the value types the canonical serializer accepts are allowed: String, Boolean, the
 * exact classes Byte, Short, Integer, Long, BigInteger, BigDecimal, Float and Double, Map with
 * String keys, and List. The exact-class check keeps mutable Number subclasses out. A null
 * payload becomes an empty map (Ruling 57). Nesting is bounded by
 * {@link SignalConstants#MAX_NESTING_DEPTH}, counted like the serializer counts it: the root map
 * is depth 1. A cycle recurses to that bound and fails there, so it cannot loop. A value that is
 * shared but not cyclic is copied once per use.
 *
 * <p>This class checks shape and type only, never values. Failures throw
 * {@link InvalidEventPayloadException} with fixed messages that never contain payload content.
 */
public class PayloadSnapshot {
    private static final int ROOT_DEPTH= 1;
    private static final int NODE_BUDGET = SignalConstants.MAX_CANONICAL_CHARS;
    private static final Set<Class<?>> IMMUTABLE_NUMBER_TYPES= Set.of(Byte.class, Short.class, Integer.class, Long.class
            , BigInteger.class, BigDecimal.class, Float.class, Double.class);

    private static final class Budget{
        private int remaining= NODE_BUDGET;
        void spend(){
            if(remaining== 0){
                throw new InvalidEventPayloadException("Payload holds too many values");
            }
            remaining--;
        }
    }
    private PayloadSnapshot(){}
    /**
     * copies a payload into deeply unmodifiable structure.
     * @param payload the parsed payload, or null for an empty payload
     * @return an inmodifiable map; empty when the payload is null
     * @throws InvalidEventPayloadException if the payload is invalid
     */
    public static Map<String, Object> copyOf(Map<?, ?> payload){
        if(payload== null){
            return Collections.emptyMap();
        }
        return copyMap(payload, ROOT_DEPTH, new Budget());
    }
    private static void requireDepth(int depth){
        if(depth > SignalConstants.MAX_NESTING_DEPTH){
            throw new InvalidEventPayloadException("Payload nesting exceeds the maximum depth");
        }
    }
    private static Map<String, Object> copyMap(Map<?, ?> source, int depth, Budget budget){
        requireDepth(depth);
        budget.spend();
        Map<String, Object> copy= new LinkedHashMap<>();
        for(Map.Entry<?, ?> entry : source.entrySet()){
            if(!(entry.getKey() instanceof String key)){
                throw new InvalidEventPayloadException("Payload key is not a string");
            }
            copy.put(key, copyValue(entry.getValue(), depth, budget));
        }
        return Collections.unmodifiableMap(copy);
    }
    private static List<Object> copyList(List<?> source, int depth, Budget budget){
        requireDepth(depth);
        budget.spend();
        List<Object> copy= new ArrayList<>(source.size());
        for(Object item : source){
            copy.add(copyValue(item, depth, budget));
        }
        return Collections.unmodifiableList(copy);
    }
    private static Object copyValue(Object value, int containerDepth, Budget budget){
        if(value instanceof Map<?,?> map){
            return copyMap(map, containerDepth + 1, budget);
        }
        if(value instanceof List<?> list){
            return copyList(list, containerDepth + 1, budget);
        }
        if(isImmutableScalar(value)){
            budget.spend();
            return value;
        }
        throw new InvalidEventPayloadException("Payload contains an invalid value type");
    }
    private static boolean isImmutableScalar(Object value){
        return value== null || value instanceof String || value instanceof Boolean
                || IMMUTABLE_NUMBER_TYPES.contains(value.getClass());
    }
}
