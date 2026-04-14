package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
public class RecurringNotificationService {

    static final String NOTIFICATION_TYPE = "RECURRING_DAY0";
    static final String TYPE_DAY5 = "RECURRING_DAY5";
    static final String TYPE_DAY12 = "RECURRING_DAY12";
    static final String TYPE_OVERDUE_1 = "RECURRING_OVERDUE1";
    static final String TYPE_OVERDUE_REPEAT = "RECURRING_OVERDUE_REPEAT";

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_REVIEW = "REVIEW";
    private static final String SUBJECT_PREFIX = "Control overdue: ";

    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ControlDetailsService controlDetailsService;
    private final NotificationTemplateService notificationTemplateService;
    private final WorkingDaysService workingDaysService;
    private final Clock clock;

    @Value("${reminders.enabled:true}")
    private boolean remindersEnabled = true;

    @Autowired
    public RecurringNotificationService(ControlRepository controlRepository,
                                        ControlAssignmentRepository assignmentRepository,
                                        NotificationRepository notificationRepository,
                                        UserRepository userRepository,
                                        ControlDetailsService controlDetailsService,
                                        NotificationTemplateService notificationTemplateService,
                                        WorkingDaysService workingDaysService,
                                        Clock clock) {
        this.controlRepository = controlRepository;
        this.assignmentRepository = assignmentRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.controlDetailsService = controlDetailsService;
        this.notificationTemplateService = notificationTemplateService;
        this.workingDaysService = workingDaysService;
        this.clock = clock;
    }

    // Backward-compatible constructor for existing unit tests.
    RecurringNotificationService(ControlRepository controlRepository,
                                 NotificationRepository notificationRepository,
                                 UserRepository userRepository,
                                 ControlDetailsService controlDetailsService,
                                 NotificationTemplateService notificationTemplateService,
                                 WorkingDaysService workingDaysService,
                                 Clock clock) {
        this(controlRepository,
                null,
                notificationRepository,
                userRepository,
                controlDetailsService,
                notificationTemplateService,
                workingDaysService,
                clock);
    }

    // Backward-compatible constructor for overdue tests.
    RecurringNotificationService(ControlRepository controlRepository,
                                 NotificationRepository notificationRepository,
                                 UserRepository userRepository,
                                 NotificationTemplateService notificationTemplateService,
                                 Clock clock) {
        this(controlRepository,
                null,
                notificationRepository,
                userRepository,
                null,
                notificationTemplateService,
                new WorkingDaysService(),
                clock);
    }

    @Transactional
    public Day0RunSummary runDailyDay0Notifications() {
        return sendDay0();
    }

    @Transactional
    public DayReminderRunSummary runDailyReminders() {
        DayReminderRunSummary day5 = sendDay5();
        DayReminderRunSummary day12 = sendDay12();
        return new DayReminderRunSummary(
                day5.getRunDate(),
                day5.getProcessedControlsCount() + day12.getProcessedControlsCount(),
                day5.getSentCount() + day12.getSentCount(),
                day5.getDedupedCount() + day12.getDedupedCount(),
                day5.getSkippedCount() + day12.getSkippedCount()
        );
    }

    @Transactional
    public OverdueRunSummary runDailyOverdues() {
        return sendOverdue();
    }

    @Transactional
    public Day0RunSummary sendDay0() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        List<ReminderControlProjection> rows = controlRepository.findRecurringDay0Candidates(today);
        int processed = 0;
        int sent = 0;
        int deduped = 0;
        int skipped = 0;
        LocalDateTime runAt = LocalDateTime.now(clock);

        for (ReminderControlProjection row : rows) {
            processed++;
            if (!isRecurring(row.getFrequency())) {
                skipped++;
                continue;
            }
            if (!isEligibleDay0Status(row.getStatus())) {
                skipped++;
                continue;
            }
            Long controlId = row.getControlId();
            if (controlId == null) {
                skipped++;
                continue;
            }
            if (notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    controlId,
                    NOTIFICATION_TYPE,
                    dayStart,
                    nextDayStart)) {
                deduped++;
                continue;
            }

