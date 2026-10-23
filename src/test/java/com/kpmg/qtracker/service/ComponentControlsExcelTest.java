package com.kpmg.qtracker.service;

import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentControlsExcelTest {

    private final ComponentControlsExcel excel = new ComponentControlsExcel(new StatusDisplayMapper());

    @Test
    void theListsTenColumns_theStatusAsText_formulasStayText_cyrillicKept() throws Exception {
        ComponentControlsList.Row row = new ComponentControlsList.Row(1L, "A&C-1", "=HYPERLINK(\"x\")", "Monthly",
                "Ерлан Тестов", "+7 701", "-owner", "@lead", "Key", LocalDate.of(2026, 10, 3), "SOQM_HEAD_REVIEW",
                false, null, false, false, false, false, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        excel.write("Acceptance & Continuance", List.of(row), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Acceptance & Continuance");
            List<String> header = new ArrayList<>();
            sheet.getRow(0).forEach(cell -> header.add(cell.getStringCellValue()));
            assertThat(header).containsExactlyElementsOf(
                    ComponentControlsList.COLUMNS.stream().map(ComponentControlsList.Column::label).toList());
            List<String> values = new ArrayList<>();
            sheet.getRow(1).forEach(cell -> {
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                values.add(cell.getStringCellValue());
            });
            assertThat(values).containsExactly("A&C-1", "=HYPERLINK(\"x\")", "Monthly", "Ерлан Тестов", "+7 701",
                    "-owner", "@lead", "Key", "03.10.2026", "SoQM Head Review");
            for (int i : new int[]{1, 4, 5, 6}) {
                assertThat(sheet.getRow(1).getCell(i).getCellStyle().getQuotePrefixed()).as("column %d", i).isTrue();
            }
            assertThat(sheet.getRow(1).getCell(0).getCellStyle().getQuotePrefixed()).isFalse();
        }
    }

    @Test
    void formulaLikeText_andSheetNames() {
        assertThat(ComponentControlsExcel.looksLikeAFormula("=1+1")).isTrue();
        assertThat(ComponentControlsExcel.looksLikeAFormula("\tx")).isTrue();
        assertThat(ComponentControlsExcel.looksLikeAFormula("HR-1")).isFalse();
        assertThat(ComponentControlsExcel.looksLikeAFormula("")).isFalse();
        assertThat(ComponentControlsExcel.safeSheetName("A/B:C?*[]")).isEqualTo("A B C");
        assertThat(ComponentControlsExcel.safeSheetName("x".repeat(40))).hasSize(31);
        assertThat(ComponentControlsExcel.safeSheetName(" ")).isEqualTo("Controls");
    }
}
