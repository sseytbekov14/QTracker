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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlyDay3Day6ReminderServiceTest {

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
    void day3SendsToFacilitatorAndOperatorOnly_forInProgress() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -3);
        ReminderControlProjection row = projectionFor(
                10L,
                "CTRL-10",
                "Monthly",
                "IN_PROGRESS",
                operationDate,
                LocalDate.of(2026, 2, 20),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                null,
                "owner@kpmg.kz"
        );

        when(controlRepository.findMonthlyDay3Day6Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndType(10L, MonthlyNotificationService.TYPE_DAY3))
                .thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/10");
        when(userRepository.findByMail("facilitator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(1L, "facilitator@kpmg.kz")));
        when(userRepository.findByMail("operator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(2L, "operator@kpmg.kz")));

        service.runDailyReminders();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        assertThat(saved).extracting(Notification::getUserId).containsExactlyInAnyOrder(1L, 2L);
        Control control = new Control();
        control.setControlId("CTRL-10");
        control.setControlFrequency("Monthly");
        control.setControlStatus("IN_PROGRESS");
        assertThat(saved).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(MonthlyNotificationService.TYPE_DAY3);
            assertThat(notification.getTitle()).isEqualTo(ControlNotificationText.dayReminderSubject(3, control));
        });
        assertThat(saved).extracting(Notification::getMessage).containsOnly(
                ControlNotificationText.dayReminderBody(3, control, operationDate, "/view-control/10")
        );
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
    }

    @Test
    void day6SendsToFacilitatorAndOperatorOnly_forReview() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -6);
        ReminderControlProjection row = projectionFor(
                11L,
                "CTRL-11",
                "Monthly",
                "REVIEW",
                operationDate,
                LocalDate.of(2026, 2, 22),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                null,
                "owner@kpmg.kz"
        );

        when(controlRepository.findMonthlyDay3Day6Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndType(11L, MonthlyNotificationService.TYPE_DAY6))
                .thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/11");
        when(userRepository.findByMail("facilitator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(3L, "facilitator@kpmg.kz")));
        when(userRepository.findByMail("operator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(4L, "operator@kpmg.kz")));

        service.runDailyReminders();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        assertThat(saved).extracting(Notification::getUserId).containsExactlyInAnyOrder(3L, 4L);
        assertThat(saved).allSatisfy(notification ->
                assertThat(notification.getType()).isEqualTo(MonthlyNotificationService.TYPE_DAY6));
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
    }

    @Test
    void skipsStatusesOutsideInProgressAndReview() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -3);
        ReminderControlProjection row = projectionFor(
                13L,
                "CTRL-13",
                "Monthly",
                "PROCESS_OWNER_REVIEW",
                operationDate,
                LocalDate.of(2026, 2, 25),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                null,
                "owner@kpmg.kz"
        );

        when(controlRepository.findMonthlyDay3Day6Candidates()).thenReturn(List.of(row));

        service.runDailyReminders();

        verify(notificationRepository, never()).save(any(Notification.class));
        verify(userRepository, never()).findByMail(eq("owner@kpmg.kz"));
    }

    @Test
    void dedupeSkipsWhenAlreadySent() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -3);
        ReminderControlProjection row = projectionFor(
                12L,
                null,
                "Monthly",
                "REVIEW",
                operationDate,
                null,
                null,
                null,
                null,
                null
        );

        when(controlRepository.findMonthlyDay3Day6Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndType(12L, MonthlyNotificationService.TYPE_DAY3))
                .thenReturn(true);

        service.runDailyReminders();

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    private ReminderControlProjection projectionFor(Long id,
                                                    String name,
                                                    String frequency,
                                                    String status,
                                                    LocalDate operationDate,
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
        when(projection.getOperationDate()).thenReturn(operationDate);
        if (deadlineDate != null) {
            when(projection.getDeadlineDate()).thenReturn(deadlineDate);
        }
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



