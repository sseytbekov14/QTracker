package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fills notification_template (V19) with the texts of NOTIFICATIONS_v5, T1..T12, word for word. A key already there is
 * left as it is. The texts are kept here as they were (V21 replaces them with the texts of 2026-10-20 and needs these
 * to tell them from texts changed by hand). Undo by hand: db/rollback/V19__undo_email_outbox.sql.
 */
public class V20__Notification_templates extends BaseJavaMigration {

    /** Subject and body of each key, as V20 wrote them. */
    public static final Map<String, String[]> TEXTS = new LinkedHashMap<>();

    static {
        TEXTS.put("T1", new String[]{"The creation of the SoQM control", """
                Dear Facilitator, Control Operator and Process Owner,

                The scheduled control {CONTROL_ID} {CONTROL_NAME} has been activated today. The occurrence date is {OCCURRENCE_DATE}. Kindly complete and submit the control as required before the deadline {DEADLINE}.

                You can access the control using the following link: {LINK}

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T2", new String[]{"The activation of the SoQM control", """
                Dear Facilitator, Control Operator and Process Owner,

                Please be aware that the control {CONTROL_ID} {CONTROL_NAME} occurred today on {OCCURRENCE_DATE}. Kindly complete and submit the control as required before the deadline {DEADLINE}.

                You can access the control using the following link: {LINK}

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T3", new String[]{"Control Reminder", """
                Dear {ROLE},

                We kindly remind you that the control {CONTROL_ID} {CONTROL_NAME} is pending your action. You can access the control using the following link: {LINK}

                The deadline of the control is {DEADLINE}.

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T4", new String[]{"Control Deadline Today", """
                Dear {ROLE},

                Please be aware that the deadline for the following control is today, {DEADLINE}:
                {CONTROL_ID} {CONTROL_NAME}

                Please ensure this control is completed by the end of the day. You can access and complete the control using the link below:
                {LINK}

                If you have any questions or require assistance, please let us know.

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T5", new String[]{"Control Deadline Notification", """
                Dear {ROLE},

                Our records indicate that the control completion deadline ({DEADLINE}) has passed.
                The following control remains incomplete: {CONTROL_ID} {CONTROL_NAME}

                Please ensure this control is completed as soon as possible. You can access and complete the control using the link below:
                {LINK}

                If you have any questions or require assistance, please let us know.

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T6", new String[]{"Control Operator Review", """
                Dear Control Operator,

                The control {CONTROL_ID} {CONTROL_NAME} has been sent for your review. Please kindly review and submit the control for the 'SoQM Admin Team review' using the link below:
                {LINK}

                The deadline of the control is {DEADLINE}.

                If you have any questions or require assistance, please let us know.

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T7", new String[]{"Control sent back to Facilitator", """
                Dear Facilitator,

                The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the {RETURNED_BY} and requires some adjustments.

                Please click the link below to view the comments and feedback for this control:
                {LINK}

                The deadline of the control is {DEADLINE}.

                Thank you for your cooperation.
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T8", new String[]{"SoQM Admin Team Review", """
                Dear SoQM Admin Team,

                The control {CONTROL_ID} {CONTROL_NAME} has been sent for your review. Please kindly review and submit the control for the 'Process Owner review' using the link below:
                {LINK}

                Thank you,
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T9", new String[]{"Control sent back to Control Operator", """
                Dear Control Operator,

                The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and requires some adjustments.

                Please click the link below to view the comments and feedback for this control:
                {LINK}

                The deadline of the control is {DEADLINE}.

                Thank you for your cooperation.
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T10", new String[]{"Process Owner Review", """
                Dear Process Owner,

                The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and is now ready for your review.

                Please kindly review and complete the control using the following link:
                {LINK}

                The deadline of the control is {DEADLINE}.

                Thank you for your attention.
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T11", new String[]{"Control sent back to Control Operator", """
                Dear Control Operator,

                The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the Process Owner and requires some adjustments.

                Please click the link below to view the comments and feedback for this control:
                {LINK}

                The deadline of the control is {DEADLINE}.

                Thank you for your cooperation.
                Kind regards,
                SoQM Team"""});
        TEXTS.put("T12", new String[]{"SoQM control completed", """
                Dear Facilitator, Control Operator, SoQM Admin Team and Process Owner,

                Please note that the control {CONTROL_ID} {CONTROL_NAME} has been successfully completed in the system.

                Kind regards,
                SoQM Team"""});
    }

    @Override
    public void migrate(Context context) throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Almaty")));
        try (PreparedStatement insert = context.getConnection().prepareStatement(
                "INSERT INTO notification_template (template_key, subject, body, updated_at) SELECT ?, ?, ?, ?"
                        + " WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE template_key = ?)")) {
            for (Map.Entry<String, String[]> text : TEXTS.entrySet()) {
                insert.setString(1, text.getKey());
                insert.setString(2, text.getValue()[0]);
                insert.setString(3, text.getValue()[1]);
                insert.setTimestamp(4, now);
                insert.setString(5, text.getKey());
                insert.executeUpdate();
            }
        }
    }
}
