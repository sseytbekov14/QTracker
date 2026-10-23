-- Undo of V14__Control_created_from.sql, run by hand (Flyway does not undo). The controls made with Create Control
-- stay; only the link to the control each was created from goes (their Changelog entry still names it).
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '14';

DROP INDEX IF EXISTS idx_controls_created_from;
ALTER TABLE controls DROP CONSTRAINT IF EXISTS fk_controls_created_from;
ALTER TABLE controls DROP COLUMN IF EXISTS created_from_control_id;
