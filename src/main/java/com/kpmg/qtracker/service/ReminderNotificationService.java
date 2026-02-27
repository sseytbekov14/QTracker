package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlNotificationLog;
import com.kpmg.qtracker.repository.ControlNotificationLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderNotificationService {

    private final ControlRepository controlRepository;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlDetailsService controlDetailsService;
    private final NotificationService notificationService;
    private final ControlNotificationLogRepository logRepository;
    private final WorkingDaysService workingDaysService;

    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final String CODE_REMINDER_1 = "REMINDER_1";
    private static final String CODE_REMINDER_2 = "REMINDER_2";
    private static final String CODE_OVERDUE_1 = "OVERDUE_1";
    private static final String CODE_OVERDUE_REPEAT = "OVERDUE_REPEAT";

    private static final Map<FrequencyGroup, int[]> REMINDER_OFFSETS = new EnumMap<>(FrequencyGroup.class);

    static {
        REMINDER_OFFSETS.put(FrequencyGroup.MONTHLY, new int[]{3, 6});
        REMINDER_OFFSETS.put(FrequencyGroup.QUARTERLY, new int[]{5, 12});
        REMINDER_OFFSETS.put(FrequencyGroup.RECURRING, new int[]{5, 12});
        REMINDER_OFFSETS.put(FrequencyGroup.AD_HOC, new int[]{5, 12});
        REMINDER_OFFSETS.put(FrequencyGroup.ANNUAL, new int[]{5, 25});
        REMINDER_OFFSETS.put(FrequencyGroup.SEMI_ANNUAL, new int[]{5, 25});
    }

    @Transactional
    public void runDailyReminders(LocalDate today) {
        runDailyRemindersWithSummary(today);
    }

    @Transactional
    public ReminderRunSummary runDailyRemindersWithSummary(LocalDate today) {
        ReminderRunSummary summary = new ReminderRunSummary(today);
        List<ReminderControlProjection> rows = controlRepository.findAllForReminders();
        log.info("Reminder run started for {} (controls found={})", today, rows.size());
        for (ReminderControlProjection row : rows) {
            if (row == null) {
                continue;
            }
            Control control = buildControl(row);
            String status = control.getPerformanceStatus();
            if (isExcludedStatus(status)) {
                summary.addResult(ControlReminderResult.skipped(control, "status excluded", status));
                continue;
            }
            ControlAssignmentDTO assignment = buildAssignment(row);
            ControlReminderResult result = processControlWithSummary(control, assignment, today);
            summary.addResult(result);
        }
        log.info("Reminder run finished for {} (processed={}, sent={}, dedupedSkips={})",
                today,
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount());
        return summary;
    }

    void processControl(Control control, ControlAssignmentDTO assignment, LocalDate today) {
        processControlWithSummary(control, assignment, today);
    }

    private ControlReminderResult processControlWithSummary(Control control,
                                                            ControlAssignmentDTO assignment,
                                                            LocalDate today) {
        if (control == null || isExcludedStatus(control.getPerformanceStatus())) {
            return ControlReminderResult.skipped(control, "status excluded", control != null ? control.getPerformanceStatus() : null);
        }
        LocalDate operationDate = assignment.getControlOperationDate();
        LocalDate deadlineDate = assignment.getControlOperationDeadline();
        if (operationDate == null || deadlineDate == null) {
            ControlReminderResult result = ControlReminderResult.skipped(control, "missing operation or deadline date", control.getPerformanceStatus());
            log.debug("Skipping reminder: controlId={}, reason={}", result.getControlId(), result.getReason());
            return result;
        }
        DueNotification due = determineDueNotification(control, assignment, today, operationDate, deadlineDate);
        if (due == null) {
            ControlReminderResult result = ControlReminderResult.skipped(control, "not due today", control.getPerformanceStatus());
            log.debug("Skipping reminder: controlId={}, reason={}", result.getControlId(), result.getReason());
            return result;
        }
        log.info("Reminder due: controlId={}, status={}, code={}, template={}, recipients={}",
                control.getId(), control.getPerformanceStatus(), due.code(), due.templateType(), due.recipients());
        SendOutcome outcome = sendIfNotLogged(control, today, due.code(), due.templateType(), due.recipients());
        return ControlReminderResult.sent(control,
                operationDate,
                deadlineDate,
                due.code(),
                due.templateType().name(),
                due.recipients(),
                outcome.deduped(),
                outcome.sent());
    }

    private DueNotification determineDueNotification(Control control,
                                                     ControlAssignmentDTO assignment,
                                                     LocalDate today,
                                                     LocalDate operationDate,
                                                     LocalDate deadlineDate) {
        DueNotification overdue = determineOverdueNotification(control, assignment, today, deadlineDate);
        if (overdue != null) {
            return overdue;
        }

        return determineReminderNotification(control, assignment, today, operationDate);
    }

    private DueNotification determineReminderNotification(Control control,
                                                          ControlAssignmentDTO assignment,
                                                          LocalDate today,
                                                          LocalDate operationDate) {
        FrequencyGroup group = FrequencyGroup.fromValue(control.getControlFrequency());
        if (group == FrequencyGroup.UNKNOWN || operationDate == null) {
            return null;
        }
        int[] offsets = REMINDER_OFFSETS.get(group);
        if (offsets == null) {
            return null;
        }
        LocalDate reminder1Date = workingDaysService.addWorkingDays(operationDate, offsets[0]);
        if (today.equals(reminder1Date)) {
            boolean responseExists = hasResponse(control);
            return new DueNotification(
                    CODE_REMINDER_1,
                    responseExists
                            ? NotificationTemplateService.TemplateType.REMINDER_1_FORWARD
                            : NotificationTemplateService.TemplateType.REMINDER_1_OPEN,
                    getReminderRecipients(assignment)
            );
        }
        LocalDate reminder2Date = workingDaysService.addWorkingDays(operationDate, offsets[1]);
        if (today.equals(reminder2Date)) {
            boolean responseExists = hasResponse(control);
            return new DueNotification(
                    CODE_REMINDER_2,
                    responseExists
                            ? NotificationTemplateService.TemplateType.REMINDER_2_FORWARD
                            : NotificationTemplateService.TemplateType.REMINDER_2_OPEN,
                    getReminderRecipients(assignment)
            );
        }
        return null;
    }

    private DueNotification determineOverdueNotification(Control control,
                                                         ControlAssignmentDTO assignment,
                                                         LocalDate today,
                                                         LocalDate deadlineDate) {
        if (deadlineDate == null || !today.isAfter(deadlineDate)) {
            return null;
        }
        LocalDate overdueStart = workingDaysService.addWorkingDays(deadlineDate, 1);
        LocalDate overdue1Date = workingDaysService.addWorkingDays(overdueStart, 1);

        if (today.equals(overdue1Date)) {
            return new DueNotification(
                    CODE_OVERDUE_1,
                    NotificationTemplateService.TemplateType.DEADLINE,
                    getOverdueRecipients(assignment)
            );
        }

        if (!today.isAfter(overdue1Date)) {
            return null;
        }

        LocalDate windowEnd = workingDaysService.addWorkingDays(overdue1Date, 6);
        if (today.isAfter(windowEnd)) {
            return null;
        }
        if (!workingDaysService.isWorkingDay(today)) {
            return null;
        }
        int workingDaysSinceOverdue1 = workingDaysService.workingDaysBetween(overdue1Date, today);
        if (workingDaysSinceOverdue1 <= 0 || workingDaysSinceOverdue1 % 2 != 0) {
            return null;
        }
        return new DueNotification(
                CODE_OVERDUE_REPEAT,
                NotificationTemplateService.TemplateType.DEADLINE,
                getOverdueRecipients(assignment)
        );
    }

    private SendOutcome sendIfNotLogged(Control control,
                                        LocalDate scheduledDate,
                                        String code,
                                        NotificationTemplateService.TemplateType templateType,
                                        List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            log.debug("No recipients for control {} and code {} on {}", control.getId(), code, scheduledDate);
            return new SendOutcome(false, false);
        }
        String dedupeKey = buildDedupeKey(control.getId(), code, scheduledDate);
        if (logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(
                control.getId(), code, scheduledDate)) {
            log.info("Skipping duplicate scheduled notification {}", dedupeKey);
            return new SendOutcome(false, true);
        }
        log.info("Sending scheduled reminder: control={}, code={}, template={}, recipients={}",
                control.getId(), code, templateType, recipients);
        notificationService.sendTemplateNotifications(control, recipients, templateType, false);
        ControlNotificationLog logRow = new ControlNotificationLog();
        logRow.setControlId(control.getId());
        logRow.setNotificationCode(code);
        logRow.setScheduledDate(scheduledDate);
        logRepository.save(logRow);
        log.debug("Scheduled reminder sent: control={}, code={}, date={}, dedupeKey={}",
                control.getId(), code, scheduledDate, dedupeKey);
        return new SendOutcome(true, false);
    }

    private List<String> getReminderRecipients(ControlAssignmentDTO assignment) {
        Set<String> emails = new LinkedHashSet<>();
        addAll(emails, assignment.getFacilitator());
        addAll(emails, assignment.getControlOperator());
        return new ArrayList<>(emails);
    }

    private List<String> getOverdueRecipients(ControlAssignmentDTO assignment) {
        Set<String> emails = new LinkedHashSet<>();
        addAll(emails, assignment.getFacilitator());
        addAll(emails, assignment.getControlOperator());
        return new ArrayList<>(emails);
    }

    private void addAll(Set<String> target, List<String> items) {
        if (items == null) {
            return;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                target.add(item.trim());
            }
        }
    }

    private boolean isExcludedStatus(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return !"IN_PROGRESS".equals(normalized)
                && !"REVIEW".equals(normalized);
    }

    private boolean hasResponse(Control control) {
        if (control == null || control.getId() == null) {
            return false;
        }
        ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(control.getId());
        return details != null && isNotBlank(details.getControlStepsPerformed());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String buildDedupeKey(Long controlId, String code, LocalDate scheduledDate) {
        return "scheduled:" + controlId + ":" + code + ":" + scheduledDate;
    }

    private Control buildControl(ReminderControlProjection row) {
        Control control = new Control();
        control.setId(row.getControlId());
        control.setControlId(row.getControlName());
        control.setControlDescription(row.getControlDescription());
        control.setControlFrequency(row.getFrequency());
        control.setControlStatus(row.getStatus());
        control.setDeadline(row.getDeadlineDate());
        control.setControlOperationDate(row.getOperationDate());
        return control;
    }

    private ControlAssignmentDTO buildAssignment(ReminderControlProjection row) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlId(row.getControlId());
        dto.setControlOperationDate(row.getOperationDate());
        dto.setControlOperationDeadline(row.getDeadlineDate());
        dto.setFacilitator(splitEmails(row.getFacilitator()));
        dto.setControlOperator(splitEmails(row.getControlOperator()));
        dto.setSoqmLead(splitEmails(row.getSoqmLead()));
        dto.setProcessOwner(splitEmails(row.getProcessOwner()));
        return dto;
    }

    private List<String> splitEmails(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    @Getter
    public static class ReminderRunSummary {
        private final LocalDate runDate;
        private final List<ControlReminderResult> results = new ArrayList<>();

        public ReminderRunSummary(LocalDate runDate) {
            this.runDate = runDate;
        }

        public void addResult(ControlReminderResult result) {
            if (result != null) {
                results.add(result);
            }
        }

        public int getProcessedControlsCount() {
            return results.size();
        }

        public long getSentCount() {
            return results.stream().filter(ControlReminderResult::isSent).count();
        }

        public long getDedupedCount() {
            return results.stream().filter(ControlReminderResult::isDeduped).count();
        }
    }

    @Getter
    public static class ControlReminderResult {
        private final Long controlId;
        private final String controlName;
        private final String frequency;
        private final String status;
        private final LocalDate operationDate;
        private final LocalDate deadlineDate;
        private final String selectedNotificationCode;
        private final String selectedNotificationType;
        private final List<String> recipients;
        private final boolean deduped;
        private final boolean sent;
        private final String reason;

        private ControlReminderResult(Long controlId,
                                      String controlName,
                                      String frequency,
                                      String status,
                                      LocalDate operationDate,
                                      LocalDate deadlineDate,
                                      String selectedNotificationCode,
                                      String selectedNotificationType,
                                      List<String> recipients,
                                      boolean deduped,
                                      boolean sent,
                                      String reason) {
            this.controlId = controlId;
            this.controlName = controlName;
            this.frequency = frequency;
            this.status = status;
            this.operationDate = operationDate;
            this.deadlineDate = deadlineDate;
            this.selectedNotificationCode = selectedNotificationCode;
            this.selectedNotificationType = selectedNotificationType;
            this.recipients = recipients == null ? Collections.emptyList() : recipients;
            this.deduped = deduped;
            this.sent = sent;
            this.reason = reason;
        }

        public static ControlReminderResult skipped(Control control, String reason, String status) {
            return new ControlReminderResult(
                    control != null ? control.getId() : null,
                    control != null ? control.getControlId() : null,
                    control != null ? control.getControlFrequency() : null,
                    status,
                    null,
                    null,
                    null,
                    null,
                    Collections.emptyList(),
                    false,
                    false,
                    reason
            );
        }

        public static ControlReminderResult sent(Control control,
                                                 LocalDate operationDate,
                                                 LocalDate deadlineDate,
                                                 String code,
                                                 String type,
                                                 List<String> recipients,
                                                 boolean deduped,
                                                 boolean sent) {
            return new ControlReminderResult(
                    control != null ? control.getId() : null,
                    control != null ? control.getControlId() : null,
                    control != null ? control.getControlFrequency() : null,
                    control != null ? control.getPerformanceStatus() : null,
                    operationDate,
                    deadlineDate,
                    code,
                    type,
                    recipients,
                    deduped,
                    sent,
                    deduped ? "skipped due to dedupe" : null
            );
        }
    }

    private record SendOutcome(boolean sent, boolean deduped) {
    }

    private record DueNotification(String code,
                                   NotificationTemplateService.TemplateType templateType,
                                   List<String> recipients) {
    }

    enum FrequencyGroup {
        MONTHLY,
        QUARTERLY,
        RECURRING,
        AD_HOC,
        ANNUAL,
        SEMI_ANNUAL,
        UNKNOWN;

        static FrequencyGroup fromValue(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (value.contains("month")) {
                return MONTHLY;
            }
            if (value.contains("quarter")) {
                return QUARTERLY;
            }
            if (value.contains("recurr")) {
                return RECURRING;
            }
            if (value.contains("ad") && value.contains("hoc")) {
                return AD_HOC;
            }
            if (value.contains("semi")) {
                return SEMI_ANNUAL;
            }
            if (value.contains("annual")) {
                return ANNUAL;
            }
            return UNKNOWN;
        }
    }
}
