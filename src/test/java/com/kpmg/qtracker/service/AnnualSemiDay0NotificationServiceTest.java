package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    private AnnualSemiDay0NotificationService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-06T04:00:00Z"), ZoneId.of("Asia/Almaty"));
        service = new AnnualSemiDay0NotificationService(
                controlRepository,
                assignmentRepository,
                notificationRepository,
                userRepository,
                notificationTemplateService,
                clock
        );
    }

    @Test
    void immediateSendAfter0930() {
        ZoneId zone = ZoneId.of("Asia/Almaty");
        LocalDateTime now = LocalDateTime.of(2026, 2, 6, 10, 5);
        Clock immediateClock = Clock.fixed(now.atZone(zone).toInstant(), zone);
        AnnualSemiDay0NotificationService immediateService = new AnnualSemiDay0NotificationService(
                controlRepository,
                assignmentRepository,
                notificationRepository,
                userRepository,
                notificationTemplateService,
                immediateClock
        );

        Control control = new Control();
        control.setId(91L);
        control.setControlId("CTRL-91");
        control.setControlFrequency("Annual");
        control.setControlStatus("Not Started");
        User creator = new User();
        creator.setId(99L);
        control.setCreatedBy(creator);
        control.setCreatedAt(LocalDateTime.of(2026, 2, 6, 9, 45));

        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(91L);
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 6));
        assignment.setFacilitator("facilitator@kpmg.kz");
        assignment.setControlOperator("operator@kpmg.kz");
        assignment.setProcessOwner("owner@kpmg.kz");

        when(controlRepository.findById(91L)).thenReturn(Optional.of(control));
        when(assignmentRepository.findByControlId(91L)).thenReturn(Optional.of(assignment));
        when(notificationRepository.existsByControlIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(91L),
                eq(AnnualSemiDay0NotificationService.NOTIFICATION_TYPE),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(false);
        when(notificationTemplateService.buildControlLink(any(Control.class))).thenReturn("/view-control/91");
        when(userRepository.findByMail("facilitator@kpmg.kz")).thenReturn(Optional.of(userWithId(30L)));
        when(userRepository.findByMail("operator@kpmg.kz")).thenReturn(Optional.of(userWithId(31L)));
        when(userRepository.findByMail("owner@kpmg.kz")).thenReturn(Optional.of(userWithId(32L)));

        boolean sent = immediateService.maybeSendImmediateDay0(91L);

        assertThat(sent).isTrue();
        verify(notificationRepository, times(3)).save(any(Notification.class));
    }

    private User userWithId(Long id) {
        User user = new User();
        user.setId(id);
        user.setMail("user" + id + "@example.test");
        user.setDisplayName("User " + id);
        return user;
    }
}

