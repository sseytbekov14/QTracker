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
 * The shorter texts of 2026-10-20 ("Dear {NAME},", {ROLES} in T1 and T2, {LINK} on a line of its own). A key is changed
 * only while its subject and body are still those V20 wrote: a text changed by hand in the database is left as it is.
 * The texts are kept here as they were: the undo script looks for them, and later versions change them (V25). Undo by
 * hand: db/rollback/V21__undo_notification_texts.sql.
 */
public class V21__Notification_texts extends BaseJavaMigration {

    /** Subject and body of each key, as V21 wrote them. */
    public static final Map<String, String[]> TEXTS = new LinkedHashMap<>();

    static {
        TEXTS.put("T1", new String[]{"The creation of the SoQM control", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been created.
                Your role: {ROLES}
                Occurrence date: {OCCURRENCE_DATE}
                Deadline: {DEADLINE}
                Please complete and submit the control before the deadline.
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T2", new String[]{"The activation of the SoQM control", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} occurs today ({OCCURRENCE_DATE}).
                Your role: {ROLES}
                Deadline: {DEADLINE}
                Please complete and submit the control before the deadline.
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T3", new String[]{"Control Reminder", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} is awaiting your action.
                Deadline: {DEADLINE}
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T4", new String[]{"Control Deadline Today", """
                Dear {NAME},

                The deadline for control {CONTROL_ID} {CONTROL_NAME} is today ({DEADLINE}).
                Please complete it by the end of the day.
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T5", new String[]{"Control Deadline Notification", """
                Dear {NAME},

                The deadline for control {CONTROL_ID} {CONTROL_NAME} has passed ({DEADLINE}). The control is not completed.
                Please complete it as soon as possible.
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T6", new String[]{"Control Operator Review", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been sent for your review.
                Please review it and submit it to the SoQM Admin Team.
                Deadline: {DEADLINE}
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T7", new String[]{"Control sent back to Facilitator", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the {RETURNED_BY} for rework.
                Please review the comments and resubmit the control.
                Deadline: {DEADLINE}
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T8", new String[]{"SoQM Admin Team Review", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been sent for your review.
                Please review it and submit it to the Process Owner.
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T9", new String[]{"Control sent back to Control Operator", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the SoQM Admin Team for rework.
                Please review the comments and resubmit the control.
                Deadline: {DEADLINE}
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T10", new String[]{"Process Owner Review", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and is ready for your review.
                Please review and complete the control.
                Deadline: {DEADLINE}
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T11", new String[]{"Control sent back to Control Operator", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the Process Owner for rework.
                Please review the comments and resubmit the control.
                Deadline: {DEADLINE}
                {LINK}

                Kind regards,
                SoQM Team"""});
        TEXTS.put("T12", new String[]{"SoQM control completed", """
                Dear {NAME},

                Control {CONTROL_ID} {CONTROL_NAME} has been completed.
                {LINK}

                Kind regards,
                SoQM Team"""});
    }

    @Override
    public void migrate(Context context) throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Almaty")));
        try (PreparedStatement update = context.getConnection().prepareStatement(
                "UPDATE notification_template SET subject = ?, body = ?, updated_at = ?"
                        + " WHERE template_key = ? AND subject = ? AND body = ?")) {
            for (Map.Entry<String, String[]> text : TEXTS.entrySet()) {
                String[] before = V20__Notification_templates.TEXTS.get(text.getKey());
                update.setString(1, text.getValue()[0]);
                update.setString(2, text.getValue()[1]);
                update.setTimestamp(3, now);
                update.setString(4, text.getKey());
                update.setString(5, before[0]);
                update.setString(6, before[1]);
                update.executeUpdate();
            }
        }
    }
}
