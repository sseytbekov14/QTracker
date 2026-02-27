package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import com.kpmg.qtracker.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyDay0NotificationServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ControlAssignmentRepository assignmentRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationTemplateService notificationTemplateService;

    private MonthlyNotificationService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        service = new MonthlyNotificationService(
                controlRepository,
                assignmentRepository,
                notificationRepository,
                userRepository,
                notificationTemplateService,
                clock
        );
    }

    @Test
    void runDailySkipsSecondRunSameDay() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = today;
        LocalDate deadlineDate = LocalDate.of(2026, 2, 10);
        ReminderControlProjection row = projectionFor(1L, "CTRL-1", "Monthly", "IN_PROGRESS",
                operationDate, deadlineDate, "facilitator@kpmg.kz", "operator@kpmg.kz");

        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findMonthlyDay0Candidates(today)).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                1L,
                MonthlyNotificationService.NOTIFICATION_TYPE,
                dayStart,
                nextDayStart)).thenReturn(false, true);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/1");
        when(userRepository.findByMail("facilitator@kpmg.kz")).thenReturn(Optional.of(userWithId(10L)));
        when(userRepository.findByMail("operator@kpmg.kz")).thenReturn(Optional.of(userWithId(11L)));

        MonthlyNotificationService.Day0RunSummary first = service.runDailyDay0Notifications();
        MonthlyNotificationService.Day0RunSummary second = service.runDailyDay0Notifications();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        Control expectedControl = new Control();
        expectedControl.setControlId("CTRL-1");
        expectedControl.setControlFrequency("Monthly");
        expectedControl.setControlStatus("IN_PROGRESS");
        assertThat(saved).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(MonthlyNotificationService.NOTIFICATION_TYPE);
            assertThat(notification.getTitle()).isEqualTo(ControlNotificationText.dayReminderSubject(0, expectedControl));
            assertThat(notification.getMessage())
                    .isEqualTo(ControlNotificationText.dayReminderBody(0, expectedControl, operationDate, "/view-control/1"));
        });

        assertThat(first.getSentCount()).isEqualTo(2);
        assertThat(second.getSentCount()).isEqualTo(0);
        assertThat(second.getDedupedCount()).isEqualTo(1);
    }

    @Test
    void runDailySendsAgainNextDay() {
        LocalDate operationDate = LocalDate.now(clock);
        ReminderControlProjection row = projectionFor(2L, "CTRL-2", "Monthly", "IN_PROGRESS",
                operationDate, LocalDate.of(2026, 2, 11), "facilitator@kpmg.kz", "operator@kpmg.kz");

        when(controlRepository.findMonthlyDay0Candidates(any(LocalDate.class))).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(2L),
                eq(MonthlyNotificationService.NOTIFICATION_TYPE),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false, false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/2");
        when(userRepository.findByMail("facilitator@kpmg.kz")).thenReturn(Optional.of(userWithId(20L)));
        when(userRepository.findByMail("operator@kpmg.kz")).thenReturn(Optional.of(userWithId(21L)));

        List<Notification> saved = new ArrayList<>();
        doAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            saved.add(notification);
            return notification;
        }).when(notificationRepository).save(any(Notification.class));

        service.runDailyDay0Notifications();

        Clock nextDayClock = Clock.fixed(Instant.parse("2026-02-07T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        MonthlyNotificationService nextDayService = new MonthlyNotificationService(
                controlRepository,
                assignmentRepository,
                notificationRepository,
                userRepository,
                notificationTemplateService,
                nextDayClock
        );
        nextDayService.runDailyDay0Notifications();

        assertThat(saved).hasSize(4);
    }

    @Test
    void maybeSendImmediateDay0_isDisabled() {
        boolean sent = service.maybeSendImmediateDay0(3L);

        assertThat(sent).isFalse();
        verify(notificationRepository, times(0)).save(any(Notification.class));
    }

    @Test
    void runDailySendsForReviewStatus() {
        LocalDate today = LocalDate.now(clock);
        ReminderControlProjection row = projectionFor(
                4L,
                "CTRL-4",
                "Monthly",
                "REVIEW",
                today,
                LocalDate.of(2026, 2, 15),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz");

        when(controlRepository.findMonthlyDay0Candidates(today)).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(4L),
                eq(MonthlyNotificationService.NOTIFICATION_TYPE),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/4");
        when(userRepository.findByMail("facilitator@kpmg.kz")).thenReturn(Optional.of(userWithId(40L)));
        when(userRepository.findByMail("operator@kpmg.kz")).thenReturn(Optional.of(userWithId(41L)));

        MonthlyNotificationService.Day0RunSummary summary = service.runDailyDay0Notifications();

        assertThat(summary.getSentCount()).isEqualTo(2);
        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    private ReminderControlProjection projectionFor(Long id,
                                                    String name,
                                                    String frequency,
                                                    String status,
                                                    LocalDate operationDate,
                                                    LocalDate deadlineDate,
                                                    String facilitator,
                                                    String operator) {
        ReminderControlProjection projection = org.mockito.Mockito.mock(ReminderControlProjection.class);
        when(projection.getControlId()).thenReturn(id);
        when(projection.getControlName()).thenReturn(name);
        when(projection.getFrequency()).thenReturn(frequency);
        when(projection.getStatus()).thenReturn(status);
        when(projection.getOperationDate()).thenReturn(operationDate);
        when(projection.getFacilitator()).thenReturn(facilitator);
        when(projection.getControlOperator()).thenReturn(operator);
        return projection;
    }

    private User userWithId(Long id) {
        User user = new User();
        user.setId(id);
        user.setMail("user" + id + "@example.test");
        user.setDisplayName("User " + id);
        return user;
    }
}



