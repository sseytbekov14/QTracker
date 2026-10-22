-- The old role columns of users go: since V6 access comes from access_level and access_scope, and since V11 the
-- Admin Panel from the SoQM level (admin_access follows it); nothing reads role or secondary_role any more
-- (migration/analysis/22_user_role_columns.md).
--
-- Check before deploying (expected: 0, and one row SOQM_TEAM | SOQM | true | 1 on prod):
--   SELECT count(*) FROM users WHERE secondary_role IS NOT NULL;
--   SELECT role, access_level, admin_access, count(*) FROM users WHERE role IS NOT NULL GROUP BY 1, 2, 3;
--
-- Every value that was set is kept in users_role_backup, so db/rollback/V22__undo_drop_user_role_columns.sql
-- can bring the columns back with them.

CREATE TABLE IF NOT EXISTS users_role_backup (
    user_id        BIGINT       NOT NULL PRIMARY KEY,
    role           VARCHAR(255),
    secondary_role VARCHAR(255),
    migrated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO users_role_backup (user_id, role, secondary_role)
SELECT id, role, secondary_role
FROM users
WHERE role IS NOT NULL
   OR secondary_role IS NOT NULL;

ALTER TABLE users DROP COLUMN IF EXISTS role;
ALTER TABLE users DROP COLUMN IF EXISTS secondary_role;
