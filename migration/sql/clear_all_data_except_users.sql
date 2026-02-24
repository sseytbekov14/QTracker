-- =====================================================
-- Clear all data from all tables EXCEPT users
-- Run date: 2026-02-23
-- =====================================================

-- Disable constraints temporarily for clean truncation
-- Order: dependent tables first, then parent tables

-- 1. Tables that may reference controls
TRUNCATE TABLE control_notification_log CASCADE;
TRUNCATE TABLE workflow_history CASCADE;
TRUNCATE TABLE workflow_steps CASCADE;
TRUNCATE TABLE notifications CASCADE;
TRUNCATE TABLE admin_audit_log CASCADE;

-- 2. Main controls table
TRUNCATE TABLE controls CASCADE;

-- Verify
SELECT 'controls' AS table_name, COUNT(*) AS row_count FROM controls
UNION ALL
SELECT 'workflow_history', COUNT(*) FROM workflow_history
UNION ALL
SELECT 'workflow_steps', COUNT(*) FROM workflow_steps
UNION ALL
SELECT 'notifications', COUNT(*) FROM notifications
UNION ALL
SELECT 'control_notification_log', COUNT(*) FROM control_notification_log
UNION ALL
SELECT 'admin_audit_log', COUNT(*) FROM admin_audit_log
UNION ALL
SELECT 'users (NOT cleared)', COUNT(*) FROM users;
