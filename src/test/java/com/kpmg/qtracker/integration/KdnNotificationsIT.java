package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Business decision of 2026-10-14: the role KDN has no notifications - none is made for a KDN user, in the application
 * or by e-mail, also where they hold a field of a KDN control, and their menu has no Notifications item, bell or
 * counter; /notifications leads to /kdn. A User with Edit on the same KDN control gets everything as before.
 * E-mail is on here, sent to a mocked mail server.
 */
@TestPropertySource(properties = {"notifications.email.enabled=true", "notifications.email.whitelist-enforced=false",
        "management.health.mail.enabled=false"})
class KdnNotificationsIT extends ReleaseScenarioSupport {

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void mailServer() {
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void aKdnControlMovedReturnedAndShared_notifiesTheUsersWithEdit_neverTheRoleKdn() throws Exception {
        User soqm = soqm("kn-soqm");
        User kdnFacilitator = kdn("kn-kdn-fac");
        User kdnNewlyShared = kdn("kn-kdn-shared");
        User operator = myEdit("kn-op");
        User owner = myEdit("kn-po");
        User editNewlyShared = myEdit("kn-shared");
        Control control = control("KDN-NTF", "IN_PROGRESS",
                people().soqm(soqm).facilitator(kdnFacilitator).operator(operator).owner(owner));
        MockHttpSession soqmSession = login(soqm);

        // SoQM makes the KDN Facilitator's step for them, then returns the control to them
        assertThat(status(soqmSession, post("/api/workflow/move").with(csrf().asHeader())
                .param("controlId", String.valueOf(control.getId())).param("targetStatus", "REVIEW")
                .param("comments", "Submitted for the KDN Facilitator"))).isEqualTo(200);
        assertThat(status(soqmSession, post("/api/workflow/move").with(csrf().asHeader())
                .param("controlId", String.valueOf(control.getId())).param("targetStatus", "IN_PROGRESS")
                .param("comments", "Back to the Facilitator"))).isEqualTo(200);
        // Two people newly in Shared With: a KDN user and a User with Edit
        MvcResult shared = callJson(soqmSession, post("/api/control-assignment").with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON),
                "{\"controlId\":" + control.getId() + ",\"controlSharedWith\":[\"" + kdnNewlyShared.getMail()
                        + "\",\"" + editNewlyShared.getMail() + "\"]}");
        assertThat(shared.getResponse().getStatus()).as(body(shared)).isEqualTo(200);

        List<Notification> notices = notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(notices).extracting(Notification::getUserId)
                .contains(operator.getId(), editNewlyShared.getId())
                .doesNotContain(kdnFacilitator.getId(), kdnNewlyShared.getId());
        assertThat(notices).filteredOn(n -> "CONTROL_SHARED".equals(n.getType()))
                .extracting(Notification::getUserId).containsExactly(editNewlyShared.getId());

        List<String> mailedTo = mailedTo();
        assertThat(mailedTo).contains(operator.getMail(), editNewlyShared.getMail())
                .doesNotContain(kdnFacilitator.getMail(), kdnNewlyShared.getMail());
    }

    @Test
    void theKdnMenu_hasOnlyKdn_andTheNotificationPagesLeadToTheKdnPage() throws Exception {
        User kdnUser = kdn("kn-menu");
        MockHttpSession session = login(kdnUser);

        String page = body(call(session, get("/kdn")));
        assertThat(page).contains("id=\"sidebarKdn\"")
                .doesNotContain("id=\"sidebarNotifications\"", "href=\"/notifications\"", "bi-bell", "notif-pill",
                        "id=\"sidebarDashboard\"", "id=\"sidebarActionCentre\"");
        assertThat(call(session, get("/notifications")).getResponse().getRedirectedUrl()).isEqualTo("/kdn");
        assertThat(call(session, get("/notification/1")).getResponse().getRedirectedUrl()).isEqualTo("/kdn");
        assertThat(call(session, post("/notifications/mark-all-read").with(csrf().asHeader()))
                .getResponse().getRedirectedUrl()).isEqualTo("/kdn");
        assertThat(status(session, post("/notifications/1/read").with(csrf().asHeader()))).isEqualTo(404);

        // A User with Edit keeps the item
        MockHttpSession editor = login(myEdit("kn-menu-edit"));
        assertThat(body(call(editor, get("/component/ALL")))).contains("id=\"sidebarNotifications\"");
    }

    private List<String> mailedTo() throws Exception {
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(sent.capture());
        List<String> to = new ArrayList<>();
        for (MimeMessage message : sent.getAllValues()) {
            for (Address address : message.getAllRecipients()) {
                to.add(address.toString());
            }
        }
        return to;
    }
}
