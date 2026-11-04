package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.RelatedControlRef;
import com.kpmg.qtracker.repository.RelatedControlRefRepository;
import com.kpmg.qtracker.util.ValueList;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The list Other Related Controls is chosen from (related_control_ref), from an Excel file of SoQM Admin Team: the
 * first column of every sheet (one sheet per component) holds one Control ID per row under a header row (CONTROL ID
 * or ID); other columns are not read, a formula or a number counts as the value it shows, a Control ID already on an
 * earlier row or sheet is skipped. A dry run by default; with apply the new
 * Control IDs are added and one entry goes to the audit trail. The same file again changes nothing. Nothing is ever
 * deleted: a Control ID that is no longer in the file stays active, unless deactivateMissing makes it inactive (no
 * longer offered; the controls that have it keep it).
 */
@Service
@RequiredArgsConstructor
public class RelatedControlsImport {

    static final String ACTOR_MAIL = "system";
    public static final String AUDIT_ACTION = "RELATED_CONTROLS_IMPORT";

    private final RelatedControlRefRepository repository;
    private final AdminAuditService auditService;

    public enum Outcome {
        ADDED("added"),
        UPDATED("updated"),
        UNCHANGED("unchanged"),
        SKIPPED("skipped");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * A row of the file (its sheet and its number in Excel) and what the import does with it; reason for SKIPPED and
     * UPDATED.
     */
    public record Line(String sheet, int row, Outcome outcome, String controlId, String reason) {
    }

    /**
     * @param sheets      the sheets read, in the order of the workbook
     * @param deactivated the active Control IDs not in the file that become inactive (deactivateMissing only)
     */
    public record Report(boolean applied, boolean deactivateMissing, String refusal, String file, List<String> sheets,
                         List<Line> lines, List<String> deactivated) {

        public boolean refused() {
            return refusal != null;
        }

        public long count(Outcome outcome) {
            return lines.stream().filter(line -> line.outcome() == outcome).count();
        }

        public List<Line> lines(Outcome outcome) {
            return lines.stream().filter(line -> line.outcome() == outcome).toList();
        }

        public String render() {
            String nl = System.lineSeparator();
            StringBuilder out = new StringBuilder("Other Related Controls list import: ");
            if (refused()) {
                return out.append("REFUSED, nothing written: ").append(refusal).append(nl).toString();
            }
            out.append(applied ? "APPLIED" : "DRY RUN, nothing written").append(nl);
            out.append("File: ").append(file).append(", ").append(sheets.size())
                    .append(sheets.size() == 1 ? " sheet (" : " sheets (").append(String.join(", ", sheets))
                    .append("), column A from row 2 (row 1 is the header): ").append(lines.size()).append(" rows")
                    .append(nl);
            String verb = applied ? "" : "would be ";
            out.append(verb).append("added: ").append(count(Outcome.ADDED))
                    .append(", ").append(verb).append("updated: ").append(count(Outcome.UPDATED))
                    .append(", unchanged: ").append(count(Outcome.UNCHANGED))
                    .append(", skipped: ").append(count(Outcome.SKIPPED)).append(nl);
            out.append(verb).append("made inactive: ").append(deactivated.size())
                    .append(deactivateMissing ? "" : " (only with --deactivate-missing; IDs not in the file stay active)")
                    .append(nl);
            for (Line line : lines) {
                if (line.outcome() == Outcome.SKIPPED || line.outcome() == Outcome.UPDATED) {
                    out.append(String.format("  %s row %4d  %s: %s%s", line.sheet(), line.row(), line.outcome().label(),
                            line.reason(), line.controlId() == null ? "" : " (" + line.controlId() + ")")).append(nl);
                }
            }
            for (String controlId : deactivated) {
                out.append("  ").append(verb).append("made inactive: ").append(controlId).append(nl);
            }
            if (!applied) {
                out.append("Run again with --apply to write it.").append(nl);
            }
            return out.toString();
        }
    }

