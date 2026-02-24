-- Drop unused tables: notification_reads and workflow_comments
-- These tables are not used by the application.
-- notification_reads: read status is tracked via notifications.is_read column
-- workflow_comments: comments are stored in workflow_history.comments column

DROP TABLE IF EXISTS notification_reads;
DROP TABLE IF EXISTS workflow_comments;
