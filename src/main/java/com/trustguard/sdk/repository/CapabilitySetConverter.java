package com.trustguard.sdk.repository;
import com.trustguard.shared.enums.Capability;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * it maps the capabilities TEXT[] column to a Set<Capability>, using the AttributeConverter approach
 * note: this converter's dbData side expects a java.sql.Array, which requires the JDBC driver ro return one for a TEXT[] column
 * confirmed supported by the postgreSQL JDBC driver. if a future driver version changes this behaviour, this converter is the single point of adjustment.
 */
@Converter
public class CapabilitySetConverter implements AttributeConverter<Set<Capability>, String[]> {
    @Override
    public String[] convertToDatabaseColumn(Set<Capability> attribute){
        if (attribute== null){
            return new String[0];
        }
        return attribute.stream().map(Enum::name).toArray(String[]::new);
    }
    @Override
    public Set<Capability> convertToEntityAttribute(String[] dbData){
        if (dbData == null){
            return Set.of();
        }
        return Arrays.stream(dbData).map(Capability::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
