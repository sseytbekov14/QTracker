-- Undoes V11__Admin_access_follows_role.sql. Not a Flyway migration (Flyway reads only db/migration): run it
-- by hand, then delete the V11 row from flyway_schema_history, and deploy a build without V11, whose code
-- reads the admin_access flag again.
--
-- Users V11 changed get their old flag back, unless their level was changed after V11 (the later change
-- wins: the flag is restored only while the level is still the one V11 saw).

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_admin_access_follows_level_check;

UPDATE users
SET admin_access = (SELECT b.admin_access FROM user_admin_access_v11_backup b WHERE b.user_id = users.id)
WHERE id IN (SELECT b.user_id FROM user_admin_access_v11_backup b
             WHERE b.access_level = users.access_level AND b.access_scope = users.access_scope);

DROP TABLE user_admin_access_v11_backup;
