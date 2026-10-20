-- Undo of V19__Email_outbox.sql and V20__Notification_templates (the texts), run by hand (Flyway does not undo).
-- The journal of the e-mails and the texts edited in the database are lost (take a backup first).
-- Needed only to run a QTracker version older than V19 on this database.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version IN ('19', '20');

DROP TABLE IF EXISTS notification_template;

DROP TABLE IF EXISTS email_outbox;
