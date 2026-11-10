package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.FieldChangeDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Audit trail section of the Admin Panel (business decision of 2026-11-10): every entry of admin_audit_log, newest
 * first, in three groups - Users, Controls (their fields, Control ID, workflow steps, files, deactivation) and System -
 * with who did it, when, on which person or control, and what changed (before and after, field by field). Filters and
 * paging run in the database. It only reads. Before it, the Users section showed the latest 100 entries per type.
 */
@Service
public class AuditLog {

    public static final int PAGE_SIZE = 50;
    /** The period shown when none is chosen: the last 30 days. */
    public static final int DEFAULT_DAYS = 30;
    /** Who the entries of the application itself name (Data retention, imports). */
    static final String SYSTEM_MAIL = "system";

    private static final String USERS = "l.action_type LIKE 'USER\\_%' ESCAPE '\\'";
    private static final String CONTROLS = "(l.action_type IN ('EDIT', 'RENAME_ID', 'WORKFLOW_MOVE')"
            + " OR l.action_type LIKE 'ATTACHMENT\\_%' ESCAPE '\\' OR l.action_type LIKE 'CONTROL\\_%' ESCAPE '\\')";

    /** The three groups, each a condition on admin_audit_log (alias l). */
    public enum Group {
        USERS("Users", "Access, roles, status, e-mail, sign-ins, deletions", AuditLog.USERS),
        CONTROLS("Controls", "Edits, Control ID, workflow steps, files, deactivation", AuditLog.CONTROLS),
        SYSTEM("System", "Automatic and service events",
                "NOT (" + AuditLog.USERS + ") AND NOT " + AuditLog.CONTROLS);

        private final String label;
        private final String hint;
        private final String condition;

        Group(String label, String hint, String condition) {
            this.label = label;
            this.hint = hint;
            this.condition = condition;
        }

        public String getLabel() {
            return label;
        }

        public String getHint() {
            return hint;
        }

        static Group of(String actionType) {
            String type = actionType == null ? "" : actionType;
            if (type.startsWith("USER_")) {
                return USERS;
            }
            if ("EDIT".equals(type) || "RENAME_ID".equals(type) || "WORKFLOW_MOVE".equals(type)
                    || type.startsWith("ATTACHMENT_") || type.startsWith("CONTROL_")) {
                return CONTROLS;
            }
            return SYSTEM;
        }

