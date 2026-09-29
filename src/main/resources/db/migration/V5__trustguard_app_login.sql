-- Added in B-005, not originally in the B-004 file plan. Necessary
-- for Rule 4.2 Vector 1 (TenantIsolationTest) to be meaningful:
-- without LOGIN capability, nothing can connect as trustguard_app,
-- and any test connecting as the trustguard superuser would bypass
-- RLS silently (FORCE ROW LEVEL SECURITY does not stop superusers —
-- see known debt, B-004). Password is a Flyway placeholder resolved
-- from the TRUSTGUARD_APP_DB_PASSWORD environment variable via
-- spring.flyway.placeholders.trustguardAppPassword — never
-- hardcoded (Rule 15.3).

ALTER ROLE trustguard_app WITH LOGIN PASSWORD 'trustguard_app_dev';