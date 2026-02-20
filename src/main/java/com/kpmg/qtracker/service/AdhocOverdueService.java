package com.kpmg.qtracker.service;

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
public class AdhocOverdueService {

    static final String TYPE_OVERDUE_1 = "ADHOC_OVERDUE1";
    static final String TYPE_OVERDUE_REPEAT = "ADHOC_OVERDUE_REPEAT";

    private static final String SUBJECT_PREFIX = "Control overdue: ";

    private final ControlRepository controlRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationTemplateService notificationTemplateService;
    private final Clock clock;

    @Transactional
    public OverdueRunSummary runDailyOverdues() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        LocalDateTime runAt = LocalDateTime.now(clock);

        List<ReminderControlProjection> rows = controlRepository.findAdhocOverdueCandidates();
        int processed = 0;
        int sent = 0;
        int deduped = 0;
        int skipped = 0;

        for (ReminderControlProjection row : rows) {
            processed++;
            if (row == null) {
                skipped++;
                continue;
            }
            if (!isAdhoc(row.getFrequency())) {
                skipped++;
                continue;
            }
            if (isExcludedStatus(row.getStatus())) {
                skipped++;
                continue;
            }
            LocalDate deadlineDate = row.getDeadlineDate();
            if (deadlineDate == null) {
                skipped++;
                continue;
            }
            Long controlId = row.getControlId();
            if (controlId == null) {
                skipped++;
                continue;
            }

            LocalDate overdueDate1 = deadlineDate.plusDays(2);
            if (today.isBefore(overdueDate1)) {
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
            String controlName = buildControlName(control);

            if (today.equals(overdueDate1)) {
                if (isNotDeduped(controlId, TYPE_OVERDUE_1, dayStart, nextDayStart)) {
                    sent += sendNotifications(control, controlName, deadlineDate, recipients,
                            TYPE_OVERDUE_1, runAt);
                } else {
                    deduped++;
                }
            }

            if (isRepeatDue(today, overdueDate1)) {
                if (isNotDeduped(controlId, TYPE_OVERDUE_REPEAT, dayStart, nextDayStart)) {
                    sent += sendNotifications(control, controlName, deadlineDate, recipients,
                            TYPE_OVERDUE_REPEAT, runAt);
                } else {
                    deduped++;
                }
            }
        }

        return new OverdueRunSummary(today, processed, sent, deduped, skipped);
    }

    private boolean isRepeatDue(LocalDate today, LocalDate overdueDate1) {
        if (!today.isAfter(overdueDate1)) {
            return false;
        }
        int daysAfter = (int) (today.toEpochDay() - overdueDate1.toEpochDay());
        if (daysAfter > 6) {
            return false;
        }
        return daysAfter == 2 || daysAfter == 4 || daysAfter == 6;
    }

    private boolean isNotDeduped(Long controlId,
                                 String type,
                                 LocalDateTime dayStart,
                                 LocalDateTime nextDayStart) {
        return !notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                controlId,
                type,
                dayStart,
                nextDayStart
        );
    }

    private int sendNotifications(Control control,
                                  String controlName,
                                  LocalDate deadlineDate,
                                  List<String> recipients,
                                  String type,
                                  LocalDateTime runAt) {
        String subject = SUBJECT_PREFIX + controlName;
        String link = notificationTemplateService.buildControlLink(control);
        String body = ControlNotificationText.overdueBody(control, deadlineDate, link);
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

    private boolean isAdhoc(String frequency) {
        if (frequency == null) {
            return false;
        }
        String normalized = frequency.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("ad-hoc") || normalized.equals("ad hoc");
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

    enum Role {
        FACILITATOR,
        CONTROL_OPERATOR,
        SOQM_LEAD,
        PROCESS_OWNER
    }

    @Getter
    public static class OverdueRunSummary {
        private final LocalDate runDate;
        private final int processedControlsCount;
        private final int sentCount;
        private final int dedupedCount;
        private final int skippedCount;

        public OverdueRunSummary(LocalDate runDate,
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
