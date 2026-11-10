-- Undo of V29__Log_date_indexes.sql, run by hand (Flyway does not undo). Only the indexes go; no data is lost.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '29';

DROP INDEX IF EXISTS idx_email_outbox_created;

DROP INDEX IF EXISTS idx_admin_audit_log_created;
