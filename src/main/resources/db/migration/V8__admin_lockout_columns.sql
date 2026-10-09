-- V8: admin lockout support for B-007 (Rule 16.7).
--
-- 1. Rule 16.7 requires an account to stay locked until another
--    admin manually unlocks it. A timestamp named locked_until
--    implies an automatic expiry, which the Contract forbids.
--    Renamed to locked_at: non-null means locked, until manual
--    unlock. The column is nullable, so no backfill is needed.
-- 2. "5 failed attempts within a 15-minute window" cannot be
--    computed from failed_attempt_count alone. This column records
--    when the current failure window began.
--
-- Grants (Ruling 21): least privilege for trustguard_app. V2/V3/V5
-- granted nothing on these two tables. No INSERT on admin_users
-- (admin creation arrives in B-007b), and no DELETE on
-- security_events (platform record, 90-day retention, Rule 14.8).
-- security_events gets INSERT only, so the application role can
-- write events but cannot read them back.
--
-- No RLS: admin_users and security_events are platform tables,
-- not tenant-scoped.

ALTER TABLE admin_users RENAME COLUMN locked_until TO locked_at;

ALTER TABLE admin_users ADD COLUMN failed_window_started_at TIMESTAMPTZ;

GRANT SELECT, UPDATE ON admin_users TO trustguard_app;

GRANT INSERT ON security_events TO trustguard_app;