-- V1: Core tenant, project, API key, and platform config tables.
-- Source of truth per Rule 8.1. All tables use UUID primary keys.
-- api_keys is append-only / immutable after creation — revocation
-- is tracked in the separate revoked_keys table (see Ruling 1,
-- B-004 review), never by mutating a column on this table.

CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    category    VARCHAR(50) NOT NULL
        CHECK (category IN ('FINTECH', 'HEALTHCARE', 'GOVERNMENT', 'GENERAL')),
    audit_tier  VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
        CHECK (audit_tier IN ('FULL', 'STANDARD', 'AGGREGATE')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id),
    name         VARCHAR(255) NOT NULL,
    environment  VARCHAR(20) NOT NULL
        CHECK (environment IN ('PRODUCTION', 'STAGING', 'DEVELOPMENT')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_tenant_id ON projects(tenant_id);

-- api_keys is immutable after INSERT. No revoked_at, no active
-- column. A key's revocation status is derived by checking
-- revoked_keys (Redis SISMEMBER first, this table as PostgreSQL
-- fallback per Rule 5.6). This table is never UPDATEd — see the
-- REVOKE UPDATE grant in V2.
CREATE TABLE api_keys (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id),
    project_id    UUID NOT NULL REFERENCES projects(id),
    key_id        VARCHAR(128) NOT NULL UNIQUE,
    key_hash      VARCHAR(255) NOT NULL,
    environment   VARCHAR(20) NOT NULL
        CHECK (environment IN ('PRODUCTION', 'STAGING', 'DEVELOPMENT')),
    capabilities  TEXT[] NOT NULL DEFAULT '{}',
    key_version   INTEGER NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_keys_tenant_id ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_key_id ON api_keys(key_id);

-- Insert-only revocation log. Per Ruling 1: never UPDATEd or
-- DELETEd. A key is "revoked" if any row exists for its key_id.
CREATE TABLE revoked_keys (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    key_id              VARCHAR(128) NOT NULL,
    revoked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by_key_id   VARCHAR(128)
);

CREATE INDEX idx_revoked_keys_key_id ON revoked_keys(key_id);
CREATE INDEX idx_revoked_keys_tenant_id ON revoked_keys(tenant_id);

CREATE TABLE platform_config (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key    VARCHAR(255) NOT NULL UNIQUE,
    config_value  TEXT NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);