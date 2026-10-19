-- V13: B-008 (Event storage). Durable idempotency backstop (Rule 6.4, Ruling 37).
--
-- Redis stays the fast path (B-010). This table enforces uniqueness of (tenant_id, idempotency_key),
-- which cannot be enforced on the partitioned trust_events. expires_at is the TTL column; the
-- expired-row cleanup (IdempotencyCleanupJob) belongs to B-026.

CREATE TABLE idempotency_keys (
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    idempotency_key  VARCHAR(255) NOT NULL,
    event_id         UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);

ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_keys_tenant_isolation ON idempotency_keys
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON idempotency_keys TO trustguard_app;