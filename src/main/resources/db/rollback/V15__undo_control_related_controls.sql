-- Undo of V15__Control_related_controls.sql, run by hand (Flyway does not undo). The links SoQM Admin Team chose
-- go; the earlier free text in controls.other_related_controls stays, and the Changelog entries still name the
-- linked Control IDs.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '15';

DROP INDEX IF EXISTS idx_control_related_controls_related;
DROP TABLE IF EXISTS control_related_controls;
