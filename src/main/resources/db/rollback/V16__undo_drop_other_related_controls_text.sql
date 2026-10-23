-- Undo of V16__Drop_other_related_controls_text.sql, run by hand (Flyway does not undo). The column comes back
-- empty and nullable, as V1 created it: the texts it held before V16 are NOT restored (only a backup of the
-- database taken before V16 has them). Needed only to run a QTracker version older than V16 on this database.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '16';

ALTER TABLE controls ADD COLUMN IF NOT EXISTS other_related_controls VARCHAR(255);
