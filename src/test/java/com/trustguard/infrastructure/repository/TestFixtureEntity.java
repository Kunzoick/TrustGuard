package com.trustguard.infrastructure.repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

/**
 * Test-only entity mapped onto the real `projects` table so
 * BaseRepositoryImplTest can exercise generic tenant-scoped CRUD
 * against real RLS policies without depending on a production
 * Project entity, which does not exist until a later batch.
 */
@Entity
@Table(name = "projects")
public class TestFixtureEntity {
    @Getter
    @Id
    private UUID id;
    @Getter
    @Column(name = "tenant_id")
    private UUID tenantId;
    @Column(name = "name")
    private String name;
    @Column(name = "environment")
    private String environment;

    protected TestFixtureEntity(){}
    public TestFixtureEntity(UUID id, UUID tenantId, String name, String environment){
        this.id= id;
        this.tenantId= tenantId;
        this.name= name;
        this.environment= environment;
    }

}
