-- Undo of V26__Notification_titles.sql, run by hand (Flyway does not undo): the three names come back where V26's are
-- still there.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '26';

UPDATE notification_template SET subject = 'Control Reminder', updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T3' AND subject = 'Awaiting your action';

UPDATE notification_template SET subject = 'Control Deadline Today', updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T4' AND subject = 'Complete by the end of the day';

UPDATE notification_template SET subject = 'Control Deadline Notification', updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T5' AND subject = 'Control not completed';
