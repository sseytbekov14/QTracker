package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnnualSemiDay5Day25ReminderService {

    static final String TYPE_DAY5 = "ANNUAL_SEMI_DAY5";
    static final String TYPE_DAY25 = "ANNUAL_SEMI_DAY25";

    private static final String SUBJECT_PREFIX = "Control reminder: ";

    private final ControlRepository controlRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ControlDetailsService controlDetailsService;
    private final NotificationTemplateService notificationTemplateService;
    private final Clock clock;

    @Transactional
    public DayReminderRunSummary runDailyReminders() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        List<ReminderControlProjection> rows = controlRepository.findAnnualSemiDay5Day25Candidates();
        int processed = 0;
        int sent = 0;
        int deduped = 0;
        int skipped = 0;
        LocalDateTime runAt = LocalDateTime.now(clock);

        for (ReminderControlProjection row : rows) {
            processed++;
            if (row == null) {
                skipped++;
                continue;
            }
            if (!isAnnualOrSemi(row.getFrequency())) {
                skipped++;
                continue;
            }
            if (isExcludedStatus(row.getStatus())) {
                skipped++;
                continue;
            }
            LocalDate operationDate = row.getOperationDate();
            if (operationDate == null) {
                skipped++;
                continue;
            }
            String notificationType = determineType(today, operationDate);
            if (notificationType == null) {
                continue;
            }
            Long controlId = row.getControlId();
            if (controlId == null) {
                skipped++;
                continue;
            }
            if (notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    controlId,
                    notificationType,
                    dayStart,
                    nextDayStart)) {
                deduped++;
                continue;
            }
            Role role = resolveRole(row.getStatus());
            if (role == null) {
                skipped++;
                continue;
            }
            List<String> recipients = recipientsForRole(role, row);
            if (recipients.isEmpty()) {
                skipped++;
                continue;
            }
            Control control = buildControl(row);
            boolean hasResponse = hasFacilitatorResponse(controlId);
            sent += sendNotifications(control, row.getDeadlineDate(), recipients, notificationType, hasResponse, runAt);
        }

        return new DayReminderRunSummary(today, processed, sent, deduped, skipped);
    }

    private String determineType(LocalDate today, LocalDate operationDate) {
        if (today.equals(operationDate.plusDays(5))) {
            return TYPE_DAY5;
        }
        if (today.equals(operationDate.plusDays(25))) {
            return TYPE_DAY25;
        }
        return null;
    }

    private boolean hasFacilitatorResponse(Long controlId) {
        if (controlId == null) {
            return false;
        }
        ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(controlId);
        return details != null && isNotBlank(details.getControlStepsPerformed());
    }

    private int sendNotifications(Control control,
                                  LocalDate deadlineDate,
                                  List<String> recipients,
                                  String type,
                                  boolean hasResponse,
                                  LocalDateTime runAt) {
        String controlName = buildControlName(control);
        String subject = SUBJECT_PREFIX + controlName;
        String link = notificationTemplateService.buildControlLink(control);
        String body = TYPE_DAY5.equals(type)
                ? ControlNotificationText.reminder1Body(control, deadlineDate, link)
                : ControlNotificationText.reminder2Body(control, deadlineDate, link);
        int created = 0;
        for (String email : recipients) {
            if (email == null || email.isBlank()) {
                continue;
            }
            Optional<User> userOpt = userRepository.findByMail(email.trim());
            if (userOpt.isEmpty()) {
                continue;
            }
            User user = userOpt.get();
            Notification notification = new Notification();
            notification.setUserId(user.getId());
            notification.setControlId(control.getId());
            notification.setType(type);
            notification.setTitle(subject);
            notification.setMessage(body);
            notification.setLink(link);
            notification.setIsRead(false);
            notification.setCreatedAt(runAt);
            notificationRepository.save(notification);
            created++;
        }
        return created;
    }

    private Control buildControl(ReminderControlProjection row) {
        Control control = new Control();
        control.setId(row.getControlId());
        control.setControlId(row.getControlName());
        control.setControlDescription(row.getControlDescription());
        control.setControlFrequency(row.getFrequency());
        control.setControlStatus(row.getStatus());
        return control;
    }

    private String buildControlName(Control control) {
        if (control == null) {
            return "Control";
        }
        String name = control.getControlId();
        if (name == null || name.trim().isEmpty()) {
            return "Control";
        }
        return name.trim();
    }

    private boolean isAnnualOrSemi(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("annual") || normalized.equals("annually")) {
            return true;
        }
        if (normalized.equals("semi annual") || normalized.equals("semi-annual")
                || normalized.equals("semiannually") || normalized.equals("semi-annually")) {
            return true;
        }
        return normalized.equals("semiannual");
    }

    private boolean isExcludedStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = normalizeStatus(status);
        return "DRAFT".equals(normalized)
                || "COMPLETED".equals(normalized);
    }

    private Role resolveRole(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = normalizeStatus(status);
        return switch (normalized) {
            case "IN_PROGRESS" -> Role.FACILITATOR;
            case "REVIEW" -> Role.CONTROL_OPERATOR;
            case "SOQM_HEAD_REVIEW" -> Role.SOQM_LEAD;
            case "PROCESS_OWNER_REVIEW" -> Role.PROCESS_OWNER;
            default -> null;
        };
    }

    private List<String> recipientsForRole(Role role, ReminderControlProjection row) {
        Set<String> recipients = new LinkedHashSet<>();
        switch (role) {
            case FACILITATOR -> addRecipients(recipients, splitEmails(row.getFacilitator()));
            case CONTROL_OPERATOR -> addRecipients(recipients, splitEmails(row.getControlOperator()));
            case SOQM_LEAD -> addRecipients(recipients, splitEmails(row.getSoqmLead()));
            case PROCESS_OWNER -> addRecipients(recipients, splitEmails(row.getProcessOwner()));
        }
        return new ArrayList<>(recipients);
    }

    private void addRecipients(Set<String> target, List<String> emails) {
        if (emails == null) {
            return;
        }
        for (String email : emails) {
            if (email == null || email.isBlank()) {
                continue;
            }
            target.add(email.trim());
        }
    }

    private List<String> splitEmails(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                results.add(trimmed);
            }
        }
        return results;
    }

    private String normalizeStatus(String status) {
        return status.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    enum Role {
        FACILITATOR,
        CONTROL_OPERATOR,
        SOQM_LEAD,
        PROCESS_OWNER
    }

    @Getter
    public static class DayReminderRunSummary {
        private final LocalDate runDate;
        private final int processedControlsCount;
        private final int sentCount;
        private final int dedupedCount;
        private final int skippedCount;

        public DayReminderRunSummary(LocalDate runDate,
                                     int processedControlsCount,
                                     int sentCount,
                                     int dedupedCount,
                                     int skippedCount) {
            this.runDate = runDate;
            this.processedControlsCount = processedControlsCount;
            this.sentCount = sentCount;
            this.dedupedCount = dedupedCount;
            this.skippedCount = skippedCount;
        }
    }
}
