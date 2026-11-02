-- Control ID only (2026-11-02): the texts name a control by its ID. The description that stood after it is free text
-- and goes on to the next cycle as it is, so an e-mail of .../DEC could say .../OCT and confuse the reader. Only the
-- pair "{CONTROL_ID} {CONTROL_NAME}" is changed, in a text changed by hand too; the rest of such a text stays.
-- Undo by hand: db/rollback/V25__undo_notification_texts_control_id.sql.

UPDATE notification_template
SET subject = REPLACE(subject, '{CONTROL_ID} {CONTROL_NAME}', '{CONTROL_ID}'),
    body = REPLACE(body, '{CONTROL_ID} {CONTROL_NAME}', '{CONTROL_ID}'),
    updated_at = CURRENT_TIMESTAMP
WHERE subject LIKE '%{CONTROL_ID} {CONTROL_NAME}%'
    OR body LIKE '%{CONTROL_ID} {CONTROL_NAME}%';
