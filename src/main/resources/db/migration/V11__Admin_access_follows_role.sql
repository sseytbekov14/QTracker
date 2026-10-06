-- The Admin Panel (users and audit) belongs to every SoQM Team member and only to them; the role is the
-- access level (AccessPolicy.hasAdminAccess). admin_access is kept, but from now on it follows the level:
-- TRUE for SOQM, FALSE for everyone else.
--
-- Nobody is promoted: a user with admin_access but another level keeps their level and loses the flag
-- (list them before deploying: SELECT mail, access_level, access_scope FROM users
--  WHERE admin_access AND access_level <> 'SOQM'). If no active SOQM user exists, nobody can open the
-- Admin Panel afterwards: check that first as well.
--
-- Every changed flag is kept in user_admin_access_v11_backup, so db/rollback/V11__undo_admin_access_follows_role.sql
-- can put the old flags back.

CREATE TABLE IF NOT EXISTS user_admin_access_v11_backup (
    user_id      BIGINT      NOT NULL PRIMARY KEY,
    admin_access BOOLEAN     NOT NULL,
    access_level VARCHAR(20) NOT NULL,
    access_scope VARCHAR(20) NOT NULL,
    migrated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user_admin_access_v11_backup (user_id, admin_access, access_level, access_scope)
SELECT id, admin_access, access_level, access_scope
FROM users
WHERE (access_level = 'SOQM' AND admin_access = FALSE)
   OR (access_level <> 'SOQM' AND admin_access = TRUE);

UPDATE users
SET admin_access = CASE WHEN access_level = 'SOQM' THEN TRUE ELSE FALSE END
WHERE (access_level = 'SOQM' AND admin_access = FALSE)
   OR (access_level <> 'SOQM' AND admin_access = TRUE);

ALTER TABLE users ADD CONSTRAINT users_admin_access_follows_level_check
    CHECK ((access_level = 'SOQM' AND admin_access = TRUE) OR (access_level <> 'SOQM' AND admin_access = FALSE));
