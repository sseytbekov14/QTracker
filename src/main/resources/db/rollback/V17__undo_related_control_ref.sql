-- Undo of V17__Related_control_ref.sql, run by hand (Flyway does not undo): the list Other Related Controls is chosen
-- from goes, with every Control ID imported into it. The values saved on the controls are not in this table and stay.
-- Needed only to run a QTracker version older than V17 on this database.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '17';

DROP TABLE IF EXISTS related_control_ref;
