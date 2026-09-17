package com.trustguard.sdk.repository;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * maps api_keys. No key_hash filed- the HMAC is recomputed from the raw key's keyId at verification time, never read back from storage.
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyRecord {
    @Id
    private UUID id;
    @Getter
    @Column(name = "key_id")
    private String keyId;
    @Getter
    @Column(name = "tenant_id")
    private UUID tenantId;
    @Column(name = "project_id")
    private UUID projectId;
    @Getter
    @Enumerated(EnumType.STRING)
    @Column(name = "environment")
    private Environment environment;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "capabilities", columnDefinition = "text[]")
    private String[] capabilitiesRaw;

    public Set<Capability> getCapabilities(){
        if(capabilitiesRaw == null){
            return Set.of();
        }
        return Arrays.stream(capabilitiesRaw).map(Capability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    protected ApiKeyRecord(){}

}
