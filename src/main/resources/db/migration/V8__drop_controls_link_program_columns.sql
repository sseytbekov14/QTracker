-- Remove deprecated fields from controls.
ALTER TABLE controls
    DROP COLUMN IF EXISTS control_operators_program;

ALTER TABLE controls
    DROP COLUMN IF EXISTS link;
