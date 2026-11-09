-- =====================================================================================================================
-- QTracker: one-off data clean-up (2026-11-06). NOT a Flyway migration; run by hand with psql, once.
--
--   1. Every control goes, with all of its data: attachments (rows), workflow steps and history, in-app notifications,
--      e-mails (sent and still to send, reminders included), its audit entries, and the older tables of controls
--      where they still exist (control_related_controls, control_notification_log).
--      The files on disk are NOT deleted by SQL: the list is printed below for deleting them by hand.
--   2. The made-up users with an e-mail ending in @qtracker.local go (V2 seed accounts: soqm1, soqm2, admin, fac1, ...),
--      except any of them with an Entra object id (signed in through SSO): those are only listed, never deleted.
--
-- Rule "history stays" (as the Admin Panel's Delete user, UserDeletion): what records what a person did stays as it
-- is - admin_audit_log (user events: who changed whom, sign-ins; the control events go with the controls in part 1),
-- deleted_users, and the e-mail log rows not tied to a control. A deleted user gets a row in deleted_users (who, when,
-- role, deleted by "cleanup-script"), their own in-app notifications go, and so do their rows in the old backup tables
-- (users_role_backup, user_access_v8_backup, user_admin_access_v11_backup) where those tables still exist.
--
-- Not touched: the reference lists (related_control_ref = Other Related Controls; IT Applications are a list in the
-- code), notification_template (the texts), flyway_schema_history, every other user.
--
-- DRY RUN BY DEFAULT: everything runs in one transaction that is rolled back at the end; the counts say what WOULD be
-- deleted. To really delete, pass apply=true:
--
--   psql -h <host> -U <owner> -d qtracker_prod -v ON_ERROR_STOP=1 -f 2026-11-06_cleanup_controls_and_seed_users.sql
--   psql -h <host> -U <owner> -d qtracker_prod -v ON_ERROR_STOP=1 -v apply=true -f 2026-11-06_cleanup_controls_and_seed_users.sql
--
-- Or in pgAdmin: right-click the database qtracker_prod -> PSQL Tool (NOT the Query Tool, which does not know the
-- \if / \set / \echo commands used here), then:
--   \encoding UTF8
--   \i 'C:/MyApps/QTracker/scripts/2026-11-06_cleanup_controls_and_seed_users.sql'      (dry run)
--   \set apply true
--   \i 'C:/MyApps/QTracker/scripts/2026-11-06_cleanup_controls_and_seed_users.sql'      (apply)
-- The path is the file on the computer pgAdmin runs on, with forward slashes. After an ERROR the prompt may read
-- "qtracker_prod=!#": type ROLLBACK; and nothing has changed.
--
-- Before applying: a pg_dump backup, and the application stopped (no one changes data while it runs). The script stops
-- with an error, changing nothing, if no active SoQM Admin Team user would be left.
-- =====================================================================================================================

\set QUIET on
\pset pager off
\pset null '-'
\if :{?apply}
\else
\set apply false
\endif

BEGIN;

CREATE TEMP TABLE cleanup_counts (step int, table_name text, change text, row_count bigint) ON COMMIT DROP;

-- ---------------------------------------------------------------------------------------------------------------- 0
\echo
\echo '=== Before: rows per table ==='
SELECT 'controls' AS table_name, COUNT(*) AS rows FROM controls
UNION ALL SELECT 'control_attachments', COUNT(*) FROM control_attachments
UNION ALL SELECT 'workflow_steps', COUNT(*) FROM workflow_steps
UNION ALL SELECT 'workflow_history', COUNT(*) FROM workflow_history
UNION ALL SELECT 'notifications', COUNT(*) FROM notifications
UNION ALL SELECT 'email_outbox', COUNT(*) FROM email_outbox
UNION ALL SELECT 'admin_audit_log', COUNT(*) FROM admin_audit_log
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'deleted_users', COUNT(*) FROM deleted_users;

-- ---------------------------------------------------------------------------------------------------------------- 1
\echo
\echo '=== 1a. Files on disk to delete BY HAND (not deleted by this script) ==='
\echo 'Under the upload folder of the server (docker-compose: /app/uploads, on Windows C:/MyApps/QTracker/uploads).'
\echo 'A folder is the Control ID with every character other than A-Z a-z 0-9 . _ - made "_" (the database id when'
\echo 'there is no Control ID). As every control goes, the whole upload folder may also be emptied instead.'
WITH files AS (
    SELECT c.id, c.control_id, a.file_name
    FROM control_attachments a JOIN controls c ON c.id = a.control_id
    UNION
    SELECT c.id, c.control_id, TRIM(f.name)
    FROM controls c, LATERAL regexp_split_to_table(COALESCE(c.attachment_details_path, ''), ';') AS f(name)
    WHERE TRIM(f.name) <> ''
    UNION
    SELECT c.id, c.control_id, TRIM(f.name)
    FROM controls c, LATERAL regexp_split_to_table(COALESCE(c.attachment_documents_path, ''), ';') AS f(name)
    WHERE TRIM(f.name) <> ''
)
SELECT COALESCE(NULLIF(regexp_replace(control_id, '[^a-zA-Z0-9._-]', '_', 'g'), ''), id::text) AS folder,
       file_name,
       control_id
FROM files
ORDER BY folder, file_name;

\echo
\echo '=== 1b. Folders of earlier Control IDs (Rename ID) that may hold files too ==='
SELECT DISTINCT regexp_replace(l.control_control_id, '[^a-zA-Z0-9._-]', '_', 'g') AS earlier_folder,
       c.control_id AS control_now
FROM admin_audit_log l JOIN controls c ON c.id = l.control_id
WHERE l.control_control_id IS NOT NULL AND l.control_control_id <> c.control_id
ORDER BY 1;

-- The rows of the controls (every row with a control_id of a control), then the controls themselves
WITH d AS (DELETE FROM control_attachments WHERE control_id IN (SELECT id FROM controls) RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'control_attachments', 'deleted', COUNT(*) FROM d;
WITH d AS (DELETE FROM workflow_steps WHERE control_id IN (SELECT id FROM controls) RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'workflow_steps', 'deleted', COUNT(*) FROM d;
WITH d AS (DELETE FROM workflow_history WHERE control_id IN (SELECT id FROM controls) RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'workflow_history', 'deleted', COUNT(*) FROM d;
WITH d AS (DELETE FROM notifications WHERE control_id IN (SELECT id FROM controls) RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'notifications', 'deleted', COUNT(*) FROM d;
WITH d AS (DELETE FROM email_outbox WHERE control_id IN (SELECT id FROM controls) RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'email_outbox', 'deleted', COUNT(*) FROM d;
WITH d AS (DELETE FROM admin_audit_log WHERE control_id IN (SELECT id FROM controls) RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'admin_audit_log', 'deleted', COUNT(*) FROM d;

-- Tables V27 drops: only where they are still there
DO $$
DECLARE
    n bigint;
BEGIN
    IF to_regclass('public.control_related_controls') IS NOT NULL THEN
        EXECUTE 'DELETE FROM control_related_controls';
        GET DIAGNOSTICS n = ROW_COUNT;
        INSERT INTO cleanup_counts VALUES (1, 'control_related_controls', 'deleted', n);
    END IF;
    IF to_regclass('public.control_notification_log') IS NOT NULL THEN
        EXECUTE 'DELETE FROM control_notification_log';
        GET DIAGNOSTICS n = ROW_COUNT;
        INSERT INTO cleanup_counts VALUES (1, 'control_notification_log', 'deleted', n);
    END IF;
END $$;

UPDATE controls SET created_from_control_id = NULL WHERE created_from_control_id IS NOT NULL;
WITH d AS (DELETE FROM controls RETURNING 1)
INSERT INTO cleanup_counts SELECT 1, 'controls', 'deleted', COUNT(*) FROM d;

-- Rows that named a control that was already gone before (orphans): reported, not deleted
\echo
\echo '=== 1c. Rows left with a control_id of no control (were orphans before; kept, for a look) ==='
SELECT 'notifications' AS table_name, COUNT(*) AS rows FROM notifications WHERE control_id IS NOT NULL
UNION ALL SELECT 'email_outbox', COUNT(*) FROM email_outbox WHERE control_id IS NOT NULL
UNION ALL SELECT 'workflow_history', COUNT(*) FROM workflow_history WHERE control_id IS NOT NULL
UNION ALL SELECT 'admin_audit_log', COUNT(*) FROM admin_audit_log WHERE control_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------- 2
CREATE TEMP TABLE cleanup_candidates ON COMMIT DROP AS
SELECT id, mail, displayname, access_level, access_scope, entra_oid,
       CASE WHEN access_level = 'SOQM' THEN 'SoQM Admin Team'
            WHEN access_scope = 'KDN' THEN 'KDN'
            ELSE 'User · ' || CASE WHEN access_scope = 'ALL' THEN 'All controls' ELSE 'My controls' END
                 || ' · ' || CASE WHEN access_level = 'PARTICIPANT' THEN 'Edit' ELSE 'Read Only' END
       END AS role
FROM users
WHERE LOWER(TRIM(mail)) LIKE '%@qtracker.local';

\echo
\echo '=== 2a. Candidates: users with an e-mail on @qtracker.local ==='
SELECT mail AS email, role, entra_oid, CASE WHEN COALESCE(TRIM(entra_oid), '') = '' THEN 'delete' ELSE 'KEEP' END AS action
FROM cleanup_candidates ORDER BY mail;

\echo
\echo '=== 2b. NOT deleted: candidates with an Entra object id (signed in through SSO) - check them by hand ==='
SELECT mail AS email, role, entra_oid FROM cleanup_candidates WHERE COALESCE(TRIM(entra_oid), '') <> '' ORDER BY mail;

CREATE TEMP TABLE cleanup_users ON COMMIT DROP AS
SELECT * FROM cleanup_candidates WHERE COALESCE(TRIM(entra_oid), '') = '';

-- Never leave QTracker without an active SoQM Admin Team member
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users
                   WHERE access_level = 'SOQM' AND enabled = TRUE AND id NOT IN (SELECT id FROM cleanup_users)) THEN
        RAISE EXCEPTION 'No active SoQM Admin Team user would be left: nothing is deleted';
    END IF;
END $$;

-- A control row would point at them (none is left after part 1; kept for safety, as the Admin Panel does)
UPDATE controls SET created_by_email = COALESCE(NULLIF(created_by_email, ''), u.mail),
                    created_by_name = COALESCE(NULLIF(created_by_name, ''), u.displayname),
                    created_by = NULL
FROM cleanup_users u WHERE controls.created_by = u.id;

-- Their own in-app notifications
WITH d AS (DELETE FROM notifications WHERE user_id IN (SELECT id FROM cleanup_users) RETURNING 1)
INSERT INTO cleanup_counts SELECT 2, 'notifications', 'deleted', COUNT(*) FROM d;

-- Their rows in the old backup tables (V8, V11, V22), where those tables are still there (V27 drops them)
DO $$
DECLARE
    t text;
    n bigint;
BEGIN
    FOREACH t IN ARRAY ARRAY['users_role_backup', 'user_access_v8_backup', 'user_admin_access_v11_backup'] LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('DELETE FROM %I WHERE user_id IN (SELECT id FROM cleanup_users)', t);
            GET DIAGNOSTICS n = ROW_COUNT;
            INSERT INTO cleanup_counts VALUES (2, t, 'deleted', n);
        END IF;
    END LOOP;
END $$;

-- The record that they were deleted (as Delete user in the Admin Panel)
WITH r AS (
    INSERT INTO deleted_users (user_id, mail, display_name, role, deleted_at, deleted_by_email)
    SELECT id, mail, displayname, role, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Almaty'), 'cleanup-script'
    FROM cleanup_users
    RETURNING 1)
INSERT INTO cleanup_counts SELECT 2, 'deleted_users', 'added', COUNT(*) FROM r;

WITH d AS (DELETE FROM users WHERE id IN (SELECT id FROM cleanup_users) RETURNING 1)
INSERT INTO cleanup_counts SELECT 2, 'users', 'deleted', COUNT(*) FROM d;

-- ---------------------------------------------------------------------------------------------------------------- end
\echo
\if :apply
\echo '=== Rows per table (APPLIED) ==='
\else
\echo '=== Rows per table that WOULD change (DRY RUN, rolled back) ==='
\endif
SELECT step, table_name, change, row_count FROM cleanup_counts ORDER BY step, change DESC, table_name;
SELECT SUM(row_count) FILTER (WHERE change = 'deleted') AS total_deleted_rows FROM cleanup_counts;

\echo
\echo '=== After: rows per table ==='
SELECT 'controls' AS table_name, COUNT(*) AS rows FROM controls
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'users @qtracker.local left', COUNT(*) FROM users WHERE LOWER(TRIM(mail)) LIKE '%@qtracker.local'
UNION ALL SELECT 'related_control_ref (kept)', COUNT(*) FROM related_control_ref
UNION ALL SELECT 'notification_template (kept)', COUNT(*) FROM notification_template;

\if :apply
COMMIT;
\echo 'APPLIED: committed. Now delete the files listed in 1a / 1b (or empty the upload folder).'
\else
ROLLBACK;
\echo 'DRY RUN: rolled back, nothing changed. Run again with -v apply=true to delete.'
\endif
