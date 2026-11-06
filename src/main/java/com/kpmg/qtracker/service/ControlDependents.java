package com.kpmg.qtracker.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The rows a control has outside its own row, in one place for everything that removes controls for good (Data
 * retention after seven years, Deactivate control): its attachments, workflow steps and history, in-app notifications,
 * e-mails (sent and still to send, its reminders included) and audit entries. Details and Assignment are columns of
 * the control's own row. A next cycle made from a removed control stays and only loses the link to it
 * ({@code created_from_control_id}); Other Related Controls hold Control IDs of the reference list as text, not links
 * to rows. Nothing here commits: the caller's transaction does.
 */
public final class ControlDependents {

    /** The tables with a control_id column holding the control's database id. */
    public static final List<String> TABLES = List.of("control_attachments", "workflow_steps", "workflow_history",
            "notifications", "email_outbox", "admin_audit_log");

    private ControlDependents() {
    }

    /** How many rows each table holds for the controls, the controls themselves first ("controls"). */
    public static Map<String, Integer> count(JdbcTemplate jdbc, List<Long> ids) {
        Map<String, Integer> rows = new LinkedHashMap<>();
        rows.put("controls", ids.size());
        String in = in(ids);
        for (String table : TABLES) {
            Integer count = in == null ? Integer.valueOf(0)
                    : jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE control_id IN (" + in + ")",
                    Integer.class);
            rows.put(table, count == null ? 0 : count);
        }
        return rows;
    }

    /** Deletes the controls and their rows; a next cycle made from one of them keeps its row and loses the link. */
    public static void delete(JdbcTemplate jdbc, List<Long> ids) {
        String in = in(ids);
        if (in == null) {
            return;
        }
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table + " WHERE control_id IN (" + in + ")");
        }
        // V14 says ON DELETE SET NULL; done here too, so it holds whatever the schema
        jdbc.update("UPDATE controls SET created_from_control_id = NULL WHERE created_from_control_id IN (" + in + ")");
        jdbc.update("DELETE FROM controls WHERE id IN (" + in + ")");
    }

    /** The ids as a SQL list (numbers only), or null for none. */
    private static String in(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream().map(id -> String.valueOf(id.longValue())).collect(Collectors.joining(","));
    }
}
