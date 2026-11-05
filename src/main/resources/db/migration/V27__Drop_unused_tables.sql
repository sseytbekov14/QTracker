-- Data minimisation (2026-11-05): five tables nothing reads any more go.
--   user_access_v8_backup, user_admin_access_v11_backup, users_role_backup: the access and role words of the users
--     as they were before V8, V11 and V22, kept only for the hand-run undo scripts of those versions; nobody goes
--     back to them, and they are data about people kept with no use.
--   control_notification_log: the log of the reminders of the first version (V1), replaced by email_outbox (V19).
--   control_related_controls: the Other Related Controls links of V15, replaced by controls.other_related_controls
--     and related_control_ref (V17, V18); V18 copied the links.
-- The database backup taken before an update keeps them. The undo scripts of V8, V11, V22, V15 and V18 restore no
-- values after this. Undo by hand: db/rollback/V27__undo_drop_unused_tables.sql (the tables come back empty).

DROP TABLE IF EXISTS user_access_v8_backup;
DROP TABLE IF EXISTS user_admin_access_v11_backup;
DROP TABLE IF EXISTS users_role_backup;
DROP TABLE IF EXISTS control_notification_log;
DROP INDEX IF EXISTS idx_control_related_controls_related;
DROP TABLE IF EXISTS control_related_controls;
