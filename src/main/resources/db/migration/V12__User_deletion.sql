-- Users can be deleted from the Admin Panel (business decision of 2026-10-13): the users row goes, the history
-- and the logs stay with the person's name and e-mail as text (not anonymised, 7-year retention, spec 22).
-- 1. Who created a control is kept as text too, so the control keeps it when the creator's row is deleted
--    (controls.created_by is set to NULL then; the foreign key to users stays).
-- 2. deleted_users: who was deleted, so that the e-mail left in a control's people and Shared With is shown as
--    "e-mail (deleted user)" and a save that keeps it is not refused.

ALTER TABLE controls ADD COLUMN IF NOT EXISTS created_by_email VARCHAR(255);
ALTER TABLE controls ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(255);

UPDATE controls
SET created_by_email = (SELECT u.mail FROM users u WHERE u.id = controls.created_by),
    created_by_name = (SELECT u.displayname FROM users u WHERE u.id = controls.created_by)
WHERE created_by IS NOT NULL AND created_by_email IS NULL;

CREATE TABLE IF NOT EXISTS deleted_users (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    mail VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    role VARCHAR(64),
    deleted_at TIMESTAMP NOT NULL,
    deleted_by_email VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_deleted_users_mail ON deleted_users (mail);
