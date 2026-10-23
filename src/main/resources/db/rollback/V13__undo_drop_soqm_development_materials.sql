-- Undo of V13__Drop_soqm_development_materials.sql, run by hand (Flyway does not undo). The column comes back
-- empty and nullable, as V1 created it: the values it held before V13 are NOT restored (only a backup of the
-- database taken before V13 has them). Needed only to run a QTracker version older than V13 on this database.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '13';

ALTER TABLE controls ADD COLUMN IF NOT EXISTS soqm_development_materials VARCHAR(255);
