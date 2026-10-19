-- V12: B-008 (Event storage). Transactional outbox (Rule 3.3, Rulings 38-40).
--
-- (event_id, event_received_at) is a plain locator into trust_events, not a foreign key: a foreign
-- key into a partitioned table would block partition drops for retention (B-026).
-- The partial index serves the poller's PENDING scan (B-011).
--
-- Platform jobs that read across tenants (the B-011 poller) are blocked by RLS and need a separate
-- access path, decided in the B-011 brief. Not part of this migration.

CREATE TABLE outbox_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id),
    event_id           UUID NOT NULL,
    event_received_at  TIMESTAMPTZ NOT NULL,
    payload            JSONB NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count        INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at       TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_pending ON outbox_events (created_at) WHERE status = 'PENDING';

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_events_tenant_isolation ON outbox_events
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON outbox_events TO trustguard_app;