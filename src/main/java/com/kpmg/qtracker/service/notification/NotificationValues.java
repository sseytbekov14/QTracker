package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * The values of the placeholders of a control: {CONTROL_ID}, {CONTROL_NAME} (its description), {OCCURRENCE_DATE} (Control
 * Operation Date), {DEADLINE} (Control Operation Deadline, as stored) as DD-MM-YYYY, {LINK} = app.base-url +
 * /view-control/{id}. A value the control does not have is null, so a text that needs it is not made (TP-2);
 * {CONTROL_NAME} is empty then instead.
 */
@Component
public class NotificationValues {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    public static final String CONTROL_ID = "CONTROL_ID";
    public static final String CONTROL_NAME = "CONTROL_NAME";
    public static final String OCCURRENCE_DATE = "OCCURRENCE_DATE";
    public static final String DEADLINE = "DEADLINE";
    public static final String ROLE = "ROLE";
    public static final String RETURNED_BY = "RETURNED_BY";

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
