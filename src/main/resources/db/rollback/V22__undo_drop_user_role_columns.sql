-- Undoes V22__Drop_user_role_columns.sql. Not a Flyway migration (Flyway reads only db/migration): run it by hand,
-- then delete the V22 row from flyway_schema_history, and deploy a build without V22.
--
-- The columns come back as they were after V6 (VARCHAR(255), nullable) with the values V22 kept; users created
-- after V22 get none (the application has not written a role since V6, except for the dev seed accounts).

ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS secondary_role VARCHAR(255);

UPDATE users
SET role           = (SELECT b.role FROM users_role_backup b WHERE b.user_id = users.id),
    secondary_role = (SELECT b.secondary_role FROM users_role_backup b WHERE b.user_id = users.id)
WHERE id IN (SELECT user_id FROM users_role_backup);

DROP TABLE users_role_backup;
