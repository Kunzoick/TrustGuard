-- V11: B-008 (Event storage). The partitioned event log (Rules 6.1, 6.3, 6.5, 6.8).
--
-- Range-partitioned by received_at (platform-controlled), never occurred_at. The primary key
-- includes the partition key, so no unique constraint on (tenant_id, idempotency_key) is possible
-- here; idempotency_keys (V13) is the durable dedup backstop (Ruling 37).
--
-- Indexes are created on the parent, so every partition gets local indexes (Rule 6.8).
-- tenant_id leads every composite. No index on idempotency_key (Ruling 37, Rule 6.5).
--
-- RLS: one policy per table with USING and WITH CHECK (Rule 4.1 Layer 4, Rulings 39, 40).
-- NULLIF turns the empty string a pooled connection keeps after a tenant transaction into NULL,
-- so a missing tenant context matches no rows instead of raising an invalid uuid error.
--
-- Grants (Ruling 38): parent table only, never partitions. SELECT, INSERT, and UPDATE of
-- risk_context_snapshot only (Rule 6.7). No DELETE, no TRUNCATE.

CREATE TABLE trust_events (
    event_id              UUID NOT NULL,
    tenant_id             UUID NOT NULL REFERENCES tenants(id),
    project_id            UUID NOT NULL REFERENCES projects(id),
    actor_type            VARCHAR(20) NOT NULL
        CHECK (actor_type IN ('USER', 'ANONYMOUS', 'SERVICE')),
    actor_id              TEXT NOT NULL CHECK (char_length(actor_id) > 0),
    session_id            TEXT,
    event_type            VARCHAR(255) NOT NULL,
    environment           VARCHAR(20) NOT NULL
        CHECK (environment IN ('PRODUCTION', 'STAGING', 'DEVELOPMENT')),
    occurred_at           TIMESTAMPTZ NOT NULL,
    received_at           TIMESTAMPTZ NOT NULL,
    sdk_version           VARCHAR(255) NOT NULL,
    ip_address            VARCHAR(45) NOT NULL,
    user_agent            TEXT,
    device_fingerprint    TEXT,
    idempotency_key       VARCHAR(255) NOT NULL,
    payload               JSONB NOT NULL,
    risk_context_snapshot JSONB,
    PRIMARY KEY (event_id, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX idx_trust_events_tenant_actor_occurred
    ON trust_events (tenant_id, actor_id, occurred_at);
CREATE INDEX idx_trust_events_tenant_type_occurred
    ON trust_events (tenant_id, event_type, occurred_at);
CREATE INDEX idx_trust_events_tenant_received
    ON trust_events (tenant_id, received_at);

ALTER TABLE trust_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE trust_events FORCE ROW LEVEL SECURITY;
CREATE POLICY trust_events_tenant_isolation ON trust_events
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON trust_events TO trustguard_app;
GRANT UPDATE (risk_context_snapshot) ON trust_events TO trustguard_app;

-- First two monthly partitions: the current UTC month and the next (AC 3).
SELECT create_trust_events_partition(
    date_trunc('month', now() AT TIME ZONE 'UTC')::date);
SELECT create_trust_events_partition(
    (date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month')::date);