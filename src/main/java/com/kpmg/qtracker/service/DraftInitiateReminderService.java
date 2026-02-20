package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DraftInitiateReminderService {

    static final String STATUS_DRAFT = "DRAFT";
    static final String NOTIFICATION_TYPE = "DRAFT_INITIATE_REMINDER";
    static final String NOTIFICATION_TITLE = "Draft Initiate Reminder";
    static final String NOTIFICATION_MESSAGE = "The control is in status Draft.\n"
            + "Please fill all role fields and click Initiate to start the workflow.";

    private final ControlRepository controlRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationTemplateService notificationTemplateService;
    private final Clock clock;

    @Transactional
    public DraftReminderRunSummary runDraftInitiateRemindersWithSummary() {
        List<Control> controls = loadDraftControls();
        LocalDateTime runAt = LocalDateTime.now(clock);
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        int processed = 0;
        int sent = 0;
        int deduped = 0;
        int skipped = 0;

        for (Control control : controls) {
            processed++;
            if (!isDraftStatus(control.getPerformanceStatus())) {
                skipped++;
                continue;
            }
            User creator = control.getCreatedBy();
            if (creator == null || creator.getId() == null || control.getId() == null) {
                skipped++;
                continue;
            }
            boolean recentExists = notificationRepository
                    .existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                            control.getId(),
                            creator.getId(),
                            NOTIFICATION_TYPE,
                            dayStart,
                            nextDayStart
                    );
            if (recentExists) {
                deduped++;
                continue;
            }
            Notification notification = new Notification();
            notification.setUserId(creator.getId());
            notification.setControlId(control.getId());
            notification.setType(NOTIFICATION_TYPE);
            notification.setTitle(NOTIFICATION_TITLE);
            notification.setMessage(NOTIFICATION_MESSAGE);
            notification.setLink(notificationTemplateService.buildControlLink(control));
            notification.setIsRead(false);
            notification.setCreatedAt(runAt);
            notificationRepository.save(notification);
            sent++;
        }

        return new DraftReminderRunSummary(runAt, processed, sent, deduped, skipped);
    }

    private List<Control> loadDraftControls() {
        List<Control> primary = safeList(controlRepository.findByPerformanceStatusIgnoreCase(STATUS_DRAFT));
        List<Control> fallback = safeList(controlRepository.findByControlStatusIgnoreCase(STATUS_DRAFT));
        if (primary.isEmpty()) {
            return fallback;
        }
        if (fallback.isEmpty()) {
            return primary;
        }
        java.util.Map<Long, Control> merged = new java.util.LinkedHashMap<>();
        primary.forEach(control -> merged.put(control.getId(), control));
        fallback.forEach(control -> merged.putIfAbsent(control.getId(), control));
        return new java.util.ArrayList<>(merged.values());
    }

    private boolean isDraftStatus(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().replace(' ', '_').toUpperCase(java.util.Locale.ROOT);
        return STATUS_DRAFT.equals(normalized);
    }

    private List<Control> safeList(List<Control> controls) {
        return controls == null ? java.util.List.of() : controls;
    }

    @Getter
    public static class DraftReminderRunSummary {
        private final LocalDateTime runAt;
        private final int processedControlsCount;
        private final int sentCount;
        private final int dedupedCount;
        private final int skippedCount;

        public DraftReminderRunSummary(LocalDateTime runAt,
                                       int processedControlsCount,
                                       int sentCount,
                                       int dedupedCount,
                                       int skippedCount) {
            this.runAt = runAt;
            this.processedControlsCount = processedControlsCount;
            this.sentCount = sentCount;
            this.dedupedCount = dedupedCount;
            this.skippedCount = skippedCount;
        }
    }
}
