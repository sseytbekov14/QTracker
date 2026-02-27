package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlDetailsDTO;
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
class AdhocDay5Day12ReminderServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ControlDetailsService controlDetailsService;

    @Mock
    private NotificationTemplateService notificationTemplateService;

    private Clock clock;
    private WorkingDaysService workingDaysService;
    private AdhocNotificationService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        workingDaysService = new WorkingDaysService();
        service = new AdhocNotificationService(
                controlRepository,
                notificationRepository,
                userRepository,
                controlDetailsService,
                notificationTemplateService,
                workingDaysService,
                clock
        );
    }

    @Test
    void day5WithFacilitatorResponseSendsToFacilitatorAndOperatorOnly() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -5);
        ReminderControlProjection row = projectionFor(
                71L,
                "CTRL-71",
                "Ad-hoc",
                "IN_PROGRESS",
                operationDate,
                LocalDate.of(2026, 2, 20),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                null,
                "owner@kpmg.kz"
        );

        when(controlRepository.findAdhocDay5Day12Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(71L),
                eq(AdhocNotificationService.TYPE_DAY5),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(71L)).thenReturn(detailsWithSteps("done"));
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/71");
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
        control.setControlId("CTRL-71");
        control.setControlFrequency("Ad-hoc");
        control.setControlStatus("IN_PROGRESS");
        assertThat(saved).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(AdhocNotificationService.TYPE_DAY5);
            assertThat(notification.getTitle()).isEqualTo(ControlNotificationText.dayReminderSubject(5, control));
        });
        assertThat(saved).extracting(Notification::getMessage).containsOnly(
                ControlNotificationText.dayReminderBody(5, control, operationDate, "/view-control/71")
        );
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
    }

    @Test
    void day12WithoutResponseSendsToFacilitatorAndOperatorOnly() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -12);
        ReminderControlProjection row = projectionFor(
                72L,
                "CTRL-72",
                "Ad-hoc",
                "REVIEW",
                operationDate,
                LocalDate.of(2026, 2, 22),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                null,
                "owner@kpmg.kz"
        );

        when(controlRepository.findAdhocDay5Day12Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(72L),
                eq(AdhocNotificationService.TYPE_DAY12),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(72L)).thenReturn(detailsWithSteps(null));
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/72");
        when(userRepository.findByMail("facilitator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(3L, "facilitator@kpmg.kz")));
        when(userRepository.findByMail("operator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(4L, "operator@kpmg.kz")));

        service.runDailyReminders();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        assertThat(saved).extracting(Notification::getUserId).containsExactlyInAnyOrder(3L, 4L);
        Control control = new Control();
        control.setControlId("CTRL-72");
        control.setControlFrequency("Ad-hoc");
        control.setControlStatus("REVIEW");
        assertThat(saved).allSatisfy(notification ->
                assertThat(notification.getType()).isEqualTo(AdhocNotificationService.TYPE_DAY12));
        assertThat(saved).extracting(Notification::getMessage).containsOnly(
                ControlNotificationText.dayReminderBody(12, control, operationDate, "/view-control/72")
        );
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
    }

    @Test
    void dedupeSkipsWhenAlreadySentToday() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -5);
        ReminderControlProjection row = projectionFor(
                73L,
                null,
                "Ad-hoc",
                "REVIEW",
                operationDate,
                null,
                null,
                null,
                null,
                null
        );

        when(controlRepository.findAdhocDay5Day12Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(73L),
                eq(AdhocNotificationService.TYPE_DAY5),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(true);

        service.runDailyReminders();

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void skipsStatusesOutsideInProgressAndReview() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = workingDaysService.addWorkingDays(today, -5);
        ReminderControlProjection row = projectionFor(
                74L,
                "CTRL-74",
                "Ad-hoc",
                "SOQM_HEAD_REVIEW",
                operationDate,
                LocalDate.of(2026, 2, 27),
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                "soqm@kpmg.kz",
                "owner@kpmg.kz"
        );

        when(controlRepository.findAdhocDay5Day12Candidates()).thenReturn(List.of(row));

        service.runDailyReminders();

        verify(notificationRepository, never()).save(any(Notification.class));
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
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

    private ControlDetailsDTO detailsWithSteps(String steps) {
        ControlDetailsDTO details = new ControlDetailsDTO();
        details.setControlStepsPerformed(steps);
        return details;
    }

    private User userWithId(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setMail(email);
        user.setDisplayName("User " + id);
        return user;
    }
}



