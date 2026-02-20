package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftInitiateReminderServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationTemplateService notificationTemplateService;

    private DraftInitiateReminderService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T10:00:00Z"), ZoneId.of("Asia/Almaty"));
        service = new DraftInitiateReminderService(
                controlRepository,
                notificationRepository,
                notificationTemplateService,
                clock
        );
    }

    @Test
    void inProgressControlSendsReminder() {
        Control control = new Control();
        control.setId(10L);
        control.setControlStatus("DRAFT");
        User creator = new User();
        creator.setId(99L);
        creator.setDisplayName("Creator");
        control.setCreatedBy(creator);

        LocalDateTime expectedRunAt = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime expectedDayStart = today.atStartOfDay();
        LocalDateTime expectedNextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findByControlStatusIgnoreCase("DRAFT")).thenReturn(List.of(control));
        when(notificationRepository.existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                10L,
                99L,
                DraftInitiateReminderService.NOTIFICATION_TYPE,
                expectedDayStart,
                expectedNextDayStart))
                .thenReturn(false);
        when(notificationTemplateService.buildControlLink(control)).thenReturn("/view-control/10");

        DraftInitiateReminderService.DraftReminderRunSummary summary =
                service.runDraftInitiateRemindersWithSummary();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(99L);
        assertThat(saved.getControlId()).isEqualTo(10L);
        assertThat(saved.getType()).isEqualTo(DraftInitiateReminderService.NOTIFICATION_TYPE);
        assertThat(saved.getTitle()).isEqualTo(DraftInitiateReminderService.NOTIFICATION_TITLE);
        assertThat(saved.getMessage()).isEqualTo(DraftInitiateReminderService.NOTIFICATION_MESSAGE);
        assertThat(saved.getLink()).isEqualTo("/view-control/10");
        assertThat(saved.getIsRead()).isFalse();
        assertThat(saved.getCreatedAt()).isEqualTo(expectedRunAt);

        assertThat(summary.getProcessedControlsCount()).isEqualTo(1);
        assertThat(summary.getSentCount()).isEqualTo(1);
        assertThat(summary.getDedupedCount()).isEqualTo(0);
        assertThat(summary.getSkippedCount()).isEqualTo(0);
    }

    @Test
    void statusChangedSkipsReminder() {
        Control control = new Control();
        control.setId(11L);
        control.setControlStatus("COMPLETED");
        User creator = new User();
        creator.setId(100L);
        creator.setDisplayName("Creator");
        control.setCreatedBy(creator);

        when(controlRepository.findByControlStatusIgnoreCase("DRAFT")).thenReturn(List.of(control));

        DraftInitiateReminderService.DraftReminderRunSummary summary =
                service.runDraftInitiateRemindersWithSummary();

        verify(notificationRepository, never()).save(org.mockito.ArgumentMatchers.any(Notification.class));
        verify(notificationRepository, never()).existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );

        assertThat(summary.getProcessedControlsCount()).isEqualTo(1);
        assertThat(summary.getSentCount()).isEqualTo(0);
        assertThat(summary.getDedupedCount()).isEqualTo(0);
        assertThat(summary.getSkippedCount()).isEqualTo(1);
    }

    @Test
    void alreadySentTodaySkipsReminder() {
        Control control = new Control();
        control.setId(12L);
        control.setControlStatus("DRAFT");
        User creator = new User();
        creator.setId(101L);
        creator.setDisplayName("Creator");
        control.setCreatedBy(creator);

        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findByControlStatusIgnoreCase("DRAFT")).thenReturn(List.of(control));
        when(notificationRepository.existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                12L,
                101L,
                DraftInitiateReminderService.NOTIFICATION_TYPE,
                dayStart,
                nextDayStart))
                .thenReturn(true);

        DraftInitiateReminderService.DraftReminderRunSummary summary =
                service.runDraftInitiateRemindersWithSummary();

        verify(notificationRepository, never()).save(org.mockito.ArgumentMatchers.any(Notification.class));

        assertThat(summary.getProcessedControlsCount()).isEqualTo(1);
        assertThat(summary.getSentCount()).isEqualTo(0);
        assertThat(summary.getDedupedCount()).isEqualTo(1);
        assertThat(summary.getSkippedCount()).isEqualTo(0);
    }

    @Test
    void secondRunSameDaySkipsReminder() {
        Control control = new Control();
        control.setId(20L);
        control.setControlStatus("DRAFT");
        User creator = new User();
        creator.setId(200L);
        creator.setDisplayName("Creator");
        control.setCreatedBy(creator);

        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findByControlStatusIgnoreCase("DRAFT")).thenReturn(List.of(control));
        when(notificationTemplateService.buildControlLink(control)).thenReturn("/view-control/20");
        when(notificationRepository.existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                20L,
                200L,
                DraftInitiateReminderService.NOTIFICATION_TYPE,
                dayStart,
                nextDayStart))
                .thenReturn(false, true);

        java.util.List<Notification> saved = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            saved.add(notification);
            return notification;
        }).when(notificationRepository).save(org.mockito.ArgumentMatchers.any(Notification.class));

        DraftInitiateReminderService.DraftReminderRunSummary firstSummary =
                service.runDraftInitiateRemindersWithSummary();
        DraftInitiateReminderService.DraftReminderRunSummary secondSummary =
                service.runDraftInitiateRemindersWithSummary();

        assertThat(saved).hasSize(1);
        assertThat(firstSummary.getSentCount()).isEqualTo(1);
        assertThat(secondSummary.getSentCount()).isEqualTo(0);
        assertThat(secondSummary.getDedupedCount()).isEqualTo(1);
    }

    @Test
    void nextDaySendsAgain() {
        Control control = new Control();
        control.setId(30L);
        control.setControlStatus("DRAFT");
        User creator = new User();
        creator.setId(300L);
        creator.setDisplayName("Creator");
        control.setCreatedBy(creator);

        when(controlRepository.findByControlStatusIgnoreCase("DRAFT")).thenReturn(List.of(control));
        when(notificationTemplateService.buildControlLink(control)).thenReturn("/view-control/30");

        java.util.List<Notification> saved = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            saved.add(notification);
            return notification;
        }).when(notificationRepository).save(org.mockito.ArgumentMatchers.any(Notification.class));

        org.mockito.Mockito.doAnswer(invocation -> {
            Long controlId = invocation.getArgument(0);
            Long userId = invocation.getArgument(1);
            String type = invocation.getArgument(2);
            LocalDateTime start = invocation.getArgument(3);
            LocalDateTime end = invocation.getArgument(4);
            return saved.stream().anyMatch(notification ->
                    controlId.equals(notification.getControlId())
                            && userId.equals(notification.getUserId())
                            && type.equals(notification.getType())
                            && notification.getCreatedAt() != null
                            && !notification.getCreatedAt().isBefore(start)
                            && notification.getCreatedAt().isBefore(end));
        }).when(notificationRepository)
                .existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                        org.mockito.ArgumentMatchers.any(LocalDateTime.class)
                );

        Clock dayOneClock = Clock.fixed(Instant.parse("2026-02-06T06:00:00Z"), ZoneId.of("Asia/Almaty"));
        DraftInitiateReminderService dayOneService = new DraftInitiateReminderService(
                controlRepository,
                notificationRepository,
                notificationTemplateService,
                dayOneClock
        );
        LocalDateTime expectedDayOneRunAt = LocalDateTime.now(dayOneClock);
        dayOneService.runDraftInitiateRemindersWithSummary();

        Clock dayTwoClock = Clock.fixed(Instant.parse("2026-02-07T06:00:00Z"), ZoneId.of("Asia/Almaty"));
        DraftInitiateReminderService dayTwoService = new DraftInitiateReminderService(
                controlRepository,
                notificationRepository,
                notificationTemplateService,
                dayTwoClock
        );
        LocalDateTime expectedDayTwoRunAt = LocalDateTime.now(dayTwoClock);
        dayTwoService.runDraftInitiateRemindersWithSummary();

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getCreatedAt()).isEqualTo(expectedDayOneRunAt);
        assertThat(saved.get(1).getCreatedAt()).isEqualTo(expectedDayTwoRunAt);
    }
}

