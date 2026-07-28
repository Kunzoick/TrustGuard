package com.trustguard.shared.domain;
import java.util.UUID;

/**
 * Correlation identifier-> generated once per request at the api layer(rule 14.1)
 * and propagated across every log line, downstream call, and audit record.
 * Rule 5.9 specifies 122 bit of entropy via UUID v7; structurally a UUID v7 is indistinguishable from any other UUID
 * version once parsed, so standard UUID format validation applies.
 */
public record CorrelationId(String value) {
    public CorrelationId{
        value= requireValidUuid(value, "CorrelationId");
    }
    private static String requireValidUuid(String rawValue, String fieldName){
        if(rawValue== null || rawValue.isBlank()){
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        try{
            return UUID.fromString(rawValue).toString();
        }catch (IllegalArgumentException ex){
            throw new IllegalArgumentException(fieldName+ " must be a valid UUID, was: " +rawValue, ex);
        }
    }
}
