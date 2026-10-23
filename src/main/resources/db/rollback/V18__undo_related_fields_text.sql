-- Undo of V18__Related_fields_text.sql, run by hand (Flyway does not undo). Other Related Controls go back to the links
-- of control_related_controls, which V18 left as they were: the Control IDs chosen after V18 are NOT written back to
-- links (only a backup taken before the undo has them). IT Applications go back to VARCHAR(255): a list longer than
-- 255 characters is cut to its first 255.
-- Needed only to run a QTracker version older than V18 on this database.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '18';

ALTER TABLE controls DROP COLUMN IF EXISTS other_related_controls;

UPDATE controls SET it_applications = LEFT(it_applications, 255) WHERE LENGTH(it_applications) > 255;

ALTER TABLE controls ALTER COLUMN it_applications TYPE VARCHAR(255);
