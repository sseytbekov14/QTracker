-- Undo of V25__Notification_texts_control_id.sql, run by hand (Flyway does not undo): the description comes back after
-- the Control ID in every body that has the ID and no description. A subject is left as it is (no text of QTracker has
-- a placeholder there).
-- Needed only to see the description in the e-mails again; a QTracker version older than V25 works with either text.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '25';

UPDATE notification_template
SET body = REPLACE(body, '{CONTROL_ID}', '{CONTROL_ID} {CONTROL_NAME}'),
    updated_at = CURRENT_TIMESTAMP
WHERE body LIKE '%{CONTROL_ID}%'
    AND body NOT LIKE '%{CONTROL_NAME}%';