    /** A value of the first column of a sheet, or why there is none (a formula that gives an error). */
    record CellText(String sheet, int row, String value, String problem) {
    }

    /**
     * @param actor who runs it (the operating-system user), for the audit trail
     */
    @Transactional
    public Report run(Path file, boolean apply, boolean deactivateMissing, String actor) {
        String name = file == null || file.getFileName() == null ? String.valueOf(file) : file.getFileName().toString();
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return refused(apply, deactivateMissing, "the file cannot be read: " + file, name);
        }
        List<String> sheetNames = new ArrayList<>();
        List<CellText> cells = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            if (workbook.getNumberOfSheets() == 0) {
                return refused(apply, deactivateMissing, "the workbook has no sheet", name);
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                sheetNames.add(sheet.getSheetName());
                cells.addAll(firstColumn(sheet, evaluator));
            }
        } catch (IOException | RuntimeException e) {
            return refused(apply, deactivateMissing, "the file is not an Excel workbook (.xlsx or .xls): " + file, name);
        }

        Map<String, RelatedControlRef> stored = new HashMap<>();
        for (RelatedControlRef ref : repository.findAll()) {
            stored.put(ref.getControlKey(), ref);
        }
        long activeBefore = stored.values().stream().filter(RelatedControlRef::isActive).count();
        List<Line> lines = new ArrayList<>();
        Map<String, CellText> rowOfKey = new HashMap<>();
        for (CellText cell : cells) {
            lines.add(line(cell, stored, rowOfKey));
        }

