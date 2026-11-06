-- Undo of V28__Notification_completed_successfully.sql, run by hand (Flyway does not undo): the Completed notification
-- (T12) says "has been completed." again where V28's sentence is still there.
-- Needed only to see the old sentence again; a QTracker version older than V28 works with either text.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '28';

UPDATE notification_template
SET body = REPLACE(body, 'Control {CONTROL_ID} has been successfully completed.', 'Control {CONTROL_ID} has been completed.'),
    updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T12'
    AND body LIKE '%Control {CONTROL_ID} has been successfully completed.%';
