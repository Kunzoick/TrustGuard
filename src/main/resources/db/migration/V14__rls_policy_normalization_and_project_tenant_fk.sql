-- V14: B-008b (RLS normalization and tenant/project integrity). Rulings 44-47, Rules 4.1, 6.1, 21.9.
--
-- 1. The three V2 policies are altered in place (ALTER POLICY, never DROP + CREATE), so there is no moment
--    without a policy and the names are kept. Both USING and WITH CHECK use the NULLIF form (Ruling 45):
--    the empty string a pooled connection keeps after a tenant transaction becomes NULL, so a missing tenant
--    context matches no rows instead of raising an invalid uuid error. FORCE ROW LEVEL SECURITY and the
--    grants are unchanged.
-- 2. V6's three INSERT policies are dropped. They were redundant (a policy with no FOR clause applies USING
--    as WITH CHECK) and a hazard (permissive policies OR together, so tightening one policy and forgetting
--    the other would silently leave INSERT loose). The explicit WITH CHECK set in step 1 enforces INSERT
--    isolation (Ruling 46). The V2 and V6 files themselves stay unchanged.
-- 3. A project is tied to its tenant (Ruling 47): projects gets UNIQUE (tenant_id, id), a valid foreign key
--    target, and trust_events trades its single-column project foreign key for a composite one. Foreign key
--    checks bypass RLS, so without this a tenant could reference another tenant's project.
--    The drop has no IF EXISTS on purpose: a wrong constraint name must fail loudly.
--
-- Rule 21.9: no column changes and no data changes. Flyway runs this file in one transaction.

-- 1. Normalize the V2 policies.
ALTER POLICY projects_tenant_isolation ON projects
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER POLICY api_keys_tenant_isolation ON api_keys
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER POLICY revoked_keys_tenant_isolation ON revoked_keys
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- 2. Drop V6's redundant INSERT policies.
DROP POLICY projects_insert_isolation ON projects;
DROP POLICY api_keys_insert_isolation ON api_keys;
DROP POLICY revoked_keys_insert_isolation ON revoked_keys;

-- 3. Tenant/project integrity.
ALTER TABLE projects ADD CONSTRAINT projects_tenant_id_id_key UNIQUE (tenant_id, id);

ALTER TABLE trust_events DROP CONSTRAINT trust_events_project_id_fkey;

ALTER TABLE trust_events ADD CONSTRAINT trust_events_tenant_project_fkey
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id);

-- Same integrity for api_keys (Ruling 48). V1 declares the single-column foreign key inline, so its
-- default name is deterministic; a wrong name fails the migration loudly.
ALTER TABLE api_keys DROP CONSTRAINT api_keys_project_id_fkey;

ALTER TABLE api_keys ADD CONSTRAINT api_keys_tenant_project_fkey
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id);