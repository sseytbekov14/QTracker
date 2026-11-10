package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.EmailOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The E-mails page of the Admin Panel (business decision of 2026-11-10): every e-mail QTracker made (email_outbox), newest
 * first, in plain words for the business owners - Sent, Waiting (with the next try), Failed (why), Not sent (why) - with
 * the control, the event and the recipient. Filters and paging run in the database. It only reads.
 * <p>"Sent" means the mail server accepted the e-mail; whether it reached the mailbox or was read is not known to
 * QTracker. The text of an e-mail is kept 30 days after it is done (DataRetention), the rest of the row 7 years.
 */
@Service
public class EmailLog {

    public static final int PAGE_SIZE = 50;
    /** The period shown when none is chosen: the last 7 days. */
    public static final int DEFAULT_DAYS = 7;

    /** The statuses as people read them; each is a condition on email_outbox (alias o). */
    public enum Status {
        SENT("Sent", "o.status = 'SENT'"),
        WAITING("Waiting", "(o.status IN ('PENDING', 'SENDING') OR (o.status = 'FAILED' AND o.next_attempt_at IS NOT NULL))"),
        FAILED("Failed", "(o.status = 'FAILED_RENDER' OR (o.status = 'FAILED' AND o.next_attempt_at IS NULL))"),
        NOT_SENT("Not sent", "o.status IN ('SKIPPED_DISABLED', 'SKIPPED_INACTIVE')");

        private final String label;
        private final String condition;

        Status(String label, String condition) {
            this.label = label;
            this.condition = condition;
        }

        public String getLabel() {
            return label;
        }

        static Status of(String stored, LocalDateTime nextAttempt) {
            String status = stored == null ? "" : stored;
            return switch (status) {
                case EmailOutbox.SENT -> SENT;
                case EmailOutbox.PENDING, EmailOutbox.SENDING -> WAITING;
                case EmailOutbox.FAILED -> nextAttempt != null ? WAITING : FAILED;
                case EmailOutbox.FAILED_RENDER -> FAILED;
                default -> NOT_SENT;
            };
        }

        static Status parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            for (Status status : values()) {
                if (status.name().equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
            return null;
        }
    }

    /** The events by template key, as the page names them. */
    public static final Map<String, String> EVENTS;

    static {
        Map<String, String> events = new LinkedHashMap<>();
        events.put("T1", "Control created");
        events.put("T2", "Occurrence day");
        events.put("T3", "Reminder");
        events.put("T4", "Deadline today");
        events.put("T5", "Overdue");
        events.put("T6", "To Control Operator");
        events.put("T7", "Returned to Facilitator");
        events.put("T8", "To SoQM Admin Team");
        events.put("T9", "Returned to Control Operator");
        events.put("T10", "To Process Owner");
        events.put("T11", "Returned to Control Operator by Process Owner");
        events.put("T12", "Control completed");
        EVENTS = java.util.Collections.unmodifiableMap(events);
    }

    /**
     * What the page asks for: a period (dates in Almaty, both included), a status, an event (template key), and a text
     * found in the Control ID, the recipient's e-mail or name.
     */
    public record Filter(LocalDate from, LocalDate to, String status, String event, String q, int page) {
    }

    /**
     * One e-mail as a line of the page.
     *
     * @param controlCode the Control ID; null when the e-mail has no control or the control is gone
     * @param detail      the plain-words reason or state under the status ("Next try at 14:35", "The person is inactive")
     * @param error       the mail server's own answer, for the curious (null when none)
     * @param text        the e-mail as plain text, while it is kept (30 days after it is done); null after that
     */
    public record Row(long id, LocalDateTime createdAt, LocalDateTime sentAt, Long controlId, String controlCode,
                      String event, String recipientName, String recipientEmail, Status status, String detail,
                      String error, int attempts, String text) {

        public String statusKey() {
            return status.name();
        }
    }

