-- The Completed notification (T12) says "successfully" (2026-11-06): "Control {ID} has been successfully completed."
-- in the e-mail and in the application alike, both read this text. Only that sentence is changed, so a text changed by
-- hand keeps the rest of its words; a text without the sentence stays as it is.
-- Undo by hand: db/rollback/V28__undo_notification_completed_successfully.sql.

UPDATE notification_template
SET body = REPLACE(body, 'Control {CONTROL_ID} has been completed.', 'Control {CONTROL_ID} has been successfully completed.'),
    updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T12'
    AND body LIKE '%Control {CONTROL_ID} has been completed.%';
