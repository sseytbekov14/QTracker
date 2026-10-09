package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Puts the deadline of the controls that are not completed on the current rule (ControlScheduleCalculator
 * .DEADLINE: operation date + 14 calendar days). Completed controls, the Next Control Operation Date and
 * the operation date are never touched. A dry run only reports; with apply each changed control gets the
 * new deadline in both stored columns and an "Edit Control" entry in its history and the audit trail.
 * Run it again and it finds nothing to change.
 *
 * <p>Started from the command line, see DeadlineRecalculationCommand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadlineRecalculation {

    static final String FIELD = "Control Operation Deadline";
    static final String AUDIT_EMAIL = "system";
    static final String AUDIT_DESCRIPTION = "Deadline recalculation";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;
    private final ControlScheduleCalculator scheduleCalculator;
    private final AdminAuditService adminAuditService;

    /** One control whose deadline differs from the rule's. */
    public record Change(Long id, String controlId, String frequency, String status, LocalDate operationDate,
                         LocalDate before, LocalDate after, boolean overdueBefore, boolean overdueAfter) {
    }

    /** A control that is not completed but has no deadline to work out. */
    public record Skipped(Long id, String controlId, String reason) {
    }

    public record Report(boolean applied, LocalDate today, int completedLeftOut, int checked, int unchanged,
                         List<Change> changes, List<Skipped> skipped) {

        public String render() {
            StringBuilder out = new StringBuilder();
            out.append(applied ? "Deadline recalculation: APPLIED" : "Deadline recalculation: DRY RUN, nothing written")
                    .append(" (rule: operation date + ").append(ControlScheduleCalculator.DEADLINE.days()).append(' ')
                    .append(ControlScheduleCalculator.DEADLINE.count().name().toLowerCase()).append(" days, today ")
                    .append(DAY.format(today)).append(")\n");
            out.append(String.format("%-40s %-12s %-22s %-10s %-10s %-10s %s%n",
                    "Control ID", "Frequency", "Status", "Operation", "Was", "Becomes", "Overdue was -> becomes"));
            for (Change change : changes) {
                out.append(String.format("%-40s %-12s %-22s %-10s %-10s %-10s %s -> %s%n",
                        change.controlId(), change.frequency(), change.status(), day(change.operationDate()),
                        day(change.before()), day(change.after()),
                        change.overdueBefore() ? "yes" : "no", change.overdueAfter() ? "yes" : "no"));
            }
            for (Skipped skipped : skipped) {
                out.append(String.format("skipped  %-40s %s%n", skipped.controlId(), skipped.reason()));
            }
            out.append(String.format("Not completed: %d; %s: %d; unchanged: %d; skipped: %d; completed left out: %d%n",
                    checked, applied ? "changed" : "would change", changes.size(), unchanged, skipped.size(),
                    completedLeftOut));
            if (!applied && !changes.isEmpty()) {
                out.append("Run again with --apply to write these deadlines.\n");
            }
            return out.toString();
        }

        private static String day(LocalDate date) {
            return date == null ? "-" : DAY.format(date);
        }
    }

    @Transactional
    public Report run(boolean apply, LocalDate today, String runBy) {
        Map<Long, ControlAssignment> assignments = assignmentRepository.findAll().stream()
                .filter(assignment -> assignment.getControlId() != null)
                .collect(Collectors.toMap(ControlAssignment::getControlId, Function.identity(), (a, b) -> a));
        List<Control> controls = controlRepository.findAll().stream()
                .sorted(Comparator.comparing(Control::getId))
                .toList();

        int completed = 0;
        int unchanged = 0;
        List<Change> changes = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Control control : controls) {
            if (DeadlineOverdue.isCompleted(control.getPerformanceStatus())) {
                completed++;
                continue;
            }
            ControlAssignment assignment = assignments.get(control.getId());
            LocalDate operationDate = assignment == null ? null : assignment.getControlOperationDate();
            if (operationDate == null) {
                skipped.add(new Skipped(control.getId(), control.getControlId(), "no Control Operation Date"));
                continue;
            }
            Optional<ControlFrequency> frequency = ControlFrequency.tryFromValue(control.getControlFrequency());
            if (frequency.isEmpty()) {
                skipped.add(new Skipped(control.getId(), control.getControlId(),
                        "frequency \"" + Objects.toString(control.getControlFrequency(), "") + "\" is not one QTracker knows"));
                continue;
            }
            LocalDate before = DeadlineOverdue.deadlineOf(assignment.getControlOperationDeadline(), control.getDeadline());
            LocalDate after = scheduleCalculator.calculateDeadline(frequency.get(), operationDate);
            if (after.equals(assignment.getControlOperationDeadline()) && after.equals(control.getDeadline())) {
                unchanged++;
                continue;
            }
            changes.add(new Change(control.getId(), control.getControlId(), control.getControlFrequency(),
                    control.getPerformanceStatus(), operationDate, before, after,
                    DeadlineOverdue.isOverdue(control.getPerformanceStatus(), before, today),
                    DeadlineOverdue.isOverdue(control.getPerformanceStatus(), after, today)));
            if (apply) {
                assignment.setControlOperationDeadline(after);
                assignmentRepository.save(assignment);
                control.setDeadline(after);
                controlRepository.save(control);
                logChange(control, before, after, runBy);
            }
        }
        Report report = new Report(apply, today, completed, controls.size() - completed, unchanged, changes, skipped);
        log.info("Deadline recalculation {}: {} not completed, {} {}, {} unchanged, {} skipped",
                apply ? "applied" : "dry run", report.checked(), changes.size(),
                apply ? "changed" : "would change", unchanged, skipped.size());
        return report;
    }

    private void logChange(Control control, LocalDate before, LocalDate after, String runBy) {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put(FIELD, before == null ? "" : before.toString());
        Map<String, String> updated = new LinkedHashMap<>();
        updated.put(FIELD, after.toString());
        Object saved;
        try {
            saved = adminAuditService.logActionWithChanges(AUDIT_EMAIL,
                    runBy == null || runBy.isBlank() ? AUDIT_DESCRIPTION : AUDIT_DESCRIPTION + " (" + runBy + ")",
                    "EDIT", control, AUDIT_DESCRIPTION,
                    JSON.writeValueAsString(List.of(FIELD)),
                    JSON.writeValueAsString(previous),
                    JSON.writeValueAsString(updated));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        // No deadline changes without their history: the whole run is rolled back
        if (saved == null) {
            throw new IllegalStateException("The audit entry for " + control.getControlId() + " could not be saved");
        }
    }
}
