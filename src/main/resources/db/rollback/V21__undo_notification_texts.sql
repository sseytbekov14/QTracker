-- Undo of V21__Notification_texts.java, run by hand (Flyway does not undo): the texts of NOTIFICATIONS_v5 (V20)
-- come back for every key that still has the text V21 wrote; a text changed by hand since is left as it is.
-- Line breaks are CHR(10), so the script means the same from a checkout with CRLF.
-- Needed only to run a QTracker version older than V21 on this database.
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '21';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'The creation of the SoQM control',
    body = 'Dear Facilitator, Control Operator and Process Owner,' || CHR(10)
    || '' || CHR(10)
    || 'The scheduled control {CONTROL_ID} {CONTROL_NAME} has been activated today. The occurrence date is {OCCURRENCE_DATE}. Kindly complete and submit the control as required before the deadline {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'You can access the control using the following link: {LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T1'
    AND subject = 'The creation of the SoQM control'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been created.' || CHR(10)
    || 'Your role: {ROLES}' || CHR(10)
    || 'Occurrence date: {OCCURRENCE_DATE}' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || 'Please complete and submit the control before the deadline.' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'The activation of the SoQM control',
    body = 'Dear Facilitator, Control Operator and Process Owner,' || CHR(10)
    || '' || CHR(10)
    || 'Please be aware that the control {CONTROL_ID} {CONTROL_NAME} occurred today on {OCCURRENCE_DATE}. Kindly complete and submit the control as required before the deadline {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'You can access the control using the following link: {LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T2'
    AND subject = 'The activation of the SoQM control'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} occurs today ({OCCURRENCE_DATE}).' || CHR(10)
    || 'Your role: {ROLES}' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || 'Please complete and submit the control before the deadline.' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control Reminder',
    body = 'Dear {ROLE},' || CHR(10)
    || '' || CHR(10)
    || 'We kindly remind you that the control {CONTROL_ID} {CONTROL_NAME} is pending your action. You can access the control using the following link: {LINK}' || CHR(10)
    || '' || CHR(10)
    || 'The deadline of the control is {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T3'
    AND subject = 'Control Reminder'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} is awaiting your action.' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control Deadline Today',
    body = 'Dear {ROLE},' || CHR(10)
    || '' || CHR(10)
    || 'Please be aware that the deadline for the following control is today, {DEADLINE}:' || CHR(10)
    || '{CONTROL_ID} {CONTROL_NAME}' || CHR(10)
    || '' || CHR(10)
    || 'Please ensure this control is completed by the end of the day. You can access and complete the control using the link below:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'If you have any questions or require assistance, please let us know.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T4'
    AND subject = 'Control Deadline Today'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'The deadline for control {CONTROL_ID} {CONTROL_NAME} is today ({DEADLINE}).' || CHR(10)
    || 'Please complete it by the end of the day.' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control Deadline Notification',
    body = 'Dear {ROLE},' || CHR(10)
    || '' || CHR(10)
    || 'Our records indicate that the control completion deadline ({DEADLINE}) has passed.' || CHR(10)
    || 'The following control remains incomplete: {CONTROL_ID} {CONTROL_NAME}' || CHR(10)
    || '' || CHR(10)
    || 'Please ensure this control is completed as soon as possible. You can access and complete the control using the link below:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'If you have any questions or require assistance, please let us know.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T5'
    AND subject = 'Control Deadline Notification'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'The deadline for control {CONTROL_ID} {CONTROL_NAME} has passed ({DEADLINE}). The control is not completed.' || CHR(10)
    || 'Please complete it as soon as possible.' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control Operator Review',
    body = 'Dear Control Operator,' || CHR(10)
    || '' || CHR(10)
    || 'The control {CONTROL_ID} {CONTROL_NAME} has been sent for your review. Please kindly review and submit the control for the ''SoQM Admin Team review'' using the link below:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'The deadline of the control is {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'If you have any questions or require assistance, please let us know.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T6'
    AND subject = 'Control Operator Review'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been sent for your review.' || CHR(10)
    || 'Please review it and submit it to the SoQM Admin Team.' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control sent back to Facilitator',
    body = 'Dear Facilitator,' || CHR(10)
    || '' || CHR(10)
    || 'The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the {RETURNED_BY} and requires some adjustments.' || CHR(10)
    || '' || CHR(10)
    || 'Please click the link below to view the comments and feedback for this control:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'The deadline of the control is {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you for your cooperation.' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T7'
    AND subject = 'Control sent back to Facilitator'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the {RETURNED_BY} for rework.' || CHR(10)
    || 'Please review the comments and resubmit the control.' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'SoQM Admin Team Review',
    body = 'Dear SoQM Admin Team,' || CHR(10)
    || '' || CHR(10)
    || 'The control {CONTROL_ID} {CONTROL_NAME} has been sent for your review. Please kindly review and submit the control for the ''Process Owner review'' using the link below:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Thank you,' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T8'
    AND subject = 'SoQM Admin Team Review'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been sent for your review.' || CHR(10)
    || 'Please review it and submit it to the Process Owner.' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control sent back to Control Operator',
    body = 'Dear Control Operator,' || CHR(10)
    || '' || CHR(10)
    || 'The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and requires some adjustments.' || CHR(10)
    || '' || CHR(10)
    || 'Please click the link below to view the comments and feedback for this control:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'The deadline of the control is {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you for your cooperation.' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T9'
    AND subject = 'Control sent back to Control Operator'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the SoQM Admin Team for rework.' || CHR(10)
    || 'Please review the comments and resubmit the control.' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Process Owner Review',
    body = 'Dear Process Owner,' || CHR(10)
    || '' || CHR(10)
    || 'The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and is now ready for your review.' || CHR(10)
    || '' || CHR(10)
    || 'Please kindly review and complete the control using the following link:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'The deadline of the control is {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you for your attention.' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T10'
    AND subject = 'Process Owner Review'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and is ready for your review.' || CHR(10)
    || 'Please review and complete the control.' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'Control sent back to Control Operator',
    body = 'Dear Control Operator,' || CHR(10)
    || '' || CHR(10)
    || 'The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the Process Owner and requires some adjustments.' || CHR(10)
    || '' || CHR(10)
    || 'Please click the link below to view the comments and feedback for this control:' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'The deadline of the control is {DEADLINE}.' || CHR(10)
    || '' || CHR(10)
    || 'Thank you for your cooperation.' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T11'
    AND subject = 'Control sent back to Control Operator'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the Process Owner for rework.' || CHR(10)
    || 'Please review the comments and resubmit the control.' || CHR(10)
    || 'Deadline: {DEADLINE}' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';

UPDATE notification_template SET updated_at = CURRENT_TIMESTAMP,
    subject = 'SoQM control completed',
    body = 'Dear Facilitator, Control Operator, SoQM Admin Team and Process Owner,' || CHR(10)
    || '' || CHR(10)
    || 'Please note that the control {CONTROL_ID} {CONTROL_NAME} has been successfully completed in the system.' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team'
WHERE template_key = 'T12'
    AND subject = 'SoQM control completed'
    AND body = 'Dear {NAME},' || CHR(10)
    || '' || CHR(10)
    || 'Control {CONTROL_ID} {CONTROL_NAME} has been completed.' || CHR(10)
    || '{LINK}' || CHR(10)
    || '' || CHR(10)
    || 'Kind regards,' || CHR(10)
    || 'SoQM Team';
