-- The plain-text part of an e-mail (2026-10-30): every e-mail is sent as multipart/alternative, the HTML of the one
-- layout (EmailLayout) and this text with the same title, text, "Go to Control: address" and footer. Null for an
-- e-mail queued before this version; the sender then takes the text of its HTML.
-- Undo by hand: db/rollback/V24__undo_email_outbox_body_text.sql.

ALTER TABLE email_outbox ADD COLUMN IF NOT EXISTS body_text TEXT;
