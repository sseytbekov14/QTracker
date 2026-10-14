package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Excel of a completed control (Download on the Performance Cycle page, GET /api/controls/{id}/export/completed):
 * every field of View Control's Control, Details and Assignment tabs, in the order and with the labels of the page,
 * an empty field too (with an empty value), each tab under a heading row; then the record (status, who created it and
 * when, the Documents attachments). People as "Name (e-mail)", a deleted person as "e-mail (deleted user)"; dates as
 * dd.mm.yyyy. A text a spreadsheet would read as a formula is written quote-prefixed (ComponentControlsExcel).
 */
public final class CompletedControlExcel {

    public static final String CONTROL = "Control";
    public static final String DETAILS = "Details";
    public static final String ASSIGNMENT = "Assignment";
    public static final String RECORD = "Control record";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private CompletedControlExcel() {
    }

    /** One row: a heading (section, no field) or a field of a section. */
    public record Line(String section, String field, String value) {
        public boolean heading() {
            return field == null;
        }
    }

    /**
     * @param names display names by lower-case e-mail (UserService.displayNamesByEmail); without one the e-mail alone
     */
    public static List<Line> lines(Control control, String performanceStatus, ControlAssignmentDTO assignment,
                                   ControlDetailsDTO details, Map<String, String> names,
                                   StatusDisplayMapper statusDisplayMapper) {
        ControlDetailsDTO d = details != null ? details : new ControlDetailsDTO();
        ControlAssignmentDTO a = assignment != null ? assignment : new ControlAssignmentDTO();
        List<Line> lines = new ArrayList<>();

        section(lines, CONTROL);
        add(lines, CONTROL, "Control ID", control.getControlId());
        add(lines, CONTROL, "Control Frequency", control.getControlFrequency());
        add(lines, CONTROL, "Control Category", control.getControlCategory());
        add(lines, CONTROL, "Control Type", control.getControlType());
        add(lines, CONTROL, "Component", control.getComponent());
        add(lines, CONTROL, "Operated By", control.getOperatedBy());
        add(lines, CONTROL, "Control Status", ControlStatusValue.label(control.getControlStatus()));
        add(lines, CONTROL, "Priority", control.getPriority());
        add(lines, CONTROL, "Non-Audit Services Control Applicability", control.getNonAuditServicesApplicability());
        add(lines, CONTROL, "SoQM Year", control.getSoqmYear());
        add(lines, CONTROL, "Control Description", control.getControlDescription());
        add(lines, CONTROL, "PRP", control.getPrp());

        section(lines, DETAILS);
        add(lines, DETAILS, "Process Name", d.getProcessName());
        add(lines, DETAILS, "Homogeneity", d.getHomogeneity());
        add(lines, DETAILS, "References to this Control", d.getReferencesToControl());
        add(lines, DETAILS, "Department", d.getDepartment());
        add(lines, DETAILS, "Process Activities", d.getProcessActivities());
        add(lines, DETAILS, ControlStepsFields.OPERATOR_PROGRAM_LABEL, d.getControlOperatorReview());
        add(lines, DETAILS, "Other Related Controls", d.getOtherRelatedControls());
        add(lines, DETAILS, "IT Applications", d.getItApplications());
        add(lines, DETAILS, ControlStepsFields.STEPS_LABEL, d.getControlStepsPerformed());
        add(lines, DETAILS, "SoQM Head/Team Comments", d.getSoqmHeadComments());
        add(lines, DETAILS, "Process Owner Comments", d.getProcessOwnerComments());
        add(lines, DETAILS, "Attachment (Details)", fileNames(control.getAttachmentDetailsPath()));

        section(lines, ASSIGNMENT);
        add(lines, ASSIGNMENT, "Facilitator", people(a.getFacilitator(), names));
        add(lines, ASSIGNMENT, "Control Operator", people(a.getControlOperator(), names));
        add(lines, ASSIGNMENT, "SoQM Team / Delegate", people(a.getSoqmLead(), names));
        add(lines, ASSIGNMENT, "Process Owner", people(a.getProcessOwner(), names));
        add(lines, ASSIGNMENT, "Control Shared With", people(a.getControlSharedWith(), names));
        add(lines, ASSIGNMENT, "Control Operation Date", date(a.getControlOperationDate()));
        add(lines, ASSIGNMENT, "Control Operation Deadline", date(a.getControlOperationDeadline()));
        add(lines, ASSIGNMENT, "Next Control Operation Date", date(a.getNextControlOperationDate()));

        section(lines, RECORD);
        String status = statusDisplayMapper.display(performanceStatus);
        add(lines, RECORD, "Performance Status", status);
        add(lines, RECORD, "Created By", control.creatorName());
        add(lines, RECORD, "Created", dateTime(control.getCreatedAt()));
        add(lines, RECORD, "Updated At", dateTime(control.getUpdatedAt()));
        add(lines, RECORD, "Attachment (Documents)", fileNames(control.getAttachmentDocumentsPath()));
        return lines;
    }

