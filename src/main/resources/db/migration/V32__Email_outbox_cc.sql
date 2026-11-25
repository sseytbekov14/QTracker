-- The copy (CC) of an e-mail (2026-11-25): the overdue e-mail T5 of In Progress and Review goes To the Facilitator or
-- the Control Operator with the Process Owner in CC, one e-mail instead of two. The addresses, lower case, joined by
-- ","; empty for every other e-mail. A new column only: nothing is changed or removed.
-- Undo by hand: db/rollback/V32__undo_email_outbox_cc.sql.

ALTER TABLE email_outbox ADD COLUMN IF NOT EXISTS cc_emails VARCHAR(2000);
