-- Undo of V23__Control_history_indexes.sql, run by hand (Flyway does not undo). Only the indexes go; no data is lost.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '23';

DROP INDEX IF EXISTS idx_admin_audit_log_control;

DROP INDEX IF EXISTS idx_workflow_steps_control;

DROP INDEX IF EXISTS idx_workflow_history_control;
