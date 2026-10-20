package db.migration;

import com.kpmg.qtracker.service.notification.Template;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Fills notification_template (V19) with the texts of NOTIFICATIONS_v5, T1..T12, as {@link Template} holds them (the
 * one copy of the texts in the code). A key already there is left as it is. Undo by hand:
 * db/rollback/V19__undo_email_outbox.sql.
 */
public class V20__Notification_templates extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Almaty")));
        try (PreparedStatement insert = context.getConnection().prepareStatement(
                "INSERT INTO notification_template (template_key, subject, body, updated_at) SELECT ?, ?, ?, ?"
                        + " WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE template_key = ?)")) {
            for (Template template : Template.values()) {
                insert.setString(1, template.name());
                insert.setString(2, template.defaultSubject());
                insert.setString(3, template.defaultBody());
                insert.setTimestamp(4, now);
                insert.setString(5, template.name());
                insert.executeUpdate();
            }
        }
    }
}