        List<RelatedControlRef> missing = new ArrayList<>();
        if (deactivateMissing) {
            if (rowOfKey.isEmpty()) {
                return refused(apply, true, "the file has no Control ID, so --deactivate-missing would make every "
                        + "Control ID of the list inactive", name);
            }
            stored.values().stream()
                    .filter(ref -> ref.isActive() && !rowOfKey.containsKey(ref.getControlKey()))
                    .sorted((a, b) -> a.getControlId().compareToIgnoreCase(b.getControlId()))
                    .forEach(missing::add);
        }
        Report report = new Report(apply, deactivateMissing, null, name, List.copyOf(sheetNames), List.copyOf(lines),
                missing.stream().map(RelatedControlRef::getControlId).toList());
        if (apply) {
            write(report, stored, missing);
            long activeAfter = repository.findAll().stream().filter(RelatedControlRef::isActive).count();
            auditService.logActionWithChanges(ACTOR_MAIL, "Related controls import (" + actor + ")", AUDIT_ACTION, null,
                    "Other Related Controls list from " + name + ": added " + report.count(Outcome.ADDED)
                            + ", updated " + report.count(Outcome.UPDATED)
                            + ", unchanged " + report.count(Outcome.UNCHANGED)
                            + ", skipped " + report.count(Outcome.SKIPPED)
                            + ", made inactive " + missing.size(),
                    "Other Related Controls list",
                    "active Control IDs: " + activeBefore,
                    "active Control IDs: " + activeAfter);
        }
        return report;
    }

    /** What the import does with a row; rowOfKey gets the first row of each Control ID. Nothing is changed here. */
    private static Line line(CellText cell, Map<String, RelatedControlRef> stored, Map<String, CellText> rowOfKey) {
        if (cell.problem() != null) {
            return new Line(cell.sheet(), cell.row(), Outcome.SKIPPED, null, cell.problem());
        }
        String value = cell.value() == null ? "" : cell.value().trim();
        if (value.isEmpty()) {
            return new Line(cell.sheet(), cell.row(), Outcome.SKIPPED, null, "empty");
        }
        if (value.length() > ValueList.MAX_VALUE_LENGTH) {
            return new Line(cell.sheet(), cell.row(), Outcome.SKIPPED, null,
                    "longer than " + ValueList.MAX_VALUE_LENGTH + " characters");
        }
        if (value.contains(ValueList.SEPARATOR)) {
            return new Line(cell.sheet(), cell.row(), Outcome.SKIPPED, value, "holds \";\", which separates the values");
        }
        String key = ValueList.key(value);
        CellText first = rowOfKey.putIfAbsent(key, cell);
        if (first != null) {
            String where = first.sheet().equals(cell.sheet()) ? "row " + first.row()
                    : "sheet " + first.sheet() + " row " + first.row();
            return new Line(cell.sheet(), cell.row(), Outcome.SKIPPED, value,
                    "the same Control ID as " + where + " (case and spaces do not count)");
        }
        RelatedControlRef ref = stored.get(key);
        if (ref == null) {
            return new Line(cell.sheet(), cell.row(), Outcome.ADDED, value, null);
        }
        List<String> changes = new ArrayList<>();
        if (!ref.isActive()) {
            changes.add("made active again");
        }
        if (!ref.getControlId().equals(value)) {
            changes.add("written \"" + ref.getControlId() + "\" before");
        }
        return changes.isEmpty() ? new Line(cell.sheet(), cell.row(), Outcome.UNCHANGED, value, null)
                : new Line(cell.sheet(), cell.row(), Outcome.UPDATED, value, String.join(", ", changes));
    }

    private void write(Report report, Map<String, RelatedControlRef> stored, List<RelatedControlRef> missing) {
        List<RelatedControlRef> changed = new ArrayList<>();
        for (Line line : report.lines()) {
            if (line.outcome() == Outcome.ADDED) {
                changed.add(new RelatedControlRef(line.controlId()));
            } else if (line.outcome() == Outcome.UPDATED) {
                RelatedControlRef ref = stored.get(ValueList.key(line.controlId()));
                ref.setActive(true);
                ref.setControlId(line.controlId());
                changed.add(ref);
            }
        }
        for (RelatedControlRef ref : missing) {
            ref.setActive(false);
            changed.add(ref);
        }
        repository.saveAll(changed);
        repository.flush();
    }

    private static Report refused(boolean apply, boolean deactivateMissing, String refusal, String file) {
        return new Report(apply, deactivateMissing, refusal, file, null, List.of(), List.of());
    }

    /** Column A of a sheet from the second row to the last one, a missing row or cell being empty. */
    static List<CellText> firstColumn(Sheet sheet, FormulaEvaluator evaluator) {
        DataFormatter formatter = new DataFormatter();
        List<CellText> cells = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            Cell cell = row == null ? null : row.getCell(0);
            int number = index + 1;
            try {
                cells.add(new CellText(sheet.getSheetName(), number, text(cell, evaluator, formatter), null));
            } catch (FormulaError e) {
                cells.add(new CellText(sheet.getSheetName(), number, null, "the formula gives an error"));
            }
        }
        return cells;
    }

    private static final class FormulaError extends RuntimeException {
    }

    /** The value the cell shows: a formula's result (its cached one if it cannot be worked out here), a number in full. */
    static String text(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            CellValue value;
            try {
                value = evaluator.evaluate(cell);
            } catch (RuntimeException e) {
                value = null;
            }
            if (value != null) {
                return switch (value.getCellType()) {
                    case STRING -> value.getStringValue();
                    case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                            ? formatter.formatCellValue(cell, evaluator) : number(value.getNumberValue());
                    case BOOLEAN -> String.valueOf(value.getBooleanValue()).toUpperCase();
                    case ERROR -> throw new FormulaError();
                    default -> "";
                };
            }
            type = cell.getCachedFormulaResultType();
            if (type == CellType.ERROR) {
                throw new FormulaError();
            }
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? formatter.formatCellValue(cell)
                    : number(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue()).toUpperCase();
            default -> "";
        };
    }

    // 12345.0 -> "12345", 1.5 -> "1.5", 1.23E12 -> "1230000000000"
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
