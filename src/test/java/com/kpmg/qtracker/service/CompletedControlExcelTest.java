package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletedControlExcelTest {

    private static final StatusDisplayMapper STATUS = new StatusDisplayMapper();

    /** Every field of the three tabs, in the page's order, each under its tab's heading; then the record. */
    private static final List<String> EXPECTED = List.of(
            "# Control",
            "Control ID", "Control Frequency", "Control Category", "Control Type", "Component", "Operated By",
            "Control Status", "Priority", "Non-Audit Services Control Applicability", "SoQM Year",
            "Control Description", "PRP",
            "# Details",
            "Process Name", "Homogeneity", "References to this Control", "Department", "Process Activities",
            ControlStepsFields.OPERATOR_PROGRAM_LABEL, "Other Related Controls", "IT Applications",
            ControlStepsFields.STEPS_LABEL, "SoQM Head/Team Comments", "Process Owner Comments", "Attachment (Details)",
            "# Assignment",
            "Facilitator", "Control Operator", "SoQM Team / Delegate", "Process Owner", "Control Shared With",
            "Control Operation Date", "Control Operation Deadline", "Next Control Operation Date",
            "# Control record",
            "Performance Status", "Created By", "Created", "Updated At", "Attachment (Documents)");

    @Test
    void everyFieldOfTheThreeTabs_inThePagesOrder_anEmptyOneToo() {
        List<CompletedControlExcel.Line> lines = CompletedControlExcel.lines(new Control(), "COMPLETED",
                new ControlAssignmentDTO(), new ControlDetailsDTO(), Map.of(), STATUS);

        assertThat(lines).extracting(line -> line.heading() ? "# " + line.section() : line.field())
                .containsExactlyElementsOf(EXPECTED);
        assertThat(lines).filteredOn(line -> !line.heading() && !"Performance Status".equals(line.field()))
                .allMatch(line -> "".equals(line.value()));
        // Nothing missing even without the details and the assignment
        assertThat(CompletedControlExcel.lines(new Control(), null, null, null, null, STATUS)).hasSize(EXPECTED.size());
    }

    @Test
    void values_asThePageShowsThem() {
        Control control = new Control();
        control.setControlId("HR-1/FY27");
        control.setControlFrequency("Monthly");
        control.setControlStatus("NOT_APPLICABLE");
        control.setSoqmYear("1 OCT 2026 - 30 SEP 2027");
        control.setControlDescription("  Description  ");
        control.setAttachmentDetailsPath("HR-1/plan.pdf;HR-1/notes.docx");
        control.setAttachmentDocumentsPath("doc.xlsx");
        control.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 5));
        ControlDetailsDTO details = new ControlDetailsDTO();
        details.setHomogeneity("Homogeneous");
        details.setReferencesToControl("REF-7");
        details.setSoqmHeadComments("Checked");
        details.setControlOperatorReview("Program");
        details.setRelatedControls(List.of("EP-2/FY27", "HR-3/FY27/Central/OCT"));
        details.setOtherRelatedControls("Typed before the links");
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of("Anna@kpmg.kz", "unknown@kpmg.kz"));
        assignment.setProcessOwner(List.of("gone@kpmg.kz"));
        assignment.setControlSharedWith(List.of("boris@kpmg.kz"));
        assignment.setControlOperationDate(LocalDate.of(2026, 10, 17));
        assignment.setControlOperationDeadline(LocalDate.of(2026, 10, 30));
        Map<String, String> names = Map.of("anna@kpmg.kz", "Anna Petrova", "boris@kpmg.kz", "Boris Ivanov",
                "gone@kpmg.kz", "gone@kpmg.kz (deleted user)");

        Map<String, String> values = new java.util.LinkedHashMap<>();
        CompletedControlExcel.lines(control, "COMPLETED", assignment, details, names, STATUS).stream()
                .filter(line -> !line.heading()).forEach(line -> values.put(line.field(), line.value()));

        assertThat(values).containsEntry("Control ID", "HR-1/FY27")
                .containsEntry("Control Status", "Not Applicable")
                .containsEntry("SoQM Year", "1 OCT 2026 - 30 SEP 2027")
                .containsEntry("Control Description", "Description")
                .containsEntry("Homogeneity", "Homogeneous")
                .containsEntry("References to this Control", "REF-7")
                .containsEntry("SoQM Head/Team Comments", "Checked")
                .containsEntry(ControlStepsFields.OPERATOR_PROGRAM_LABEL, "Program")
                .containsEntry("Other Related Controls", "EP-2/FY27, HR-3/FY27/Central/OCT")
                .doesNotContainKey(RelatedControls.EARLIER_TEXT_LABEL)
                .containsEntry("Attachment (Details)", "plan.pdf; notes.docx")
                .containsEntry("Facilitator", "Anna Petrova (Anna@kpmg.kz); unknown@kpmg.kz")
                .containsEntry("Process Owner", "gone@kpmg.kz (deleted user)")
                .containsEntry("Control Shared With", "Boris Ivanov (boris@kpmg.kz)")
                .containsEntry("Control Operation Date", "17.10.2026")
                .containsEntry("Control Operation Deadline", "30.10.2026")
                .containsEntry("Next Control Operation Date", "")
                .containsEntry("Performance Status", "Completed")
                .containsEntry("Created", "01.10.2026 09:05")
                .containsEntry("Attachment (Documents)", "doc.xlsx");
    }

    @Test
    void theFile_hasFieldAndValue_aHeadingPerTab_andNoFormula() throws Exception {
        Control control = new Control();
        control.setControlId("HR-2");
        control.setControlDescription("=HYPERLINK(\"http://x\")");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletedControlExcel.write(CompletedControlExcel.lines(control, "COMPLETED", null, null, Map.of(), STATUS), out);

        List<String> firstColumn = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Field");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Value");
            for (Row row : sheet) {
                firstColumn.add(row.getCell(0).getStringCellValue());
                for (Cell cell : row) {
                    assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                }
                if ("Control Description".equals(row.getCell(0).getStringCellValue())) {
                    assertThat(row.getCell(1).getCellStyle().getQuotePrefixed()).isTrue();
                }
            }
            assertThat(sheet.getNumMergedRegions()).isEqualTo(4);
        }
        assertThat(firstColumn).hasSize(EXPECTED.size() + 1)
                .contains("Control", "Details", "Assignment", "Control record");
    }
}
