-- Indexes by date of the two lists of the Admin Panel (2026-11-10): Audit trail reads admin_audit_log and E-mails
-- reads email_outbox for a period ("created_at >= ? AND created_at < ?"), newest first ("ORDER BY created_at DESC,
-- id DESC LIMIT 50"), and counts the period for the cards. Without these indexes every page load and every filter
-- went through the whole table; with them the database reads only the period and only the page shown.
-- CREATE INDEX (not CONCURRENTLY: Flyway runs the script in a transaction) locks the table against writes while the
-- index is built; on a large table build them by hand with CREATE INDEX CONCURRENTLY IF NOT EXISTS and the same
-- names before the release, and this script then finds them there.
-- Undo by hand: db/rollback/V29__undo_log_date_indexes.sql.

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created ON admin_audit_log (created_at, id);

CREATE INDEX IF NOT EXISTS idx_email_outbox_created ON email_outbox (created_at, id);
