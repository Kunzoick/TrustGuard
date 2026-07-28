package com.trustguard.shared.domain;
import java.util.UUID;

/**
 * Project identifier.Every tenant has at least on project and this identifier is always a well
 * formed UUID assigned by the platform at project creation time
 */
public record ProjectId(String value) {
    public ProjectId{
        value= requireValidUuid(value, "ProjectId");
    }
    private static String requireValidUuid(String rawValue, String fieldName){
        if(rawValue== null || rawValue.isBlank()){
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        try {
            return UUID.fromString(rawValue).toString();
        } catch (IllegalArgumentException ex){
            throw new IllegalArgumentException(fieldName + " must be a valid UUID, was: "+ rawValue, ex);
        }
    }
}
