-- Undoes V8__KDN_users_read_only.sql. Not a Flyway migration (Flyway reads only db/migration): run it by
-- hand, then delete the V8 row from flyway_schema_history, and deploy a build without V8, whose code
-- accepts PARTICIPANT / KDN again.
--
-- Users V8 changed get their old level back, unless their access was changed after V8 (by then it is no
-- longer READ_ONLY / KDN, and the later change wins). Users created as READ_ONLY / KDN after V8 stay so.

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_kdn_read_only_check;

UPDATE users
SET access_level = (SELECT b.access_level FROM user_access_v8_backup b WHERE b.user_id = users.id)
WHERE id IN (SELECT user_id FROM user_access_v8_backup)
  AND access_level = 'READ_ONLY'
  AND access_scope = 'KDN';

DROP TABLE user_access_v8_backup;
