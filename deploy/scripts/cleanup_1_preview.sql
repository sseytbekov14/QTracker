-- QTracker clean-up, step 1 of 2: PREVIEW. Changes nothing.
-- pgAdmin -> database qtracker_prod -> Query Tool. Select ONE query below with the mouse and press F5 (Execute):
-- the Query Tool shows the result of one query at a time. Then run cleanup_2_delete.sql.

-- 1. How many rows there are now
SELECT 'controls' AS table_name, COUNT(*) AS rows FROM controls
UNION ALL SELECT 'control_attachments', COUNT(*) FROM control_attachments
UNION ALL SELECT 'workflow_steps', COUNT(*) FROM workflow_steps
UNION ALL SELECT 'workflow_history', COUNT(*) FROM workflow_history
UNION ALL SELECT 'notifications', COUNT(*) FROM notifications
UNION ALL SELECT 'email_outbox', COUNT(*) FROM email_outbox
UNION ALL SELECT 'admin_audit_log', COUNT(*) FROM admin_audit_log
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'deleted_users', COUNT(*) FROM deleted_users;

-- 2. The users @qtracker.local: "delete", or "KEEP" for one with an Entra object id (signed in through SSO)
SELECT mail, displayname, access_level, access_scope, entra_oid,
       CASE WHEN COALESCE(TRIM(entra_oid), '') = '' THEN 'delete' ELSE 'KEEP' END AS action
FROM users
WHERE LOWER(TRIM(mail)) LIKE '%@qtracker.local'
ORDER BY mail;

-- 3. Active SoQM Admin Team members who stay: must be at least 1, or step 2 refuses to run
SELECT mail, displayname
FROM users
WHERE access_level = 'SOQM' AND enabled = TRUE AND LOWER(TRIM(mail)) NOT LIKE '%@qtracker.local'
ORDER BY mail;

-- 4. The files on disk to delete by hand after step 2 (folder under C:\MyApps\QTracker\uploads, file name).
--    As every control goes, the whole content of the uploads folder may simply be deleted instead.
SELECT DISTINCT COALESCE(NULLIF(regexp_replace(c.control_id, '[^a-zA-Z0-9._-]', '_', 'g'), ''), c.id::text) AS folder,
       f.file_name, c.control_id
FROM controls c
JOIN LATERAL (
    SELECT a.file_name FROM control_attachments a WHERE a.control_id = c.id
    UNION SELECT TRIM(x) FROM regexp_split_to_table(COALESCE(c.attachment_details_path, ''), ';') AS x WHERE TRIM(x) <> ''
    UNION SELECT TRIM(x) FROM regexp_split_to_table(COALESCE(c.attachment_documents_path, ''), ';') AS x WHERE TRIM(x) <> ''
) f ON TRUE
ORDER BY folder, f.file_name;
