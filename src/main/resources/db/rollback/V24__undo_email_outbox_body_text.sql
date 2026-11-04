-- Undo of V24__Email_outbox_body_text.sql, run by hand (Flyway does not undo), together with the release before it,
-- which sends the HTML only. Only the plain-text parts of the e-mails go; the HTML of every e-mail stays.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '24';

ALTER TABLE email_outbox DROP COLUMN IF EXISTS body_text;
