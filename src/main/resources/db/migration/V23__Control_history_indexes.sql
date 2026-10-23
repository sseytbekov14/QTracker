-- Indexes by control of the tables a control's page and the lists read per control (load test,
-- migration/analysis/9_performance_report.md): every History / Changelog tab, the current workflow step and the
-- "Closed late" mark read workflow_history, workflow_steps and admin_audit_log of one control, and without these
-- indexes each read went through the whole table (1 461 and 2 738 full passes in 6 minutes at 5 000 controls).
-- CREATE INDEX (not CONCURRENTLY: Flyway runs the script in a transaction) locks the table against writes while the
-- index is built; on a large table build them by hand with CREATE INDEX CONCURRENTLY IF NOT EXISTS and the same
-- names before the release, and this script then finds them there (report, section 5).
-- Undo by hand: db/rollback/V23__undo_control_history_indexes.sql.

CREATE INDEX IF NOT EXISTS idx_workflow_history_control ON workflow_history (control_id, created_at);

CREATE INDEX IF NOT EXISTS idx_workflow_steps_control ON workflow_steps (control_id, sequence_order);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_control ON admin_audit_log (control_id, created_at);
