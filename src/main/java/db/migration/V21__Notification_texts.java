package db.migration;

import com.kpmg.qtracker.service.notification.Template;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The shorter texts of 2026-10-20 (T1..T12 as {@link Template} holds them: "Dear {NAME},", {ROLES} in T1 and T2, {LINK}
 * on a line of its own). A key is changed only while its subject and body are still those V20 wrote: a text changed
 * by hand in the database is left as it is. Undo by hand: db/rollback/V21__undo_notification_texts.sql.
 */
public class V21__Notification_texts extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Almaty")));
        try (PreparedStatement update = context.getConnection().prepareStatement(
                "UPDATE notification_template SET subject = ?, body = ?, updated_at = ?"
                        + " WHERE template_key = ? AND subject = ? AND body = ?")) {
            for (Template template : Template.values()) {
                String[] before = V20__Notification_templates.TEXTS.get(template.name());
                update.setString(1, template.defaultSubject());
                update.setString(2, template.defaultBody());
                update.setTimestamp(3, now);
                update.setString(4, template.name());
                update.setString(5, before[0]);
                update.setString(6, before[1]);
                update.executeUpdate();
            }
        }
    }
}
