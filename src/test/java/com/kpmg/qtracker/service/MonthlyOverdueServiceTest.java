package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyOverdueServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationTemplateService notificationTemplateService;

    private WorkingDaysService workingDaysService;
    private Clock clock;
    private MonthlyNotificationService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        workingDaysService = new WorkingDaysService();
        service = new MonthlyNotificationService(
                controlRepository,
                notificationRepository,
                userRepository,
                notificationTemplateService,
                workingDaysService,
                clock
        );
    }

    @Test
    void overdueDate1SendsOnlyOverdue1() {
        LocalDate today = LocalDate.now(clock);
        LocalDate deadlineDate = today.minusDays(2);
        ReminderControlProjection row = projectionFor(
                21L,
                "CTRL-21",
                "Monthly",
                "IN_PROGRESS",
                deadlineDate,
                "facilitator@kpmg.kz",
                null,
                null,
                null
        );

        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findMonthlyOverdueCandidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                21L,
                MonthlyNotificationService.TYPE_OVERDUE_1,
                dayStart,
                nextDayStart)).thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/21");
        when(userRepository.findByMail("facilitator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(1L, "facilitator@kpmg.kz")));

        service.runDailyOverdues();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        List<Notification> notifications = captor.getAllValues();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getType()).isEqualTo(MonthlyNotificationService.TYPE_OVERDUE_1);
    }

    @Test
    void repeatOnWorkingDay3SendsEvenIfOverdue1Missing() {
        LocalDate today = LocalDate.now(clock);
        LocalDate overdueDate1 = today.minusDays(2);
        LocalDate deadlineDate = overdueDate1.minusDays(2);
        int workingDayIndex = workingDaysService.workingDaysBetween(overdueDate1.minusDays(1), today);
        assertThat(workingDayIndex).isEqualTo(3);

        ReminderControlProjection row = projectionFor(
                22L,
                "CTRL-22",
                "Monthly",
                "REVIEW",
                deadlineDate,
                null,
                "operator@kpmg.kz",
                null,
                null
        );

        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findMonthlyOverdueCandidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                22L,
                MonthlyNotificationService.TYPE_OVERDUE_REPEAT,
                dayStart,
                nextDayStart)).thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/22");
        when(userRepository.findByMail("operator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(2L, "operator@kpmg.kz")));

        service.runDailyOverdues();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(MonthlyNotificationService.TYPE_OVERDUE_REPEAT);
        assertThat(saved.getUserId()).isEqualTo(2L);
        verify(notificationRepository, never()).existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(22L),
                eq(MonthlyNotificationService.TYPE_OVERDUE_1),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
    }

    @Test
    void dedupeSkipsRepeatWhenAlreadySentToday() {
        LocalDate today = LocalDate.now(clock);
        LocalDate deadlineDate = today.minusDays(4);
        ReminderControlProjection row = projectionFor(
                23L,
                "CTRL-23",
                "Monthly",
                "SOQM_HEAD_REVIEW",
                deadlineDate,
                null,
                null,
                "soqm@kpmg.kz",
                null
        );

        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();

        when(controlRepository.findMonthlyOverdueCandidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                23L,
                MonthlyNotificationService.TYPE_OVERDUE_REPEAT,
                dayStart,
                nextDayStart)).thenReturn(true);

        service.runDailyOverdues();

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    private ReminderControlProjection projectionFor(Long id,
                                                    String name,
                                                    String frequency,
                                                    String status,
                                                    LocalDate deadlineDate,
                                                    String facilitator,
                                                    String operator,
                                                    String soqm,
                                                    String owner) {
        ReminderControlProjection projection = org.mockito.Mockito.mock(ReminderControlProjection.class);
        when(projection.getControlId()).thenReturn(id);
        if (name != null) {
            when(projection.getControlName()).thenReturn(name);
        }
        when(projection.getFrequency()).thenReturn(frequency);
        when(projection.getStatus()).thenReturn(status);
        when(projection.getDeadlineDate()).thenReturn(deadlineDate);
        if (facilitator != null) {
            when(projection.getFacilitator()).thenReturn(facilitator);
        }
        if (operator != null) {
            when(projection.getControlOperator()).thenReturn(operator);
        }
        if (soqm != null) {
            when(projection.getSoqmLead()).thenReturn(soqm);
        }
        if (owner != null) {
            when(projection.getProcessOwner()).thenReturn(owner);
        }
        return projection;
    }

    private User userWithId(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setMail(email);
        user.setDisplayName("User " + id);
        return user;
    }
}



