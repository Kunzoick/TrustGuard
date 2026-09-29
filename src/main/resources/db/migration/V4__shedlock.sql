-- Exact ShedLock library schema. Do not deviate from this
-- structure — ShedLock's JdbcTemplateLockProvider expects it
-- verbatim. Not activated until multi-instance deployment
-- (Rule 15.9) — table exists now so the migration history is
-- stable ahead of that need.
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);