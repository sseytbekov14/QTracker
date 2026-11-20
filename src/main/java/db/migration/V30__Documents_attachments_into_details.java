package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Documents tab of View Control goes (2026-11-20): every file uploaded there is shown in the Attachment block of
 * Details. Only copies, nothing is deleted or moved:
 * <ul>
 * <li>controls.attachment_details_path gets each name of attachment_documents_path it does not list yet, in the same
 * order; attachment_documents_path is left as it was (the record of the old tab);</li>
 * <li>control_attachments gets a DETAILS row for each DOCUMENTS row (same control, file name, author, stage and upload
 * time) unless the control already has a DETAILS row of that name; the DOCUMENTS rows stay.</li>
 * </ul>
 * The files themselves are not touched: both tabs always shared the control's folder, so the same name is the same
 * file. Safe to run again: a name already listed and a row already there are skipped. Written in Java like V5 so the
 * same code runs on PostgreSQL and on the H2 database of the tests.
 * <p>
 * Undo by hand (only what this migration added): for each control, remove from attachment_details_path the names
 * that are in attachment_documents_path but were not in Details before, and
 * DELETE FROM control_attachments d WHERE d.tab = 'DETAILS' AND EXISTS (SELECT 1 FROM control_attachments o
 * WHERE o.tab = 'DOCUMENTS' AND o.control_id = d.control_id AND o.file_name = d.file_name
 * AND o.uploaded_at IS NOT DISTINCT FROM d.uploaded_at) - check each case first: a file uploaded to both tabs under
 * one name before V30 also matches.
 */
public class V30__Documents_attachments_into_details extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        mergeLists(connection);
        copyRecords(connection);
    }

    /** Details list + the Documents names it lacks. */
    private void mergeLists(Connection connection) throws SQLException {
        List<Object[]> updates = new ArrayList<>();
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("SELECT id, attachment_details_path, attachment_documents_path"
                     + " FROM controls WHERE attachment_documents_path IS NOT NULL")) {
            while (rows.next()) {
                String details = rows.getString("attachment_details_path");
                String merged = merge(details, rows.getString("attachment_documents_path"));
                if (merged != null && !merged.equals(details)) {
                    updates.add(new Object[] {rows.getLong("id"), merged});
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE controls SET attachment_details_path = ? WHERE id = ?")) {
            for (Object[] row : updates) {
                update.setString(1, (String) row[1]);
                update.setLong(2, (Long) row[0]);
                update.addBatch();
            }
            if (!updates.isEmpty()) {
                update.executeBatch();
            }
        }
    }

    /**
     * The Details list with each Documents name it does not hold appended (';'-separated, trimmed, once each); the
     * Details list as stored when nothing is added.
     */
    public static String merge(String details, String documents) {
        Set<String> listed = names(details);
        Set<String> added = new LinkedHashSet<>();
        for (String name : names(documents)) {
            if (!listed.contains(name)) {
                added.add(name);
            }
        }
        if (added.isEmpty()) {
            return details;
        }
        List<String> all = new ArrayList<>(listed);
        all.addAll(added);
        return String.join(";", all);
    }

    private static Set<String> names(String list) {
        Set<String> names = new LinkedHashSet<>();
        if (list == null) {
            return names;
        }
        for (String part : list.split(";")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** A DETAILS record for each DOCUMENTS record, the author, stage and upload time kept. */
    private void copyRecords(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO control_attachments"
                    + " (control_id, tab, file_name, uploaded_by_email, uploaded_stage, uploaded_at)"
                    + " SELECT d.control_id, 'DETAILS', d.file_name, d.uploaded_by_email, d.uploaded_stage, d.uploaded_at"
                    + " FROM control_attachments d WHERE d.tab = 'DOCUMENTS'"
                    + " AND NOT EXISTS (SELECT 1 FROM control_attachments x WHERE x.control_id = d.control_id"
                    + " AND x.tab = 'DETAILS' AND x.file_name = d.file_name)");
        }
    }
}
