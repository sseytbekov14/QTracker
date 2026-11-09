-- QTracker clean-up, step 2 of 2: DELETE. Run cleanup_1_preview.sql first.
-- Before: a database backup (pg_dump, or pgAdmin -> Backup...) and the application stopped.
-- pgAdmin -> database qtracker_prod -> Query Tool -> open this file -> F5 (Execute the whole file).
--
-- One statement: everything is deleted, or on any error nothing at all. The Messages tab lists the rows deleted per
-- table and ends with "Clean-up done". It deletes:
--   1. every control with all of its rows: attachments (rows only - the files on disk are deleted by hand, see the
--      preview), workflow steps and history, in-app notifications, e-mails, its audit entries;
--   2. the users with an e-mail ending in @qtracker.local (the made-up seed accounts) that have no Entra object id,
--      with their own in-app notifications; each gets a row in deleted_users, as the Admin Panel's Delete user does.
-- History stays: admin_audit_log and e-mails not tied to a control. Not touched: related_control_ref (Other Related
-- Controls), notification_template, flyway_schema_history, every other user.
-- It refuses (nothing deleted) when no active SoQM Admin Team member would be left.

DO $$
DECLARE
    n bigint;
    seed_ids bigint[];
    t text;
BEGIN
    SELECT COALESCE(array_agg(id), '{}') INTO seed_ids
    FROM users
    WHERE LOWER(TRIM(mail)) LIKE '%@qtracker.local' AND COALESCE(TRIM(entra_oid), '') = '';

    IF NOT EXISTS (SELECT 1 FROM users
                   WHERE access_level = 'SOQM' AND enabled = TRUE AND NOT (id = ANY (seed_ids))) THEN
        RAISE EXCEPTION 'No active SoQM Admin Team user would be left: nothing deleted';
    END IF;

    -- 1. Every control and its rows
    DELETE FROM control_attachments WHERE control_id IN (SELECT id FROM controls);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'control_attachments: % deleted', n;
    DELETE FROM workflow_steps WHERE control_id IN (SELECT id FROM controls);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'workflow_steps: % deleted', n;
    DELETE FROM workflow_history WHERE control_id IN (SELECT id FROM controls);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'workflow_history: % deleted', n;
    DELETE FROM notifications WHERE control_id IN (SELECT id FROM controls);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'notifications (of controls): % deleted', n;
    DELETE FROM email_outbox WHERE control_id IN (SELECT id FROM controls);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'email_outbox: % deleted', n;
    DELETE FROM admin_audit_log WHERE control_id IN (SELECT id FROM controls);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'admin_audit_log (of controls): % deleted', n;
    -- Tables a database before V27 still has
    FOREACH t IN ARRAY ARRAY['control_related_controls', 'control_notification_log'] LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('DELETE FROM %I', t);
            GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE '%: % deleted', t, n;
        END IF;
    END LOOP;
    UPDATE controls SET created_from_control_id = NULL WHERE created_from_control_id IS NOT NULL;
    DELETE FROM controls;
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'controls: % deleted', n;

    -- 2. The seed users @qtracker.local without an Entra object id
    DELETE FROM notifications WHERE user_id = ANY (seed_ids);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'notifications (of the deleted users): % deleted', n;
    FOREACH t IN ARRAY ARRAY['users_role_backup', 'user_access_v8_backup', 'user_admin_access_v11_backup'] LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('DELETE FROM %I WHERE user_id = ANY ($1)', t) USING seed_ids;
            GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE '%: % deleted', t, n;
        END IF;
    END LOOP;
    INSERT INTO deleted_users (user_id, mail, display_name, role, deleted_at, deleted_by_email)
    SELECT id, mail, displayname,
           CASE WHEN access_level = 'SOQM' THEN 'SoQM Admin Team'
                WHEN access_scope = 'KDN' THEN 'KDN'
                ELSE 'User · ' || CASE WHEN access_scope = 'ALL' THEN 'All controls' ELSE 'My controls' END
                     || ' · ' || CASE WHEN access_level = 'PARTICIPANT' THEN 'Edit' ELSE 'Read Only' END
           END,
           (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Almaty'), 'cleanup-script'
    FROM users WHERE id = ANY (seed_ids);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'deleted_users: % added', n;
    DELETE FROM users WHERE id = ANY (seed_ids);
    GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'users: % deleted', n;

    RAISE NOTICE 'Clean-up done. Now delete the content of C:\MyApps\QTracker\uploads and start the application.';
END $$;
