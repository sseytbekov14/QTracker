-- Undo of V32__Email_outbox_cc.sql, by hand: removes the CC addresses of the e-mails (the e-mails themselves stay).
-- Before it, put back an application without CC, then:

ALTER TABLE email_outbox DROP COLUMN IF EXISTS cc_emails;
DELETE FROM flyway_schema_history WHERE version = '32';
