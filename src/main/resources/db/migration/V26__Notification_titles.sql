-- The subject of an e-mail is now "[QTracker] Prefix: Control ID: step or event" (2026-11-02), built by TemplateRenderer;
-- this column holds the last part, which is also the title of the e-mail and of the in-app notification. Three names
-- would only repeat their prefix ("Reminder: ...: Control Reminder"), so they say the event instead. A name changed by
-- hand stays. Undo by hand: db/rollback/V26__undo_notification_titles.sql.

UPDATE notification_template SET subject = 'Awaiting your action', updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T3' AND subject = 'Control Reminder';

UPDATE notification_template SET subject = 'Complete by the end of the day', updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T4' AND subject = 'Control Deadline Today';

UPDATE notification_template SET subject = 'Control not completed', updated_at = CURRENT_TIMESTAMP
WHERE template_key = 'T5' AND subject = 'Control Deadline Notification';
