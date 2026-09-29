-- V2: Row Level Security policies for tenant-scoped tables.
-- Rule 4.1 Layer 4 — last line of defence for tenant isolation.
-- Policies read app.tenant_id, set via SET LOCAL by the
-- TenantRlsAspect (B-005) at the start of every transaction.
-- Never SET (session-scoped) — always SET LOCAL (transaction-
-- scoped) to avoid HikariCP connection-reuse cross-tenant leaks.

-- tenants and platform_config are NOT RLS-scoped here:
-- tenants is the root entity (tenant_id would be self-referential),
-- platform_config is platform-wide, not tenant data.
-- projects, api_keys, and revoked_keys are tenant-scoped and
-- RLS-enabled below.

-- FORCE ROW LEVEL SECURITY is applied per CF-001 (B-004 review).
-- Note this does not protect against superuser bypass — Flyway
-- connects as the 'trustguard' superuser, and superusers bypass
-- RLS regardless of FORCE. It does add defence-in-depth against
-- the table-owner role in non-superuser deployment configurations
-- (e.g. once trustguard_app or another owner role is used), at
-- zero cost. Separating migration and application DB users
-- remains a documented production deployment concern — see
-- known technical debt.

ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
CREATE POLICY projects_tenant_isolation ON projects
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY api_keys_tenant_isolation ON api_keys
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- revoked_keys is tenant-scoped per Ruling 1 review — mandatory
-- RLS under Rule 4.1 Layer 4, same as projects and api_keys.
ALTER TABLE revoked_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE revoked_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY revoked_keys_tenant_isolation ON revoked_keys
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Application role. Docker Compose currently connects as the
-- 'trustguard' superuser (see B-003); this role is created now
-- for correctness and will be enforced in production deployment
-- configuration. Not enforced at startup in V1.
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT FROM pg_roles WHERE rolname = 'trustguard_app'
    ) THEN
        CREATE ROLE trustguard_app;
    END IF;
END $$;

GRANT SELECT, INSERT, UPDATE ON projects TO trustguard_app;

GRANT SELECT, INSERT, UPDATE ON api_keys TO trustguard_app;
REVOKE UPDATE ON api_keys FROM trustguard_app;
-- api_keys is immutable after creation (Ruling 1). This
-- GRANT-then-REVOKE is defense-in-depth: it documents intent
-- explicitly even though the net effect is a no-op UPDATE grant.
-- Revocation is tracked exclusively in revoked_keys, never by
-- mutating this table.

GRANT SELECT, INSERT ON revoked_keys TO trustguard_app;
-- No UPDATE, no DELETE — revoked_keys is insert-only, ever.