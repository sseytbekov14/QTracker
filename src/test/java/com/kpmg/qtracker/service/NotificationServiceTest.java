package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationTemplateService notificationTemplateService;

    @Mock
    private ObjectProvider<EmailNotificationChannel> emailChannelProvider;

    @Mock
    private EmailNotificationChannel emailChannel;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository,
                userRepository,
                notificationTemplateService,
                emailChannelProvider,
                new StatusDisplayMapper()
        );
    }

    @Test
    void sendTemplateNotifications_deduplicatesRecipients() {
        Control control = controlWithId(10L);
        User duplicateUser = userWithId(1L, "dup@example.test", "FACILITATOR");
        User otherUser = userWithId(2L, "other@example.test", "CONTROL_OPERATOR");

        when(userRepository.findByMail("dup@example.test")).thenReturn(Optional.of(duplicateUser));
        when(userRepository.findByMail("other@example.test")).thenReturn(Optional.of(otherUser));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(null);

        notificationService.sendTemplateNotifications(
                control,
                List.of("dup@example.test", "dup@example.test", "other@example.test", "dup@example.test"),
                NotificationTemplateService.TemplateType.COMPLETED_ALL,
                false
        );

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(userRepository, times(1)).findByMail("dup@example.test");
        verify(userRepository, times(1)).findByMail("other@example.test");
    }

    @Test
    void sendTemplateNotifications_samePersonInTwoFieldsInAnotherCase_isNotifiedOnce() {
        Control control = controlWithId(18L);
        User both = userWithId(3L, "both@example.test", "FACILITATOR");

        when(userRepository.findByMail("both@example.test")).thenReturn(Optional.of(both));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(null);

        // Completed goes to the Facilitator and the Operator; the Operator field spells the address in capitals
        notificationService.sendTemplateNotifications(
                control,
                List.of("both@example.test", "BOTH@Example.test "),
                NotificationTemplateService.TemplateType.COMPLETED_ALL,
                false
        );

        verify(notificationRepository, times(1)).save(any(Notification.class));
        verify(userRepository, times(1)).findByMail("both@example.test");
    }

    @Test
    void sendTemplateNotifications_whenEmailDisabled_doesNotInvokeEmailChannel() {
        Control control = controlWithId(11L);
        User userA = userWithId(3L, "usera@example.test", "FACILITATOR");
        User userB = userWithId(4L, "userb@example.test", "CONTROL_OPERATOR");

        when(userRepository.findByMail("usera@example.test")).thenReturn(Optional.of(userA));
        when(userRepository.findByMail("userb@example.test")).thenReturn(Optional.of(userB));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(null);

        notificationService.sendTemplateNotifications(
                control,
                List.of("usera@example.test", "userb@example.test"),
                NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR,
                false
        );

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(emailChannel, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void sendTemplateNotifications_whenEmailEnabled_invokesEmailChannelOncePerRecipient() {
        Control control = controlWithId(12L);
        User userA = userWithId(5L, "usera@example.test", "FACILITATOR");
        User userB = userWithId(6L, "userb@example.test", "CONTROL_OPERATOR");

        when(userRepository.findByMail("usera@example.test")).thenReturn(Optional.of(userA));
        when(userRepository.findByMail("userb@example.test")).thenReturn(Optional.of(userB));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(emailChannel);

        notificationService.sendTemplateNotifications(
                control,
                List.of("usera@example.test", "userb@example.test", "usera@example.test"),
                NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR,
                false
        );

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(emailChannel, times(1)).send("usera@example.test", "Subject", "Body");
        verify(emailChannel, times(1)).send("userb@example.test", "Subject", "Body");
    }

    @Test
    void sendTemplateNotifications_submitOverridesNotificationTitleOnly() {
        Control control = controlWithId(13L);
        User recipient = userWithId(7L, "submit@example.test", "CONTROL_OPERATOR");

        when(userRepository.findByMail("submit@example.test")).thenReturn(Optional.of(recipient));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(emailChannel);

        notificationService.sendTemplateNotifications(
                control,
                List.of("submit@example.test"),
                NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR,
                false
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertEquals("Control Submitted by Facilitator", saved.getTitle());
        verify(emailChannel).send(eq("submit@example.test"), eq("Subject"), eq("Body"));
    }

    @Test
    void sendTemplateNotifications_nonSubmitKeepsTemplateTitle() {
        Control control = controlWithId(14L);
        User recipient = userWithId(8L, "complete@example.test", "PROCESS_OWNER");

        when(userRepository.findByMail("complete@example.test")).thenReturn(Optional.of(recipient));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(null);

        notificationService.sendTemplateNotifications(
                control,
                List.of("complete@example.test"),
                NotificationTemplateService.TemplateType.COMPLETED_ALL,
                false
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertEquals("Subject", saved.getTitle());
    }

    @Test
    void sendInitiateNotifications_isIdempotent() {
        Control control = controlWithId(15L);
        User userA = userWithId(9L, "fac@example.test", "FACILITATOR");
        User userB = userWithId(10L, "op@example.test", "CONTROL_OPERATOR");

        when(notificationRepository.existsByControlIdAndType(control.getId(), "INITIATE"))
                .thenReturn(false, true);
        when(userRepository.findByMail("fac@example.test")).thenReturn(Optional.of(userA));
        when(userRepository.findByMail("op@example.test")).thenReturn(Optional.of(userB));
        stubTemplate();
        when(emailChannelProvider.getIfAvailable()).thenReturn(null);

        notificationService.sendInitiateNotifications(
                control,
                List.of("fac@example.test", "op@example.test")
        );
        notificationService.sendInitiateNotifications(
                control,
                List.of("fac@example.test", "op@example.test")
        );

        verify(notificationRepository, times(2)).save(any(Notification.class));
    }

    @Test
    void sendReturnNotifications_repeatedReturnWithinMinutes_isAnnouncedAgain() {
        Control control = controlWithId(16L);
        User operator = userWithId(11L, "op@example.test", "CONTROL_OPERATOR");

        when(userRepository.findByMail("op@example.test")).thenReturn(Optional.of(operator));
        when(notificationTemplateService.renderReturnNotification(
                any(Control.class), anyString(), anyString(), any(), eq("RETURN_TO_OPERATOR")))
                .thenReturn(new NotificationTemplateService.NotificationTemplate(
                        "Returned", "Body", "RETURN_TO_OPERATOR"));
        when(notificationTemplateService.buildControlLink(any(Control.class)))
                .thenReturn("http://example.test/view-control/16");
        when(emailChannelProvider.getIfAvailable()).thenReturn(emailChannel);

        // Returned by SoQM, resubmitted, then returned again by the Process Owner right away
        notificationService.sendReturnNotifications(control, List.of("op@example.test"),
                "SOQM_TEAM", "SoQM User", "Control Operator", "Fix the sample", "RETURN_TO_OPERATOR");
        notificationService.sendReturnNotifications(control, List.of("op@example.test"),
                "PROCESS_OWNER", "Owner User", "Control Operator", "Attach evidence", "RETURN_TO_OPERATOR");

        verify(notificationRepository, times(2)).save(any(Notification.class));
        verify(emailChannel, times(2)).send("op@example.test", "Returned", "Body");
    }

    @Test
    void sendReturnNotifications_sameRecipientTwiceInOneCall_isNotifiedOnce() {
        Control control = controlWithId(17L);
        User facilitator = userWithId(12L, "fac@example.test", "FACILITATOR");

        when(userRepository.findByMail("fac@example.test")).thenReturn(Optional.of(facilitator));
        when(notificationTemplateService.renderReturnNotification(
                any(Control.class), anyString(), anyString(), any(), eq("RETURN_TO_FACILITATOR")))
                .thenReturn(new NotificationTemplateService.NotificationTemplate(
                        "Returned", "Body", "RETURN_TO_FACILITATOR"));
        when(notificationTemplateService.buildControlLink(any(Control.class)))
                .thenReturn("http://example.test/view-control/17");
        when(emailChannelProvider.getIfAvailable()).thenReturn(null);

        notificationService.sendReturnNotifications(control,
                List.of("fac@example.test", " fac@example.test ", "fac@example.test"),
                "CONTROL_OPERATOR", "Operator User", "Facilitator", null, "RETURN_TO_FACILITATOR");

        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    // ------------------------------------------------------------------ the role KDN gets no notification (2026-10-14)

    @Test
    void theRoleKdn_getsNoNotification_andNoEmail_aUserWithEditOnTheSameKdnControlDoes() {
        Control control = controlWithId(30L);
        control.setControlId("KDN-30");
        User kdn = kdnUser(31L, "kdn@example.test");
        User editor = userWithId(32L, "editor@example.test", "FACILITATOR");
        when(userRepository.findByMail("kdn@example.test")).thenReturn(Optional.of(kdn));
        when(userRepository.findByMail("editor@example.test")).thenReturn(Optional.of(editor));
        stubTemplate();
        when(notificationTemplateService.renderReturnNotification(any(), any(), any(), any(), any()))
                .thenReturn(new NotificationTemplateService.NotificationTemplate("Returned", "Body", "RETURN"));
        when(notificationTemplateService.buildPerformanceCycleLink(any(Control.class))).thenReturn("/performance-cycle/30");
        when(emailChannelProvider.getIfAvailable()).thenReturn(emailChannel);
        List<String> both = List.of("kdn@example.test", "editor@example.test");

        notificationService.sendTemplateNotifications(control, both,
                NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR, false);
        notificationService.sendInitiateNotifications(control, both);
        notificationService.sendReturnNotifications(control, both, "SoQM Team", "SoQM One", "Facilitator",
                "Redo", "RETURN");
        notificationService.sendOnBehalfCopy(control, both, "SoQM One", "Facilitator", "Submit", "IN_PROGRESS",
                "REVIEW", null);
        notificationService.sendSharedWithNotification(control, "kdn@example.test", "SoQM One");
        notificationService.sendSharedWithNotification(control, "editor@example.test", "SoQM One");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(5)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Notification::getUserId).containsOnly(32L);
        verify(emailChannel, never()).send(eq("kdn@example.test"), any(), any());
        // template, initiate, return and share by e-mail; the on-behalf copy is in-app only
        verify(emailChannel, times(4)).send(eq("editor@example.test"), any(), any());
    }

    @Test
    void receives_everyoneButTheRoleKdn() {
        assertThat(NotificationService.receives(kdnUser(1L, "kdn@example.test"))).isFalse();
        assertThat(NotificationService.receives(userWithId(2L, "f@example.test", "FACILITATOR"))).isTrue();
        User soqm = userWithId(3L, "s@example.test", "SOQM_TEAM");
        soqm.setAccessLevel(com.kpmg.qtracker.enums.AccessLevel.SOQM);
        soqm.setAccessScope(com.kpmg.qtracker.enums.AccessScope.ALL);
        assertThat(NotificationService.receives(soqm)).isTrue();
        User readOnly = userWithId(4L, "r@example.test", "READ_ONLY");
        readOnly.setAccessLevel(com.kpmg.qtracker.enums.AccessLevel.READ_ONLY);
        readOnly.setAccessScope(com.kpmg.qtracker.enums.AccessScope.ALL);
        assertThat(NotificationService.receives(readOnly)).isTrue();
        assertThat(NotificationService.receives(null)).isFalse();
    }

    private User kdnUser(Long id, String mail) {
        User user = userWithId(id, mail, "KDN");
        user.setAccessLevel(com.kpmg.qtracker.enums.AccessLevel.READ_ONLY);
        user.setAccessScope(com.kpmg.qtracker.enums.AccessScope.KDN);
        user.setEnabled(true);
        return user;
    }

    private void stubTemplate() {
        NotificationTemplateService.NotificationTemplate template =
                new NotificationTemplateService.NotificationTemplate("Subject", "Body", "WORKFLOW_STEP");
        when(notificationTemplateService.render(
                any(NotificationTemplateService.TemplateType.class),
                any(Control.class),
                any(LocalDate.class),
                anyBoolean(),
                anyString(),
                anyString()
        )).thenReturn(template);
        when(notificationTemplateService.buildControlLink(any(Control.class)))
                .thenReturn("http://example.test/view-control/1");
    }

    private Control controlWithId(Long id) {
        Control control = new Control();
        control.setId(id);
        control.setControlId("CTRL-" + id);
        control.setDeadline(LocalDate.of(2026, 2, 4));
        return control;
    }

    private User userWithId(Long id, String mail, String role) {
        User user = new User();
        user.setId(id);
        user.setMail(mail);
        user.setRole(role);
        user.setDisplayName(role + " User");
        return user;
    }
}