        static Group parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            for (Group group : values()) {
                if (group.name().equalsIgnoreCase(value.trim())) {
                    return group;
                }
            }
            return null;
        }
    }

    /** An action people can filter on: its name, its group and which stored types it covers. */
    public record Action(String key, String label, Group group, String condition) {
    }

    /** The actions of the Action filter, by group, in the order shown. */
    public static final List<Action> ACTIONS = List.of(
            new Action("USER_CREATE", "User created", Group.USERS, "l.action_type = 'USER_CREATE'"),
            new Action("USER_ACCESS_UPDATE", "Access changed", Group.USERS, "l.action_type = 'USER_ACCESS_UPDATE'"),
            new Action("USER_EMAIL_UPDATE", "E-mail changed", Group.USERS, "l.action_type = 'USER_EMAIL_UPDATE'"),
            new Action("USER_DELETED", "User deleted", Group.USERS, "l.action_type IN ('USER_DELETED', 'USER_DELETE')"),
            new Action("USER_SSO_LINKED", "SSO linked", Group.USERS, "l.action_type = 'USER_SSO_LINKED'"),
            new Action("USER_SSO_REFUSED", "Sign-in refused", Group.USERS, "l.action_type = 'USER_SSO_REFUSED'"),
            new Action("EDIT", "Control edited", Group.CONTROLS, "l.action_type = 'EDIT'"),
            new Action("RENAME_ID", "Control ID renamed", Group.CONTROLS, "l.action_type = 'RENAME_ID'"),
            new Action("WORKFLOW_MOVE", "Workflow step", Group.CONTROLS, "l.action_type = 'WORKFLOW_MOVE'"),
            new Action("FILES", "Files", Group.CONTROLS, "l.action_type LIKE 'ATTACHMENT\\_%' ESCAPE '\\'"),
            new Action("CONTROL_DEACTIVATED", "Control deactivated", Group.CONTROLS,
                    "l.action_type = 'CONTROL_DEACTIVATED'"),
            new Action("DATA_RETENTION_PURGE", "Data retention", Group.SYSTEM, "l.action_type = 'DATA_RETENTION_PURGE'"),
            new Action("RELATED_CONTROLS_IMPORT", "Reference list imported", Group.SYSTEM,
                    "l.action_type = 'RELATED_CONTROLS_IMPORT'"),
            new Action("DATA_INTEGRITY_ERROR", "Data integrity error", Group.SYSTEM,
                    "l.action_type = 'DATA_INTEGRITY_ERROR'"));

    /** The name of each stored type, as the Action column shows it. */
    private static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Action action : ACTIONS) {
            labels.put(action.key(), action.label());
        }
        labels.put("USER_DELETE", "User deleted");
        labels.put("ATTACHMENT_ADDED", "File uploaded");
        labels.put("ATTACHMENT_REMOVED", "File removed");
        labels.put("ATTACHMENT_HIDDEN", "File hidden");
        labels.put("ATTACHMENT_REPLACED", "File replaced");
        labels.put("CONTROL_INITIATED", "Control initiated");
        labels.put("CONTROL_COMPLETED", "Control completed");
        labels.put("CONTROL_AUTO_CREATED", "Control created automatically");
        LABELS = Collections.unmodifiableMap(labels);
    }

    /** What the page asks for: dates in Almaty (both included), a group, an action key, a text to find. */
    public record Filter(LocalDate from, LocalDate to, String group, String action, String q, int page) {
    }

    /** One field of the details: before and after (a snapshot line has only after). */
    public record Change(String field, String before, String after) {
    }

    /**
     * One entry as a line of the page.
     *
     * @param byName       who did it, "System" for the application itself
     * @param description  the entry's own words ("Changed role from User to SoQM Admin Team"), the target taken out
     * @param targetName   the person the entry is about (users) or null
     * @param targetEmail  their e-mail, or null
     * @param controlId    the control's database id while it exists, for the link
     * @param controlCode  its Control ID (the stored one when the control is gone), or null
     * @param changes      before and after, field by field; empty when the entry records none
     */
    public record Row(long id, LocalDateTime createdAt, String byName, String byEmail, boolean system, Group group,
                      String action, String description, String targetName, String targetEmail, Long controlId,
                      String controlCode, List<Change> changes) {

        public String groupKey() {
            return group.name();
        }

        public boolean hasDetails() {
            return !changes.isEmpty();
        }
    }

    /** @param now the time of the page (Almaty), for "2 hours ago" (static/js/time-ago.js) */
    public record Page(Filter filter, LocalDate from, LocalDate to, List<Row> rows, long total, int page, int pages,
                       Map<Group, Long> counts, LocalDateTime now) {

        public long allCount() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private final JdbcTemplate jdbc;
    private final ControlHistoryService history;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public AuditLog(JdbcTemplate jdbc, ControlHistoryService history, Clock clock) {
        this.jdbc = jdbc;
        this.history = history;
        this.clock = clock;
    }

    public Page find(Filter filter) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = filter.to() != null ? filter.to() : today;
        LocalDate from = filter.from() != null ? filter.from() : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }

        // Every filter but the group, for the cards; the group on top for the rows
        StringBuilder where = new StringBuilder(" WHERE l.created_at >= ? AND l.created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(from.atStartOfDay()));
        args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        Action action = ACTIONS.stream().filter(a -> a.key().equals(filter.action())).findFirst().orElse(null);
        if (action != null) {
            where.append(" AND ").append(action.condition());
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String like = "%" + filter.q().trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(COALESCE(l.admin_email, '')) LIKE ? ESCAPE '\\'"
                    + " OR LOWER(COALESCE(l.admin_name, '')) LIKE ? ESCAPE '\\'"
                    // a text saved under an earlier status name is found by today's (StatusDisplayMapper.withCurrentNames)
                    + " OR LOWER(REPLACE(COALESCE(l.action_description, ''), 'SoQM Head Review', 'SoQM Admin Team Review'))"
                    + " LIKE ? ESCAPE '\\'"
                    + " OR LOWER(COALESCE(l.control_control_id, '')) LIKE ? ESCAPE '\\'"
                    + " OR LOWER(COALESCE(c.control_id, '')) LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
        String tables = " FROM admin_audit_log l LEFT JOIN controls c ON c.id = l.control_id";

        Map<Group, Long> counts = new LinkedHashMap<>();
        StringBuilder countSql = new StringBuilder("SELECT");
        for (Group group : Group.values()) {
            countSql.append(group.ordinal() == 0 ? " " : ", ")
                    .append("COALESCE(SUM(CASE WHEN ").append(group.condition).append(" THEN 1 ELSE 0 END), 0)");
        }
        jdbc.query(countSql + tables + where, rs -> {
            for (Group group : Group.values()) {
                counts.put(group, rs.getLong(group.ordinal() + 1));
            }
        }, args.toArray());
        for (Group group : Group.values()) {
            counts.putIfAbsent(group, 0L);
        }

        Group wanted = Group.parse(filter.group());
        long total = wanted == null ? counts.values().stream().mapToLong(Long::longValue).sum() : counts.get(wanted);
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(filter.page(), 1), pages);

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(PAGE_SIZE);
        rowArgs.add((page - 1) * PAGE_SIZE);
        List<AdminAuditLog> logs = new ArrayList<>();
        List<String> currentCodes = new ArrayList<>();
        jdbc.query("SELECT l.id, l.created_at, l.admin_email, l.admin_name, l.action_type, l.control_id,"
                        + " l.control_control_id, l.action_description, l.changed_fields, l.previous_values, l.new_values,"
                        + " c.control_id AS current_code" + tables + where
                        + (wanted == null ? "" : " AND " + wanted.condition)
                        + " ORDER BY l.created_at DESC, l.id DESC LIMIT ? OFFSET ?",
                rs -> {
                    AdminAuditLog log = new AdminAuditLog();
                    log.setId(rs.getLong("id"));
                    Timestamp at = rs.getTimestamp("created_at");
                    log.setCreatedAt(at == null ? null : at.toLocalDateTime());
                    log.setAdminEmail(rs.getString("admin_email"));
                    log.setAdminName(rs.getString("admin_name"));
                    log.setActionType(rs.getString("action_type"));
                    log.setControlId(rs.getObject("control_id", Long.class));
                    log.setControlControlId(rs.getString("control_control_id"));
                    log.setActionDescription(rs.getString("action_description"));
                    log.setChangedFields(rs.getString("changed_fields"));
                    log.setPreviousValues(rs.getString("previous_values"));
                    log.setNewValues(rs.getString("new_values"));
                    logs.add(log);
                    currentCodes.add(rs.getString("current_code"));
                }, rowArgs.toArray());

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < logs.size(); i++) {
            rows.add(row(logs.get(i), currentCodes.get(i)));
        }
        return new Page(filter, from, to, withTargetNames(rows), total, page, pages, counts,
                LocalDateTime.now(clock).withNano(0));
    }

    /** The names of the people the entries are about, in one query for the page; a deleted person keeps the e-mail. */
    private List<Row> withTargetNames(List<Row> rows) {
        List<String> mails = rows.stream().map(Row::targetEmail).filter(mail -> mail != null && !mail.isBlank())
                .map(mail -> mail.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        if (mails.isEmpty()) {
            return rows;
        }
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT mail, displayname FROM users WHERE LOWER(mail) IN ("
                        + String.join(",", Collections.nCopies(mails.size(), "?")) + ")",
                rs -> {
                    String name = rs.getString("displayname");
                    if (name != null && !name.isBlank()) {
                        names.put(rs.getString("mail").trim().toLowerCase(Locale.ROOT), name.trim());
                    }
                }, mails.toArray());
        List<Row> named = new ArrayList<>();
        for (Row row : rows) {
            String name = row.targetEmail() == null ? null : names.get(row.targetEmail().trim().toLowerCase(Locale.ROOT));
            named.add(name == null ? row : new Row(row.id(), row.createdAt(), row.byName(), row.byEmail(), row.system(),
                    row.group(), row.action(), row.description(), name, row.targetEmail(), row.controlId(),
                    row.controlCode(), row.changes()));
        }
        return named;
    }

    private Row row(AdminAuditLog log, String currentCode) {
        Group group = Group.of(log.getActionType());
        String email = log.getAdminEmail();
        boolean system = email == null || email.isBlank() || SYSTEM_MAIL.equalsIgnoreCase(email.trim());
        String byName = system ? "System" : (log.getAdminName() != null && !log.getAdminName().isBlank()
                ? log.getAdminName().trim() : email);
        String description = log.getActionDescription() == null ? ""
                : com.kpmg.qtracker.util.StatusDisplayMapper.withCurrentNames(log.getActionDescription().trim());
        String targetEmail = null;
        if (group == Group.USERS) {
            // The person an entry is about is named in its text (AdminAuditTrail reads it out)
            AdminAuditTrail.Entry entry = AdminAuditTrail.entry(log);
            targetEmail = entry.target();
            description = entry.action();
        }
        // Words that only repeat the action ("Edit Control" under "Control edited") are left out
        if (repeatsTheAction(description, label(log.getActionType()))) {
            description = "";
        }
        Long controlId = currentCode != null ? log.getControlId() : null;
        String controlCode = currentCode != null ? currentCode : blankToNull(log.getControlControlId());
        return new Row(log.getId(), log.getCreatedAt(), byName, system ? null : email, system, group,
                label(log.getActionType()), description, null, targetEmail, controlId, controlCode, changes(log, group));
    }

    /** The entries' generic words that say no more than the action's name. */
    private static final List<String> GENERIC = List.of("edit control", "created user", "updated control", "updated");

    static boolean repeatsTheAction(String description, String action) {
        if (description == null || description.isBlank()) {
            return true;
        }
        String text = description.trim().toLowerCase(Locale.ROOT);
        return GENERIC.contains(text) || text.equals(action == null ? "" : action.toLowerCase(Locale.ROOT));
    }

    /** The name of a stored type; one the page does not know reads as its words ("SOME_EVENT" -> "Some event"). */
    static String label(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return "Event";
        }
        String known = LABELS.get(actionType);
        if (known != null) {
            return known;
        }
        String words = actionType.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Before and after, field by field: a deactivated control's snapshot, a control's fields, or "field=value" lists. */
    List<Change> changes(AdminAuditLog log, Group group) {
        List<Change> changes = new ArrayList<>();
        if (ControlDeactivation.AUDIT_ACTION.equals(log.getActionType())) {
            for (String line : DeactivationSnapshot.lines(log.getNewValues())) {
                int colon = line.indexOf(": ");
                changes.add(colon > 0 ? new Change(line.substring(0, colon), "", line.substring(colon + 2))
                        : new Change("", "", line));
            }
            return changes;
        }
        if (group == Group.CONTROLS && isJsonMap(log.getPreviousValues(), log.getNewValues())) {
            for (FieldChangeDTO change : history.fieldChanges(log)) {
                changes.add(new Change(change.getField(), nullToEmpty(change.getOldValue()),
                        nullToEmpty(change.getNewValue())));
            }
            return changes;
        }
        Map<String, String> before = pairs(log.getPreviousValues());
        Map<String, String> after = pairs(log.getNewValues());
        List<String> fields = new ArrayList<>(before.keySet());
        after.keySet().stream().filter(field -> !fields.contains(field)).forEach(fields::add);
        for (String field : fields) {
            changes.add(new Change(humanize(field), before.getOrDefault(field, ""), after.getOrDefault(field, "")));
        }
        if (changes.isEmpty() && notBlank(log.getPreviousValues(), log.getNewValues())) {
            changes.add(new Change("Value", nullToEmpty(log.getPreviousValues()), nullToEmpty(log.getNewValues())));
        }
        return changes;
    }

    /** "role=USER, name=Smith, Anna, visibility=MY" or a JSON object: field -> value, in order. */
    Map<String, String> pairs(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) {
            return values;
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            try {
                Map<String, Object> map = json.readValue(text, new TypeReference<Map<String, Object>>() { });
                map.forEach((key, value) -> values.put(key, value == null ? "" : String.valueOf(value)));
                return values;
            } catch (Exception notJson) {
                // read as text below
            }
        }
        for (String part : text.split(",\\s*(?=[A-Za-z][A-Za-z ]*=)")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return values;
    }

    private boolean isJsonMap(String... values) {
        for (String value : values) {
            if (value != null && value.trim().startsWith("{")) {
                return true;
            }
        }
        return false;
    }

    /** The fields of a user's entries, as the Admin Panel names them. */
    private static final Map<String, String> USER_FIELDS = Map.of("mail", "E-mail", "displayName", "Name",
            "role", "Role", "visibility", "Visibility", "access", "Access", "enabled", "Status");

    private static String humanize(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String known = USER_FIELDS.get(field.trim());
        if (known != null) {
            return known;
        }
        String words = field.replace('_', ' ').trim();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static boolean notBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"-".equals(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
