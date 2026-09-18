package com.trustguard.sdk.repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

/**
 * maps revoked_keys. Includes the primary key id co;umn, omitted from the brief's "minimum fields"
 * list but required- every JPA entity needs exactly one @ID
 */
@Entity
@Table(name = "revoked_keys")
public class RevocationRecord {
    @Id
    private UUID id;
    @Getter
    @Column(name = "tenant_id")
    private UUID tenantId;
    @Getter
    @Column(name = "key_id")
    private String keyId;

    protected RevocationRecord(){}
}
