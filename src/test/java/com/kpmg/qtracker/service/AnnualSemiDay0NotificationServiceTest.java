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
class AnnualSemiDay0NotificationServiceTest {

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

    private Clock clock;
    private AnnualNotificationService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        service = new AnnualNotificationService(
                controlRepository,
                assignmentRepository,
                notificationRepository,
                userRepository,
                notificationTemplateService,
                clock
        );
    }

    @Test
    void maybeSendImmediateDay0_isDisabled() {
        boolean sent = service.maybeSendImmediateDay0(91L);

        assertThat(sent).isFalse();
        verify(notificationRepository, times(0)).save(any(Notification.class));
    }

    @Test
    void day0SendsOnlyToFacilitatorAndOperator_whenStatusInProgress() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        ReminderControlProjection row = projectionFor(
                92L,
                "CTRL-92",
                "Annual",
                "IN_PROGRESS",
                today,
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                "owner@kpmg.kz"
        );

        when(controlRepository.findAnnualSemiDay0Candidates(today)).thenReturn(List.of(row));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(92L),
                eq(AnnualNotificationService.NOTIFICATION_TYPE),
                eq(dayStart),
                eq(nextDayStart))).thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/92");
        when(userRepository.findByMail("facilitator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(1L, "facilitator@kpmg.kz")));
        when(userRepository.findByMail("operator@kpmg.kz"))
                .thenReturn(Optional.of(userWithId(2L, "operator@kpmg.kz")));

        AnnualNotificationService.Day0RunSummary summary = service.runDailyDay0Notifications();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Notification::getUserId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(summary.getSentCount()).isEqualTo(2);
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
    }

    @Test
    void day0SkipsStatusesOutsideInProgressAndReview() {
        LocalDate today = LocalDate.now(clock);
        ReminderControlProjection row = projectionFor(
                93L,
                "CTRL-93",
                "Annual",
                "PROCESS_OWNER_REVIEW",
                today,
                "facilitator@kpmg.kz",
                "operator@kpmg.kz",
                "owner@kpmg.kz"
        );

        when(controlRepository.findAnnualSemiDay0Candidates(today)).thenReturn(List.of(row));

        AnnualNotificationService.Day0RunSummary summary = service.runDailyDay0Notifications();

        assertThat(summary.getSentCount()).isZero();
        verify(notificationRepository, never()).save(any(Notification.class));
        verify(userRepository, never()).findByMail("owner@kpmg.kz");
    }

    private ReminderControlProjection projectionFor(Long id,
                                                    String name,
                                                    String frequency,
                                                    String status,
                                                    LocalDate operationDate,
                                                    String facilitator,
                                                    String operator,
                                                    String owner) {
        ReminderControlProjection projection = org.mockito.Mockito.mock(ReminderControlProjection.class);
        when(projection.getControlId()).thenReturn(id);
        when(projection.getControlName()).thenReturn(name);
        when(projection.getFrequency()).thenReturn(frequency);
        when(projection.getStatus()).thenReturn(status);
        when(projection.getOperationDate()).thenReturn(operationDate);
        when(projection.getFacilitator()).thenReturn(facilitator);
        when(projection.getControlOperator()).thenReturn(operator);
        when(projection.getProcessOwner()).thenReturn(owner);
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



