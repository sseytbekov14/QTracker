-- The free-text fields of a control may hold up to 20 000 characters (2026-11-20, ControlTextLimits.MAX): the columns
-- become TEXT; the limit itself is the application's (one constant for the server's check and the pages).
-- Before: control_description, prp, process_activities, control_operator_review, control_steps_performed,
-- soqm_head_comments, process_owner_comments VARCHAR(2000) (the application allowed 1000 / 2000), process_name,
-- references_to_control, department VARCHAR(255).
-- Safe on PostgreSQL with data: VARCHAR(n) -> TEXT changes no value and rewrites no row (binary-compatible types);
-- no view or index uses these columns (checked 2026-11-20: no views; the indexes of controls are on id, control_id,
-- created_from_control_id). Running it again changes nothing (TEXT -> TEXT).
-- Undo by hand only if no value is longer than the old size, else the ALTER fails (nothing is cut): check first, e.g.
--   SELECT id FROM controls WHERE length(control_description) > 2000 OR length(process_name) > 255 ...;
--   ALTER TABLE controls ALTER COLUMN control_description TYPE VARCHAR(2000); ... (process_name, references_to_control,
--   department VARCHAR(255)); DELETE FROM flyway_schema_history WHERE version = '31';

ALTER TABLE controls ALTER COLUMN control_description TYPE TEXT;
ALTER TABLE controls ALTER COLUMN prp TYPE TEXT;
ALTER TABLE controls ALTER COLUMN process_name TYPE TEXT;
ALTER TABLE controls ALTER COLUMN references_to_control TYPE TEXT;
ALTER TABLE controls ALTER COLUMN department TYPE TEXT;
ALTER TABLE controls ALTER COLUMN process_activities TYPE TEXT;
ALTER TABLE controls ALTER COLUMN control_operator_review TYPE TEXT;
ALTER TABLE controls ALTER COLUMN control_steps_performed TYPE TEXT;
ALTER TABLE controls ALTER COLUMN soqm_head_comments TYPE TEXT;
ALTER TABLE controls ALTER COLUMN process_owner_comments TYPE TEXT;
