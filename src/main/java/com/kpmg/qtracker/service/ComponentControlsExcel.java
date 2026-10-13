package com.kpmg.qtracker.service;

import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * The Excel of a list (/component/{code}/export: a component, All components, KDN): the ten columns of the list in
 * its order, the rows the user asked for (search, filter, KDN, sorting; every page), the Performance Status as text.
 * Streamed (SXSSF: a hundred rows in memory at a time); a text that a spreadsheet would read as a formula (starting
 * with =, +, -, @, a tab or a line break) is written quote-prefixed, so it stays text when the cell is edited.
 */
@Component
public class ComponentControlsExcel {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final int ROWS_IN_MEMORY = 100;

    private final StatusDisplayMapper statusDisplayMapper;

    public ComponentControlsExcel(StatusDisplayMapper statusDisplayMapper) {
        this.statusDisplayMapper = statusDisplayMapper;
    }

    public void write(String sheetName, List<ComponentControlsList.Row> rows, OutputStream out) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROWS_IN_MEMORY);
        try {
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            CellStyle quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);

            Sheet sheet = workbook.createSheet(safeSheetName(sheetName));
            List<ComponentControlsList.Column> columns = ComponentControlsList.COLUMNS;
            Row head = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(header);
            }
            int index = 1;
            for (ComponentControlsList.Row row : rows) {
                Row line = sheet.createRow(index++);
                String status = statusDisplayMapper.display(row.status());
                String[] values = {row.controlId(), row.type(), row.frequency(), row.facilitators(), row.operators(),
                        row.owners(), row.soqmLeads(), row.category(), row.operationDateText(),
                        status == null ? "Draft" : status};
                for (int i = 0; i < values.length; i++) {
                    String value = values[i] == null ? "" : values[i];
                    Cell cell = line.createCell(i);
                    cell.setCellValue(value);
                    if (looksLikeAFormula(value)) {
                        cell.setCellStyle(quoted);
                    }
                }
            }
            for (int i = 0; i < columns.size(); i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }
            workbook.write(out);
        } finally {
            workbook.close();
        }
    }

    /** Text a spreadsheet would take for a formula or a command (CSV/formula injection). */
    static boolean looksLikeAFormula(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                || first == '\n';
    }

    /** Excel sheet names: at most 31 characters, none of : \ / ? * [ ]. */
    static String safeSheetName(String name) {
        String cleaned = (name == null || name.isBlank() ? "Controls" : name).replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }
}
