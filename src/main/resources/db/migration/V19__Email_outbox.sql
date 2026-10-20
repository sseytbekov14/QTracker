-- The e-mails of QTracker go through an outbox (business requirements NOTIFICATIONS_v5, NFR-1..3): the event or the
-- scheduler that makes a notification writes the e-mail here in its own transaction (with the in-app notification);
-- a dispatcher sends it later, outside that transaction, and retries a failure. The table is also the journal of
-- every e-mail: to whom, which template, when, with what result.
-- dedupe_key makes each e-mail once (a reminder once per control and person, an event once per history entry and
-- person); a second insert of the same key is skipped (ON CONFLICT DO NOTHING).
-- status: PENDING, SENDING, SENT, FAILED, SKIPPED_DISABLED (e-mail off or address not in the whitelist),
-- SKIPPED_INACTIVE (the person was deactivated before the sending), FAILED_RENDER (a placeholder had no value).
-- next_attempt_at: when a FAILED e-mail is tried again (null: not again); claimed_at: when a dispatcher took it.
-- notification_template: subject and body of T1..T12 with {PLACEHOLDERS}, read for every notification, so a text
-- is changed in the database without a release; V20 fills it with the texts of NOTIFICATIONS_v5.
-- Undo by hand: db/rollback/V19__undo_email_outbox.sql.

CREATE TABLE IF NOT EXISTS email_outbox (
    id BIGSERIAL PRIMARY KEY,
    control_id BIGINT,
    template_key VARCHAR(10) NOT NULL,
    recipient_user_id BIGINT,
    recipient_email VARCHAR(320) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    body_html TEXT NOT NULL,
    importance VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    dedupe_key VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000),
    provider VARCHAR(20),
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    next_attempt_at TIMESTAMP,
    claimed_at TIMESTAMP,
    CONSTRAINT uq_email_outbox_dedupe_key UNIQUE (dedupe_key)
);

CREATE INDEX IF NOT EXISTS idx_email_outbox_status ON email_outbox (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_email_outbox_control ON email_outbox (control_id, template_key);

CREATE TABLE IF NOT EXISTS notification_template (
    template_key VARCHAR(10) PRIMARY KEY,
    subject VARCHAR(500) NOT NULL,
    body TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
