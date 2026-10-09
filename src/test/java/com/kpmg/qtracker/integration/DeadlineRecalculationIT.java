package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.QtrackerApplication;
import com.kpmg.qtracker.dto.ControlHistoryEntryDTO;
import com.kpmg.qtracker.dto.FieldChangeDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.service.ControlHistoryService;
import com.kpmg.qtracker.service.DeadlineRecalculation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deadline recalculation command: a dry run writes nothing, --apply puts the deadline of the controls
 * that are not completed on operation date + 14 days in both stored columns with an entry in their history
 * and the audit trail, and a second run finds nothing to change. Completed controls, the operation date
 * and the Next Control Operation Date stay as they are.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=" + DeadlineRecalculationIT.DB,
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "file.upload.dir=target/it-uploads-recalc"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DeadlineRecalculationIT {

    static final String DB = "jdbc:h2:mem:deadline-recalc-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    @Autowired
    private DeadlineRecalculation recalculation;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private ControlHistoryService historyService;

    @BeforeEach
    void seed() {
        auditLogRepository.deleteAll();
        controlRepository.deleteAll();
        // The earlier rule: Monthly + 7 days, Annual / Semi Annual + 1 month
        control("REC-M1", "Monthly", "IN_PROGRESS", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 25));
        control("REC-A1", "Annual", "DRAFT", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2027, 10, 1));
        control("REC-S1", "Semi Annual", "REVIEW", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 1));
        control("REC-Q1", "Quarterly", "IN_PROGRESS", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), LocalDate.of(2027, 1, 1));
        control("REC-C1", "Monthly", "COMPLETED", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), LocalDate.of(2026, 10, 1));
        control("REC-N1", "Monthly", "IN_PROGRESS", null, null, null);
        control("REC-U1", "Weekly", "IN_PROGRESS", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 7), null);
    }

    @Test
    void dryRun_reportsWasAndBecomes_andWritesNothing() {
        DeadlineRecalculation.Report report = recalculation.run(false, TODAY, "tester");

        assertThat(report.applied()).isFalse();
        assertThat(report.checked()).isEqualTo(6);
        assertThat(report.completedLeftOut()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(1);
        Map<String, DeadlineRecalculation.Change> changes = byId(report.changes());
        assertThat(changes).containsOnlyKeys("REC-M1", "REC-A1", "REC-S1");
        assertThat(changes.get("REC-M1").before()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(changes.get("REC-M1").after()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(changes.get("REC-M1").overdueBefore()).isTrue();
        assertThat(changes.get("REC-M1").overdueAfter()).isFalse();
        assertThat(changes.get("REC-A1").after()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(changes.get("REC-S1").after()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(changes.get("REC-S1").overdueAfter()).isTrue();
        assertThat(report.skipped()).extracting(DeadlineRecalculation.Skipped::controlId)
                .containsExactlyInAnyOrder("REC-N1", "REC-U1");
        assertThat(report.render())
                .contains("DRY RUN, nothing written")
                .contains("REC-M1").contains("02.10.2026").contains("09.10.2026")
                .contains("would change: 3")
                .contains("Run again with --apply");

        assertThat(deadline("REC-M1")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(controlDeadline("REC-M1")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(auditLogRepository.count()).isZero();
    }

    @Test
    void apply_writesBothColumnsWithHistoryAndAudit_andLeavesTheRestAsItWas() {
        DeadlineRecalculation.Report report = recalculation.run(true, TODAY, "tester");

        assertThat(report.applied()).isTrue();
        assertThat(report.changes()).hasSize(3);
        assertThat(report.render()).contains("APPLIED").contains("changed: 3");

        assertThat(deadline("REC-M1")).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(controlDeadline("REC-M1")).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(deadline("REC-A1")).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(deadline("REC-S1")).isEqualTo(LocalDate.of(2026, 9, 15));
        // Operation date and Next Control Operation Date as they were
        ControlAssignment monthly = assignment("REC-M1");
        assertThat(monthly.getControlOperationDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(monthly.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(assignment("REC-A1").getNextControlOperationDate()).isEqualTo(LocalDate.of(2027, 10, 1));
        // Completed, already right, no date, unknown frequency: untouched
        assertThat(deadline("REC-C1")).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(deadline("REC-Q1")).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(deadline("REC-U1")).isEqualTo(LocalDate.of(2026, 10, 7));

        List<AdminAuditLog> audit = auditLogRepository.findAll();
        assertThat(audit).extracting(AdminAuditLog::getControlControlId)
                .containsExactlyInAnyOrder("REC-M1", "REC-A1", "REC-S1");
        assertThat(audit).allSatisfy(entry -> {
            assertThat(entry.getActionType()).isEqualTo("EDIT");
            assertThat(entry.getAdminEmail()).isEqualTo("system");
            assertThat(entry.getAdminName()).isEqualTo("Deadline recalculation (tester)");
        });

        List<ControlHistoryEntryDTO> history = historyService.getControlHistory(control("REC-M1").getId());
        ControlHistoryEntryDTO edit = history.stream()
                .filter(entry -> "Edit Control".equals(entry.getEventName()))
                .findFirst().orElseThrow();
        assertThat(edit.getActorName()).isEqualTo("Deadline recalculation (tester)");
        assertThat(edit.getFieldChanges()).extracting(FieldChangeDTO::getField, FieldChangeDTO::getOldValue,
                        FieldChangeDTO::getNewValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "Control Operation Deadline", "2026-10-02", "2026-10-09"));
    }

    @Test
    void secondRun_findsNothingToChange() {
        recalculation.run(true, TODAY, "tester");
        long auditEntries = auditLogRepository.count();

        DeadlineRecalculation.Report again = recalculation.run(true, TODAY, "tester");

        assertThat(again.changes()).isEmpty();
        assertThat(again.unchanged()).isEqualTo(4);
        assertThat(auditLogRepository.count()).isEqualTo(auditEntries);
        assertThat(recalculation.run(false, TODAY, "tester").render()).contains("would change: 0")
                .doesNotContain("--apply");
    }

    @Test
    void fromTheCommandLine_dryRunByDefault_writesOnlyWithApply(CapturedOutput output) {
        try (ConfigurableApplicationContext ignored = QtrackerApplication.runCommand(commandArgs("--recalculate-deadlines"))) {
            assertThat(output.getOut()).contains("DRY RUN, nothing written").contains("would change: 3");
        }
        assertThat(deadline("REC-M1")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(auditLogRepository.count()).isZero();

        try (ConfigurableApplicationContext ignored =
                     QtrackerApplication.runCommand(commandArgs("--recalculate-deadlines", "--apply"))) {
            assertThat(output.getOut()).contains("APPLIED").contains("changed: 3");
        }
        assertThat(deadline("REC-M1")).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(auditLogRepository.count()).isEqualTo(3);
    }

    // The command's own context: no web server, the schema and rows of this test's database kept
    private static String[] commandArgs(String... options) {
        List<String> args = new java.util.ArrayList<>(List.of(options));
        args.addAll(List.of(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + DB,
                "--spring.jpa.hibernate.ddl-auto=none",
                "--reminders.enabled=false",
                "--controls.auto-create.enabled=false",
                "--file.upload.dir=target/it-uploads-recalc"));
        return args.toArray(String[]::new);
    }

    private void control(String controlId, String frequency, String status, LocalDate operationDate,
                         LocalDate deadline, LocalDate nextDate) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency(frequency);
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control.setControlDescription("Description of " + controlId);
        control.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        control.setDeadline(deadline);
        control = controlRepository.save(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator("facilitator@recalc.test");
        assignment.setControlOperationDate(operationDate);
        assignment.setControlOperationDeadline(deadline);
        assignment.setNextControlOperationDate(nextDate);
        assignmentRepository.save(assignment);
    }

    private Control control(String controlId) {
        return controlRepository.findByControlId(controlId).orElseThrow();
    }

    private ControlAssignment assignment(String controlId) {
        return assignmentRepository.findByControlId(control(controlId).getId()).orElseThrow();
    }

    private LocalDate deadline(String controlId) {
        return assignment(controlId).getControlOperationDeadline();
    }

    private LocalDate controlDeadline(String controlId) {
        return control(controlId).getDeadline();
    }

    private static Map<String, DeadlineRecalculation.Change> byId(List<DeadlineRecalculation.Change> changes) {
        return changes.stream().collect(Collectors.toMap(DeadlineRecalculation.Change::controlId, change -> change));
    }
}
