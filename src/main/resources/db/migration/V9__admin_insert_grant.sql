-- V9: B-007b (Admin Lifecycle). Lets the application role create the first admin at startup.
--
-- AdminSeedRunner inserts into admin_users when the table is empty (Ruling 24).
-- V8 granted SELECT and UPDATE only and deferred INSERT to this batch.
--
-- Least privilege (Rule 16.7, acceptance criterion 9): INSERT only.
-- No DELETE on admin_users: removing an admin is out of scope and stays denied.
-- ON CONFLICT (username) DO NOTHING needs only INSERT, plus the SELECT already held from V8.
--
-- No RLS: admin_users is a platform table, not tenant-scoped.

GRANT INSERT ON admin_users TO trustguard_app;