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
class AnnualSemiDay5Day25ReminderServiceTest {

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
    private AnnualSemiDay5Day25ReminderService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        service = new AnnualSemiDay5Day25ReminderService(
                controlRepository,
                notificationRepository,
                userRepository,
                controlDetailsService,
                notificationTemplateService,
                clock
        );
    }

    @Test
    void day5WithFacilitatorResponseUsesSubmitText() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = today.minusDays(5);
        ReminderControlProjection row = projectionFor(
                101L,
                "CTRL-101",
                "Annual",
                "IN_PROGRESS",
                operationDate,
                LocalDate.of(2026, 2, 20),
                "facilitator@kpmg.kz",
                null,
                null,
                null
        );

        when(controlRepository.findAnnualSemiDay5Day25Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(101L),
                eq(AnnualSemiDay5Day25ReminderService.TYPE_DAY5),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(101L)).thenReturn(detailsWithSteps("done"));
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/101");
        when(userRepository.findByMail("facilitator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(1L, "facilitator@kpmg.kz")));

        service.runDailyReminders();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AnnualSemiDay5Day25ReminderService.TYPE_DAY5);
        assertThat(saved.getTitle()).isEqualTo("Control reminder: CTRL-101");
        Control control = new Control();
        control.setControlId("CTRL-101");
        assertThat(saved.getMessage())
                .isEqualTo(ControlNotificationText.reminder1Body(
                        control,
                        LocalDate.of(2026, 2, 20),
                        "/view-control/101"));
    }

    @Test
    void day25WithoutResponseUsesCloseText() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = today.minusDays(25);
        ReminderControlProjection row = projectionFor(
                102L,
                "CTRL-102",
                "Semi Annual",
                "REVIEW",
                operationDate,
                LocalDate.of(2026, 2, 22),
                null,
                "operator@kpmg.kz",
                null,
                null
        );

        when(controlRepository.findAnnualSemiDay5Day25Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(102L),
                eq(AnnualSemiDay5Day25ReminderService.TYPE_DAY25),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(102L)).thenReturn(detailsWithSteps(null));
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/102");
        when(userRepository.findByMail("operator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(2L, "operator@kpmg.kz")));

        service.runDailyReminders();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(AnnualSemiDay5Day25ReminderService.TYPE_DAY25);
        Control control = new Control();
        control.setControlId("CTRL-102");
        assertThat(saved.getMessage())
                .isEqualTo(ControlNotificationText.reminder2Body(
                        control,
                        LocalDate.of(2026, 2, 22),
                        "/view-control/102"));
    }

    @Test
    void dedupeSkipsWhenAlreadySentToday() {
        LocalDate today = LocalDate.now(clock);
        LocalDate operationDate = today.minusDays(5);
        ReminderControlProjection row = projectionFor(
                103L,
                null,
                "Annual",
                "SOQM_HEAD_REVIEW",
                operationDate,
                null,
                null,
                null,
                null,
                null
        );

        when(controlRepository.findAnnualSemiDay5Day25Candidates()).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(103L),
                eq(AnnualSemiDay5Day25ReminderService.TYPE_DAY5),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(true);

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

