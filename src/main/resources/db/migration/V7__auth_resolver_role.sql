-- Creates a narrow-privilege, BYPASSRLS role for credential
-- resolution queries that must execute before TenantContext is
-- established (see ADR-005, ADR-006). Read-only. No INSERT,
-- UPDATE, or DELETE grants. Password is a Flyway placeholder
-- resolved from an environment variable — never hardcoded,
-- same pattern as trustguard_app in V5 (Rule 15.3).

CREATE ROLE trustguard_authresolver
    WITH LOGIN
    PASSWORD '${trustguardAuthResolverPassword}'
    BYPASSRLS
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE;

GRANT SELECT ON api_keys TO trustguard_authresolver;
GRANT SELECT ON revoked_keys TO trustguard_authresolver;