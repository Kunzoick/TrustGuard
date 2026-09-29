-- security_events created here (V3) rather than V12 as originally
-- planned in the Roadmap. B-007 (Admin Authentication) requires
-- this table for lockout and failed-attempt logging.
-- B-012 must NOT attempt to recreate this table.

CREATE TABLE admin_users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username              VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(255) NOT NULL,
    token_version         INTEGER NOT NULL DEFAULT 0,
    failed_attempt_count  INTEGER NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Platform-level security log per Rule 14.8. Not tenant-scoped
-- RLS — tenant_id is nullable here because admin-originated
-- events (e.g. admin login failures) have no tenant context.
CREATE TABLE security_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(100) NOT NULL,
    actor_id    VARCHAR(255),
    tenant_id   UUID,
    ip_address  VARCHAR(45),
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_security_events_created_at ON security_events(created_at DESC);
CREATE INDEX idx_security_events_tenant_created
    ON security_events(tenant_id, created_at DESC)
    WHERE tenant_id IS NOT NULL;