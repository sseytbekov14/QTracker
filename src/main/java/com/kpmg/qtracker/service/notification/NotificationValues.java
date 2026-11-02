package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.service.WorkingDaysService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The values of the placeholders of a control: {CONTROL_ID}, {CONTROL_NAME} (its description), {OCCURRENCE_DATE} (Control
 * Operation Date), {DEADLINE} (Control Operation Deadline, as stored) as "14 Nov 2026", {LINK} = app.base-url +
 * /view-control/{id}. A value the control does not have is null, so a text that needs it is not made (TP-2);
 * {CONTROL_NAME} is empty then instead. No text of {@link Template} uses {CONTROL_NAME} since V25; it stays so that a
 * text written by hand in notification_template with it is still made.
 */
@Component
public class NotificationValues {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    public static final String CONTROL_ID = "CONTROL_ID";
    public static final String CONTROL_NAME = "CONTROL_NAME";
    public static final String OCCURRENCE_DATE = "OCCURRENCE_DATE";
    public static final String DEADLINE = "DEADLINE";
    public static final String RETURNED_BY = "RETURNED_BY";
    /** Who passed the step on (the line From of the e-mail's details), set for a move of the workflow. */
    public static final String FROM = "FROM";
    /** The comment of a return (the quote in the e-mail, "Comment from ..."), set for a return of the workflow. */
    public static final String COMMENT = "COMMENT";
    /** The deadline with how far it is ({@link #due}), set for the reminders: the line Due of the e-mail's details. */
    public static final String DUE = "DUE";
    /** The person's name in "Dear {NAME},", set for each person by NotificationProducer. */
    public static final String NAME = "NAME";
    /** The person's roles on the control (T1, T2), set for each person by NotificationProducer. */
    public static final String ROLES = "ROLES";

    private final String baseUrl;

    public NotificationValues(@Value("${app.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public Map<String, String> of(Control control, ControlAssignmentDTO assignment) {
        Map<String, String> values = new HashMap<>();
        String controlId = control.getControlId() == null ? null : control.getControlId().trim();
        values.put(CONTROL_ID, controlId == null || controlId.isEmpty() ? null : controlId);
        values.put(CONTROL_NAME, control.getControlDescription() == null ? "" : control.getControlDescription().trim());
        values.put(OCCURRENCE_DATE, format(assignment == null ? null : assignment.getControlOperationDate()));
        values.put(DEADLINE, format(assignment == null ? null : assignment.getControlOperationDeadline()));
        values.put(TemplateRenderer.LINK, link(control));
        return values;
    }

    /**
     * The deadline and how far it is from today, in working days (Monday to Friday, no holidays, as the deadline itself
     * is counted): "14 Nov 2026 (in 4 working days)", "14 Nov 2026 (today)", "14 Nov 2026 (overdue by 2 working days)".
     * A deadline at the weekend after today's Friday has no working day to count: the date alone.
     */
    public static String due(WorkingDaysService workingDays, LocalDate deadline, LocalDate today) {
        String date = format(deadline);
        if (today.equals(deadline)) {
            return date + " (today)";
        }
        if (today.isAfter(deadline)) {
            return date + " (overdue by " + workingDays(workingDays.workingDaysBetween(deadline, today)) + ")";
        }
        int left = workingDays.workingDaysBetween(today, deadline);
        return left == 0 ? date : date + " (in " + workingDays(left) + ")";
    }

    private static String workingDays(int count) {
        return count + (count == 1 ? " working day" : " working days");
    }

    /** The address of the control's page; a path only when app.base-url is not set. */
    public String link(Control control) {
        String path = "/view-control/" + control.getId();
        if (baseUrl.isEmpty()) {
            return path;
        }
        return (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + path;
    }

    private static String format(LocalDate date) {
        return date == null ? null : date.format(DATE);
    }
}
