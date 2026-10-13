-- Undo of V12__User_deletion.sql, run by hand (Flyway does not undo). Deleted users stay deleted: their rows
-- cannot come back. What goes: the list of deleted users and the creator's name and e-mail kept as text.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '12';

DROP TABLE IF EXISTS deleted_users;
ALTER TABLE controls DROP COLUMN IF EXISTS created_by_name;
ALTER TABLE controls DROP COLUMN IF EXISTS created_by_email;
