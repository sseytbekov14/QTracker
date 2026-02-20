-- Remove unused attachment columns from controls.
ALTER TABLE controls
    DROP COLUMN IF EXISTS attached_file;

ALTER TABLE controls
    DROP COLUMN IF EXISTS attachment;
