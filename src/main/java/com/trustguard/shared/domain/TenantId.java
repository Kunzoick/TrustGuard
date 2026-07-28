package com.trustguard.shared.domain;
import java.util.UUID;

/**
 * Tenant identifier. Wraps a uuid string, always normalized to canonical
 * form (lowercase, no hyphens).
 */
public record TenantId(String value) {
    //validates that the supplied value is a well formed uuid
    public TenantId {
        value= requireValidUuid(value, "TenantId");
    }
    private static String requireValidUuid(String rawValue, String fieldName){
        if(rawValue== null || rawValue.isBlank()){
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        try{
            return UUID.fromString(rawValue).toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID, was: "+ rawValue, ex);
        }
    }
}