    public record Page(Filter filter, LocalDate from, LocalDate to, List<Row> rows, long total, int page, int pages,
                       Map<Status, Long> counts) {

        /** Every e-mail of the period with the other filters, whatever its status. */
        public long allCount() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EmailLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
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

        // Every filter but the status, for the counters; the status on top for the rows
        StringBuilder where = new StringBuilder(" WHERE o.created_at >= ? AND o.created_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(from.atStartOfDay()));
        args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        if (filter.event() != null && EVENTS.containsKey(filter.event())) {
            where.append(" AND o.template_key = ?");
            args.add(filter.event());
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            String like = "%" + filter.q().trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(o.recipient_email) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(u.displayname, '')) LIKE ?"
                    + " ESCAPE '\\' OR LOWER(COALESCE(c.control_id, '')) LIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String tables = " FROM email_outbox o LEFT JOIN controls c ON c.id = o.control_id"
                + " LEFT JOIN users u ON u.id = o.recipient_user_id";

        Map<Status, Long> counts = new LinkedHashMap<>();
        StringBuilder countSql = new StringBuilder("SELECT");
        for (Status status : Status.values()) {
            countSql.append(status.ordinal() == 0 ? " " : ", ")
                    .append("COALESCE(SUM(CASE WHEN ").append(status.condition).append(" THEN 1 ELSE 0 END), 0)");
        }
        jdbc.query(countSql + tables + where, rs -> {
            for (Status status : Status.values()) {
                counts.put(status, rs.getLong(status.ordinal() + 1));
            }
        }, args.toArray());
        for (Status status : Status.values()) {
            counts.putIfAbsent(status, 0L);
        }

        Status wanted = Status.parse(filter.status());
        String rowsWhere = where + (wanted == null ? "" : " AND " + wanted.condition);
        long total = wanted == null ? counts.values().stream().mapToLong(Long::longValue).sum() : counts.get(wanted);
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(Math.max(filter.page(), 1), pages);

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(PAGE_SIZE);
        rowArgs.add((page - 1) * PAGE_SIZE);
        List<Row> rows = jdbc.query("SELECT o.id, o.created_at, o.sent_at, o.control_id, c.control_id AS control_code,"
                        + " o.template_key, u.displayname, o.recipient_email, o.status, o.next_attempt_at, o.attempts,"
                        + " o.last_error, o.provider, o.body_text" + tables + rowsWhere
                        + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> {
                    LocalDateTime next = toDateTime(rs.getTimestamp("next_attempt_at"));
                    Status status = Status.of(rs.getString("status"), next);
                    String error = rs.getString("last_error");
                    String text = rs.getString("body_text");
                    String key = rs.getString("template_key");
                    return new Row(rs.getLong("id"), toDateTime(rs.getTimestamp("created_at")),
                            toDateTime(rs.getTimestamp("sent_at")), (Long) rs.getObject("control_id", Long.class),
                            rs.getString("control_code"), EVENTS.getOrDefault(key, key), rs.getString("displayname"),
                            rs.getString("recipient_email"), status,
                            detail(rs.getString("status"), next, error, rs.getString("provider"), rs.getInt("attempts")),
                            error == null || error.isBlank() ? null : error, rs.getInt("attempts"),
                            text == null || text.isBlank() ? null : text);
                }, rowArgs.toArray());
        return new Page(filter, from, to, rows, total, page, pages, counts);
    }

    /** The state or the reason in plain words, as the line under the status says it. */
    static String detail(String stored, LocalDateTime nextAttempt, String error, String provider, int attempts) {
        String status = stored == null ? "" : stored;
        String err = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return switch (status) {
            case EmailOutbox.SENT -> "log".equalsIgnoreCase(provider)
                    ? "Test mode: written to the application log, not e-mailed"
                    : "Accepted by the mail server";
            case EmailOutbox.PENDING -> "In the queue, goes out within a minute";
            case EmailOutbox.SENDING -> "Being sent now";
            case EmailOutbox.FAILED -> nextAttempt != null
                    ? "Try " + attempts + " failed (" + reason(err) + "), next try at " + nextAttempt.format(TIME)
                    : reason(err) + "; tried " + attempts + " times, no more tries";
            case EmailOutbox.FAILED_RENDER -> "The text of the e-mail could not be made";
            case EmailOutbox.SKIPPED_INACTIVE -> "The person is inactive or deleted";
            case EmailOutbox.SKIPPED_DISABLED -> err.contains("whitelist")
                    ? "The address is not in the e-mail whitelist of the server"
                    : "E-mail is turned off on the server";
            default -> status;
        };
    }

    /** The mail server's answer in plain words. */
    static String reason(String error) {
        if (error.contains("connect") || error.contains("unknownhost") || error.contains("timed out")
                || error.contains("timeout") || error.contains("no e-mail provider")) {
            return "the mail server could not be reached";
        }
        if (error.contains("authentication") || error.contains("535")) {
            return "the mail server refused the login";
        }
        if (error.contains("invalid address") || error.contains("sendfailed") || error.contains("550")
                || error.contains("553") || error.contains("recipient")) {
            return "the mail server did not accept the address";
        }
        return "the mail server returned an error";
    }

    private static LocalDateTime toDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
