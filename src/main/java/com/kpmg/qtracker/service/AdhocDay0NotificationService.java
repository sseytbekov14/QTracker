package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdhocDay0NotificationService {

    static final String NOTIFICATION_TYPE = "ADHOC_DAY0";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final LocalTime DAY0_THRESHOLD = LocalTime.of(9, 30);

    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationTemplateService notificationTemplateService;
    private final Clock clock;
    @Value("${reminders.enabled:true}")
    private boolean remindersEnabled = true;

    @Transactional
    public Day0RunSummary runDailyDay0Notifications() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        List<ReminderControlProjection> rows = controlRepository.findAdhocDay0Candidates(today);
        int processed = 0;
        int sent = 0;
        int deduped = 0;
        int skipped = 0;
        LocalDateTime runAt = LocalDateTime.now(clock);

        for (ReminderControlProjection row : rows) {
            processed++;
            if (!isAdhoc(row.getFrequency())) {
                skipped++;
                continue;
            }
            if (isBlockedStatus(row.getStatus())) {
                skipped++;
                continue;
            }
            Long controlId = row.getControlId();
            if (controlId == null) {
                skipped++;
                continue;
            }
            if (wasSentToday(controlId, dayStart, nextDayStart)) {
                deduped++;
                continue;
            }
            Control control = buildControl(row);
            List<String> recipients = collectRecipients(
                    splitEmails(row.getFacilitator()),
                    splitEmails(row.getControlOperator()),
                    splitEmails(row.getProcessOwner())
            );
            if (recipients.isEmpty()) {
                skipped++;
                continue;
            }
            sent += sendNotifications(control, row.getDeadlineDate(), recipients, runAt);
        }

        return new Day0RunSummary(today, processed, sent, deduped, skipped);
    }

    @Transactional
    public boolean maybeSendImmediateDay0(Long controlId) {
        if (controlId == null) {
            return false;
        }
        Optional<Control> controlOpt = controlRepository.findById(controlId);
        if (controlOpt.isEmpty()) {
            return false;
        }
        Control control = controlOpt.get();
        if (!isAdhoc(control.getControlFrequency())) {
            return false;
        }
        if (isBlockedStatus(control.getPerformanceStatus())) {
            return false;
        }
        if (control.getCreatedBy() == null) {
            return false;
        }
        Optional<ControlAssignment> assignmentOpt = assignmentRepository.findByControlId(controlId);
        if (assignmentOpt.isEmpty()) {
            return false;
        }
        ControlAssignment assignment = assignmentOpt.get();
        LocalDate today = LocalDate.now(clock);
        if (assignment.getControlOperationDate() == null
                || !assignment.getControlOperationDate().equals(today)) {
            return false;
        }
        LocalDateTime createdAt = control.getCreatedAt();
        if (createdAt == null || !createdAt.toLocalDate().equals(today)) {
            return false;
        }
        LocalDateTime threshold = today.atTime(DAY0_THRESHOLD);
        if (createdAt.isBefore(threshold)) {
            return false;
        }
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        if (wasSentToday(controlId, dayStart, nextDayStart)) {
            return false;
        }
        List<String> recipients = collectRecipients(
                splitEmails(assignment.getFacilitator()),
                splitEmails(assignment.getControlOperator()),
                splitEmails(assignment.getProcessOwner())
        );
        if (recipients.isEmpty()) {
            return false;
        }
        LocalDateTime runAt = LocalDateTime.now(clock);
        sendNotifications(control, assignment.getControlOperationDeadline(), recipients, runAt);
        return true;
    }

    private boolean wasSentToday(Long controlId, LocalDateTime dayStart, LocalDateTime nextDayStart) {
        return notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                controlId,
                NOTIFICATION_TYPE,
                dayStart,
                nextDayStart
        );
    }

    private int sendNotifications(Control control,
                                  LocalDate deadlineDate,
                                  List<String> recipients,
                                  LocalDateTime runAt) {
        if (isDay0NotificationsDisabled()) {
            return 0;
        }
        String controlName = buildControlName(control);
        String subject = "Control opened: " + controlName;
        String body = ControlNotificationText.activationBody(control, deadlineDate);
        String link = notificationTemplateService.buildControlLink(control);
        int created = 0;
        for (String email : recipients) {
            if (email == null || email.isBlank()) {
                continue;
            }
            Optional<User> userOpt = userRepository.findByMail(email);
            if (userOpt.isEmpty()) {
                continue;
            }
            User user = userOpt.get();
            Notification notification = new Notification();
            notification.setUserId(user.getId());
            notification.setControlId(control.getId());
            notification.setType(NOTIFICATION_TYPE);
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

    private boolean isDay0NotificationsDisabled() {
        return !remindersEnabled;
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

    private boolean isAdhoc(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("ad-hoc") || normalized.equals("ad hoc");
    }

    private boolean isBlockedStatus(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        return STATUS_IN_PROGRESS.equals(normalized)
                || STATUS_COMPLETED.equals(normalized);
    }

    private List<String> collectRecipients(List<String> facilitators,
                                           List<String> operators,
                                           List<String> owners) {
        Set<String> unique = new LinkedHashSet<>();
        addRecipients(unique, facilitators);
        addRecipients(unique, operators);
        addRecipients(unique, owners);
        return new ArrayList<>(unique);
    }

    private void addRecipients(Set<String> target, List<String> recipients) {
        if (recipients == null) {
            return;
        }
        for (String recipient : recipients) {
            if (recipient == null || recipient.isBlank()) {
                continue;
            }
            target.add(recipient.trim());
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

    @Getter
    public static class Day0RunSummary {
        private final LocalDate runDate;
        private final int processedControlsCount;
        private final int sentCount;
        private final int dedupedCount;
        private final int skippedCount;

        public Day0RunSummary(LocalDate runDate,
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
