-- V6: Add WITH CHECK policies for INSERT isolation on
-- tenant-scoped tables. Completes Rule 4.1 Layer 4.
--
-- V2's USING-only policies were incomplete — USING covers
-- SELECT/UPDATE/DELETE but is ignored for INSERT. This
-- migration adds separate WITH CHECK policies for INSERT.
-- Both are required for full RLS enforcement per
-- PostgreSQL documentation.
--
-- Added during B-005 review to close a gap in B-004's
-- approved code without modifying the already-approved
-- V2 migration.

-- projects: INSERT must use the active tenant context
CREATE POLICY projects_insert_isolation ON projects
    FOR INSERT
    WITH CHECK (tenant_id =
        current_setting('app.tenant_id', true)::uuid);

-- api_keys: INSERT must use the active tenant context
CREATE POLICY api_keys_insert_isolation ON api_keys
    FOR INSERT
    WITH CHECK (tenant_id =
        current_setting('app.tenant_id', true)::uuid);

-- revoked_keys: INSERT must use the active tenant context
CREATE POLICY revoked_keys_insert_isolation ON revoked_keys
    FOR INSERT
    WITH CHECK (tenant_id =
        current_setting('app.tenant_id', true)::uuid);