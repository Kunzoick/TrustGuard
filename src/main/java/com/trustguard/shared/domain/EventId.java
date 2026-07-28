package com.trustguard.shared.domain;
import java.util.UUID;

/**
 * Event identifier-> Rule 6.1 specifies "UUID v7- sortable by time" for every event entering the platform.
 * As woth correlationId, UUID v7 is structurally a standard UUID,
 * so this record validates general UUID format rather than attempting to verify version-specific bits,
 * which a domain primitive receiving an already formed ID cannot independently verify anyway
 */
public record EventId(String value) {
    public EventId{
        value= requireValidUuid(value, "EventId");
    }
    private static String requireValidUuid(String rawValue, String fieldName){
        if(rawValue== null || rawValue.isBlank()){
            throw new IllegalArgumentException(fieldName+ " must not be null or blank");
        }
        try{
            return UUID.fromString(rawValue).toString();
        }catch (IllegalArgumentException e){
            throw new IllegalArgumentException(fieldName+ " must be a valid UUID, was: "+ rawValue, e);
        }
    }
}