            Control control = buildControl(row);
            List<String> recipients = collectRecipients(
                    splitEmails(row.getFacilitator()),
                    splitEmails(row.getControlOperator())
            );
            if (recipients.isEmpty()) {
                skipped++;
                continue;
            }
            sent += sendDay0Notifications(control, row.getOperationDate(), recipients, runAt);
        }

        return new Day0RunSummary(today, processed, sent, deduped, skipped);
    }

    @Transactional
    public boolean maybeSendImmediateDay0(Long controlId) {
        return false;
    }

    @Transactional
    public DayReminderRunSummary sendDay5() {
        return sendDayReminder(TYPE_DAY5, 5);
    }

    @Transactional
    public DayReminderRunSummary sendDay12() {
        return sendDayReminder(TYPE_DAY12, 12);
    }

    @Transactional
    public OverdueRunSummary sendOverdue() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        LocalDateTime runAt = LocalDateTime.now(clock);

        List<ReminderControlProjection> rows = controlRepository.findRecurringOverdueCandidates();
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
            if (!isRecurring(row.getFrequency())) {
                skipped++;
                continue;
            }
            if (isOverdueExcludedStatus(row.getStatus())) {
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

            Role role = resolveOverdueRole(row.getStatus());
            if (role == null) {
                skipped++;
                continue;
            }
            List<String> recipients = overdueRecipientsForRole(role, row);
            if (recipients.isEmpty()) {
                skipped++;
                continue;
            }

            Control control = buildControl(row);
            String controlName = buildControlName(control);

            if (today.equals(overdueDate1)) {
                if (isNotDeduped(controlId, TYPE_OVERDUE_1, dayStart, nextDayStart)) {
                    sent += sendOverdueNotifications(control, controlName, deadlineDate, recipients,
                            TYPE_OVERDUE_1, runAt);
                } else {
                    deduped++;
                }
            }

            if (isOverdueRepeatDue(today, overdueDate1)) {
                if (isNotDeduped(controlId, TYPE_OVERDUE_REPEAT, dayStart, nextDayStart)) {
                    sent += sendOverdueNotifications(control, controlName, deadlineDate, recipients,
                            TYPE_OVERDUE_REPEAT, runAt);
                } else {
                    deduped++;
                }
            }
        }

        return new OverdueRunSummary(today, processed, sent, deduped, skipped);
    }

    private DayReminderRunSummary sendDayReminder(String notificationType, int dayOffset) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        List<ReminderControlProjection> rows = controlRepository.findRecurringDay5Day12Candidates();
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
            if (!isRecurring(row.getFrequency())) {
                skipped++;
                continue;
            }
            if (isReminderExcludedStatus(row.getStatus())) {
                skipped++;
                continue;
            }
            LocalDate operationDate = row.getOperationDate();
            if (operationDate == null) {
                skipped++;
                continue;
            }
            if (!today.equals(workingDaysService.addWorkingDays(operationDate, dayOffset))) {
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
            List<String> recipients = collectRecipients(
                    splitEmails(row.getFacilitator()),
                    splitEmails(row.getControlOperator())
            );
            if (recipients.isEmpty()) {
                skipped++;
                continue;
            }
            boolean hasResponse = hasFacilitatorResponse(controlId);
            Control control = buildControl(row);
            sent += sendDayReminderNotifications(control, operationDate, recipients, notificationType, dayOffset, hasResponse, runAt);
        }

        return new DayReminderRunSummary(today, processed, sent, deduped, skipped);
    }

    private boolean hasFacilitatorResponse(Long controlId) {
        if (controlId == null) {
            return false;
        }
        ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(controlId);
        return details != null && details.getControlStepsPerformed() != null && !details.getControlStepsPerformed().trim().isEmpty();
    }

    private int sendDay0Notifications(Control control,
                                      LocalDate operationDate,
                                      List<String> recipients,
                                      LocalDateTime runAt) {
        if (!remindersEnabled) {
            return 0;
        }
        String link = notificationTemplateService.buildControlLink(control);
        String subject = ControlNotificationText.dayReminderSubject(0, control);
        String body = ControlNotificationText.dayReminderBody(0, control, operationDate, link);
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

    private int sendDayReminderNotifications(Control control,
                                             LocalDate operationDate,
                                             List<String> recipients,
                                             String type,
                                             int day,
                                             boolean hasResponse,
                                             LocalDateTime runAt) {
        String subject = ControlNotificationText.dayReminderSubject(day, control);
        String link = notificationTemplateService.buildControlLink(control);
        String body = ControlNotificationText.dayReminderBody(day, control, operationDate, link);
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

    private int sendOverdueNotifications(Control control,
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

    private boolean isOverdueRepeatDue(LocalDate today, LocalDate overdueDate1) {
        if (!today.isAfter(overdueDate1)) {
            return false;
        }
        int daysAfter = (int) (today.toEpochDay() - overdueDate1.toEpochDay());
        if (daysAfter > 6) {
            return false;
        }
        return daysAfter == 2 || daysAfter == 4 || daysAfter == 6;
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

    private boolean isRecurring(String value) {
        if (value == null) {
            return false;
        }
        return "recurring".equalsIgnoreCase(value.trim());
    }

    private boolean isEligibleDay0Status(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        return STATUS_IN_PROGRESS.equals(normalized) || STATUS_REVIEW.equals(normalized);
    }

    private boolean isReminderExcludedStatus(String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        String normalized = normalizeStatus(status);
        return !STATUS_IN_PROGRESS.equals(normalized) && !STATUS_REVIEW.equals(normalized);
    }

    private boolean isOverdueExcludedStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = normalizeStatus(status);
        return "DRAFT".equals(normalized) || "COMPLETED".equals(normalized);
    }

    private Role resolveOverdueRole(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = normalizeStatus(status);
        return switch (normalized) {
            case STATUS_IN_PROGRESS -> Role.FACILITATOR;
            case STATUS_REVIEW -> Role.CONTROL_OPERATOR;
            case "SOQM_HEAD_REVIEW" -> Role.SOQM_TEAM;
            case "PROCESS_OWNER_REVIEW" -> Role.PROCESS_OWNER;
            default -> null;
        };
    }

    private List<String> collectRecipients(List<String> facilitators,
                                           List<String> operators) {
        Set<String> unique = new LinkedHashSet<>();
        addRecipients(unique, facilitators);
        addRecipients(unique, operators);
        return new ArrayList<>(unique);
    }

    private List<String> overdueRecipientsForRole(Role role, ReminderControlProjection row) {
        Set<String> recipients = new LinkedHashSet<>();
        switch (role) {
            case FACILITATOR -> addRecipients(recipients, splitEmails(row.getFacilitator()));
            case CONTROL_OPERATOR -> addRecipients(recipients, splitEmails(row.getControlOperator()));
            case SOQM_TEAM -> addRecipients(recipients, splitEmails(row.getSoqmLead()));
            case PROCESS_OWNER -> addRecipients(recipients, splitEmails(row.getProcessOwner()));
        }
        return new ArrayList<>(recipients);
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

    private String normalizeStatus(String status) {
        return status.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    enum Role {
        FACILITATOR,
        CONTROL_OPERATOR,
        SOQM_TEAM,
        PROCESS_OWNER
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

