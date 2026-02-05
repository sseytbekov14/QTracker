package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlNotificationLog;
import com.kpmg.qtracker.repository.ControlNotificationLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    @Value("${overdue.use-working-days:true}")
    private boolean useWorkingDays = true;

    private static final String STATUS_COMPLETED = "Completed";

    private static final String CODE_START = "START";
    private static final String CODE_REMINDER_1 = "REMINDER_1";
    private static final String CODE_REMINDER_2 = "REMINDER_2";
    private static final String CODE_OVERDUE = "OVERDUE";

    private static final Map<FrequencyGroup, int[]> FREQUENCY_OFFSETS = new EnumMap<>(FrequencyGroup.class);

    static {
        FREQUENCY_OFFSETS.put(FrequencyGroup.MONTHLY, new int[]{0, 3, 6});
        FREQUENCY_OFFSETS.put(FrequencyGroup.QUARTERLY, new int[]{0, 5, 12});
        FREQUENCY_OFFSETS.put(FrequencyGroup.RECURRING, new int[]{0, 5, 12});
        FREQUENCY_OFFSETS.put(FrequencyGroup.AD_HOC, new int[]{0, 5, 12});
        FREQUENCY_OFFSETS.put(FrequencyGroup.ANNUAL, new int[]{0, 5, 25});
        FREQUENCY_OFFSETS.put(FrequencyGroup.SEMI_ANNUAL, new int[]{0, 5, 25});
    }

    @Transactional
    public void runDailyReminders(LocalDate today) {
        List<Control> controls = controlRepository.findAll();
        for (Control control : controls) {
            if (control == null) {
                continue;
            }
            String status = control.getControlStatus();
            if (STATUS_COMPLETED.equalsIgnoreCase(status)) {
                continue;
            }
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            if (assignment == null) {
                continue;
            }
            processControl(control, assignment, today);
        }
    }

    void processControl(Control control, ControlAssignmentDTO assignment, LocalDate today) {
        LocalDate operationDate = assignment.getControlOperationDate();
        LocalDate deadlineDate = assignment.getControlOperationDeadline() != null
                ? assignment.getControlOperationDeadline()
                : control.getDeadline();

        FrequencyGroup group = FrequencyGroup.fromValue(control.getControlFrequency());
        if (group != FrequencyGroup.UNKNOWN && operationDate != null) {
            int daysFromStart = (int) ChronoUnit.DAYS.between(operationDate, today);
            if (daysFromStart >= 0) {
                triggerByFrequency(control, assignment, today, daysFromStart, group);
            }
        }

        if (deadlineDate != null && today.isAfter(deadlineDate)) {
            maybeSendOverdue(control, assignment, today, deadlineDate);
        }
    }

    private void triggerByFrequency(Control control,
                                    ControlAssignmentDTO assignment,
                                    LocalDate today,
                                    int daysFromStart,
                                    FrequencyGroup group) {
        int[] offsets = FREQUENCY_OFFSETS.get(group);
        if (offsets == null) {
            return;
        }
        if (daysFromStart == offsets[0]) {
            sendIfNotLogged(control, today, CODE_START,
                    NotificationTemplateService.TemplateType.ACTIVATION,
                    getAllParticipants(assignment));
        } else if (daysFromStart == offsets[1]) {
            boolean responseExists = hasResponse(control);
            sendIfNotLogged(control, today, CODE_REMINDER_1,
                    responseExists
                            ? NotificationTemplateService.TemplateType.REMINDER_1_FORWARD
                            : NotificationTemplateService.TemplateType.REMINDER_1_OPEN,
                    getBlockingParticipants(control.getControlStatus(), assignment));
        } else if (daysFromStart == offsets[2]) {
            boolean responseExists = hasResponse(control);
            sendIfNotLogged(control, today, CODE_REMINDER_2,
                    responseExists
                            ? NotificationTemplateService.TemplateType.REMINDER_2_FORWARD
                            : NotificationTemplateService.TemplateType.REMINDER_2_OPEN,
                    getBlockingParticipants(control.getControlStatus(), assignment));
        }
    }

    private void maybeSendOverdue(Control control,
                                  ControlAssignmentDTO assignment,
                                  LocalDate today,
                                  LocalDate deadlineDate) {
        int daysSinceOverdue = useWorkingDays
                ? countWorkingDays(deadlineDate.plusDays(1), today)
                : countCalendarDays(deadlineDate.plusDays(1), today);
        if (daysSinceOverdue < 2 || daysSinceOverdue > 6) {
            return;
        }
        if ((daysSinceOverdue - 2) % 2 != 0) {
            return;
        }
        sendIfNotLogged(control, today, CODE_OVERDUE,
                NotificationTemplateService.TemplateType.DEADLINE,
                getBlockingParticipants(control.getControlStatus(), assignment));
    }

    private void sendIfNotLogged(Control control,
                                 LocalDate scheduledDate,
                                 String code,
                                 NotificationTemplateService.TemplateType templateType,
                                 List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        if (logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(
                control.getId(), code, scheduledDate)) {
            return;
        }
        notificationService.sendTemplateNotifications(control, recipients, templateType, false);
        ControlNotificationLog logRow = new ControlNotificationLog();
        logRow.setControlId(control.getId());
        logRow.setNotificationCode(code);
        logRow.setScheduledDate(scheduledDate);
        logRepository.save(logRow);
        log.info("Scheduled reminder sent: control={}, code={}, date={}",
                control.getId(), code, scheduledDate);
    }

    private List<String> getAllParticipants(ControlAssignmentDTO assignment) {
        Set<String> emails = new LinkedHashSet<>();
        addAll(emails, assignment.getFacilitator());
        addAll(emails, assignment.getControlOperator());
        addAll(emails, assignment.getProcessOwner());
        addAll(emails, assignment.getSoqmLead());
        return new ArrayList<>(emails);
    }

    private List<String> getBlockingParticipants(String status, ControlAssignmentDTO assignment) {
        if (status == null) {
            return List.of();
        }
        String normalized = status.trim();
        if ("In Progress".equalsIgnoreCase(normalized) || "Facilitator Review".equalsIgnoreCase(normalized)) {
            return firstOnly(assignment.getFacilitator());
        }
        if ("Control Operator Review".equalsIgnoreCase(normalized)) {
            return firstOnly(assignment.getControlOperator());
        }
        return List.of();
    }

    private List<String> firstOnly(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String first = items.get(0);
        if (first == null || first.isBlank()) {
            return List.of();
        }
        return List.of(first.trim());
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

    private int countWorkingDays(LocalDate startInclusive, LocalDate endInclusive) {
        if (startInclusive == null || endInclusive == null || endInclusive.isBefore(startInclusive)) {
            return 0;
        }
        int count = 0;
        LocalDate current = startInclusive;
        while (!current.isAfter(endInclusive)) {
            DayOfWeek dow = current.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                count++;
            }
            current = current.plusDays(1);
        }
        return count;
    }

    private int countCalendarDays(LocalDate startInclusive, LocalDate endInclusive) {
        if (startInclusive == null || endInclusive == null || endInclusive.isBefore(startInclusive)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(startInclusive, endInclusive) + 1;
    }

    private boolean hasResponse(Control control) {
        if (control == null || control.getId() == null) {
            return false;
        }
        if (isNotBlank(control.getControlOperatorsProgram())) {
            return true;
        }
        ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(control.getId());
        return details != null && (isNotBlank(details.getControlStepsPerformed())
                || isNotBlank(details.getControlOperatorsProgram()));
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
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