    /** Every address of the assignment fields, for UserService.displayNamesByEmail. */
    public static List<String> emailsOf(ControlAssignmentDTO assignment) {
        List<String> emails = new ArrayList<>();
        if (assignment != null) {
            for (List<String> field : List.of(nonNull(assignment.getFacilitator()), nonNull(assignment.getControlOperator()),
                    nonNull(assignment.getSoqmLead()), nonNull(assignment.getProcessOwner()),
                    nonNull(assignment.getControlSharedWith()))) {
                emails.addAll(field);
            }
        }
        return emails;
    }

    /** Two columns, Field and Value; a heading row per tab across both. */
    public static void write(List<Line> lines, OutputStream out) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            CellStyle heading = workbook.createCellStyle();
            heading.setFont(bold);
            heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle label = workbook.createCellStyle();
            label.setWrapText(true);
            CellStyle text = workbook.createCellStyle();
            text.setWrapText(true);
            CellStyle quoted = workbook.createCellStyle();
            quoted.setWrapText(true);
            quoted.setQuotePrefixed(true);

            Sheet sheet = workbook.createSheet("Control");
            Row head = sheet.createRow(0);
            cell(head, 0, "Field", header);
            cell(head, 1, "Value", header);
            int index = 1;
            for (Line line : lines) {
                Row row = sheet.createRow(index);
                if (line.heading()) {
                    cell(row, 0, line.section(), heading);
                    cell(row, 1, "", heading);
                    sheet.addMergedRegion(new CellRangeAddress(index, index, 0, 1));
                } else {
                    cell(row, 0, line.field(), label);
                    cell(row, 1, line.value(), ComponentControlsExcel.looksLikeAFormula(line.value()) ? quoted : text);
                }
                index++;
            }
            sheet.createFreezePane(0, 1);
            sheet.setColumnWidth(0, 40 * 256);
            sheet.setColumnWidth(1, 90 * 256);
            workbook.write(out);
        }
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void section(List<Line> lines, String section) {
        lines.add(new Line(section, null, null));
    }

    private static void add(List<Line> lines, String section, String field, String value) {
        lines.add(new Line(section, field, value == null ? "" : value.trim()));
    }

    private static String people(List<String> emails, Map<String, String> names) {
        List<String> shown = new ArrayList<>();
        for (String email : nonNull(emails)) {
            if (email == null || email.isBlank()) {
                continue;
            }
            String mail = email.trim();
            String name = names == null ? null : names.get(mail.toLowerCase(Locale.ROOT));
            if (name == null || name.isBlank() || name.equalsIgnoreCase(mail)) {
                shown.add(mail);
            } else if (name.toLowerCase(Locale.ROOT).startsWith(mail.toLowerCase(Locale.ROOT))) {
                shown.add(name);   // "e-mail (deleted user)"
            } else {
                shown.add(name + " (" + mail + ")");
            }
        }
        return String.join("; ", shown);
    }

    private static String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    private static String dateTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : DATE_TIME.format(dateTime);
    }

    /** The names of a ';'-separated attachment list, without folders. */
    private static String fileNames(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String part : stored.split(";")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            names.add(slash >= 0 && slash + 1 < name.length() ? name.substring(slash + 1) : name);
        }
        return String.join("; ", names);
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }
}
