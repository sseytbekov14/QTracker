package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release test pass, stage A: signing in and out, the session, CSRF, the Admin Panel, requests that go around
 * the pages (another person's ids, a swapped controlId), Swagger and the error pages.
 */
class ReleaseScenarioAuthIT extends ReleaseScenarioSupport {

    private static final List<String> LEAKS = List.of("Exception", "at com.kpmg", "at org.springframework",
            "Caused by", "java.lang", "SQL", "Hibernate", "trace");

    // ---- signing in ----

    @Test
    void signIn_withTheAddress_theUsernameOrStraySpacesAndCapitals_opensTheDashboard() throws Exception {
        User user = myEdit("login");
        String username = user.getMail().substring(0, user.getMail().indexOf('@'));

        for (String typed : List.of(user.getMail(), username, "  " + user.getMail().toUpperCase() + " ")) {
            MvcResult result = signIn(typed, PASSWORD);
            assertThat(result.getResponse().getRedirectedUrl()).as("login as '%s'", typed).isEqualTo("/");
            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            MvcResult home = call(session, get("/"));
            assertThat(home.getResponse().getStatus()).isEqualTo(200);
            assertThat(home.getModelAndView().getViewName()).isEqualTo("dashboard");
        }
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    void wrongPassword_andUnknownPerson_getTheSameMessage_andNoSession() throws Exception {
        User user = myEdit("wrong");
        MvcResult wrong = signIn(user.getMail(), "not-the-password");
        MvcResult unknown = signIn("nobody-" + RUN + "@release.test", PASSWORD);

        for (MvcResult failed : List.of(wrong, unknown)) {
            assertThat(failed.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
            String page = signInPage(failed);
            assertThat(page).contains("Invalid username or password");
            MockHttpSession session = (MockHttpSession) failed.getRequest().getSession(false);
            assertThat(outcome(call(session, get("/")))).isEqualTo("login");
            assertThat(outcome(call(session, get("/api/dashboard/deadline-countdown")))).isEqualTo("login");
        }
    }

    @Test
    void disabledPerson_cannotSignIn_andASignedInPersonDisabledLater_isSentOutOnTheNextRequest() throws Exception {
        User gone = disable(myEdit("disabled"));
        MvcResult refused = signIn(gone.getMail(), PASSWORD);
        assertThat(refused.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signInPage(refused)).contains("Your account is disabled");

        User later = myEdit("disabled-later");
        Control control = control("RS-DIS", "IN_PROGRESS", people().facilitator(later));
        MockHttpSession session = login(later);
        assertThat(status(session, get("/view-control/{id}", control.getId()))).isEqualTo(200);

        disable(later);
        MvcResult page = call(session, get("/view-control/{id}", control.getId()));
        assertThat(outcome(page)).isEqualTo("login");
        // The session is emptied: the next API call is not answered with data either
        MvcResult api = call(session, get("/api/control-details").param("controlId", String.valueOf(control.getId())));
        assertThat(api.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(body(api)).doesNotContain("Steps performed");
    }

    @Test
    void fiveWrongPasswords_lockTheAccount_theRightPasswordIsRefusedToo_otherPeopleAreNotAffected() throws Exception {
        User user = myEdit("lock");
        User neighbour = myEdit("lock-neighbour");
        for (int i = 0; i < 5; i++) {
            assertThat(signIn(user.getMail(), "wrong-" + i).getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        }
        MvcResult locked = signIn(user.getMail(), PASSWORD);
        assertThat(locked.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(signInPage(locked)).contains("Too many failed login attempts");
        // Upper case and spaces are the same account
        assertThat(signInPage(signIn(" " + user.getMail().toUpperCase() + " ", PASSWORD)))
                .contains("Too many failed login attempts");

        assertThat(signIn(neighbour.getMail(), PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    /**
     * Since feature/sso-saml a sign-in counts per name typed and address together (behind the port forward every
     * client has the same address): the 21st try of one name in a minute is refused, another person from the
     * same address is not.
     */
    @Test
    void moreThanTwentySignInsAMinuteOfOneNameFromOneAddress_areRefusedWith429_anotherNameIsNot() throws Exception {
        String address = "10.66.1." + (1 + next() % 250);
        String name = "rate-limited-" + RUN + "@release.test";
        for (int i = 0; i < 20; i++) {
            MvcResult result = mockMvc.perform(post("/login").with(csrf()).with(address(address))
                    .param("username", name).param("password", "x-" + i)).andReturn();
            assertThat(result.getResponse().getStatus()).as("attempt %d", i + 1).isEqualTo(302);
        }
        MvcResult limited = mockMvc.perform(post("/login").with(csrf()).with(address(address))
                .param("username", name).param("password", "x")).andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited)).contains("RATE_LIMITED");

        MvcResult otherName = mockMvc.perform(post("/login").with(csrf()).with(address(address))
                .param("username", "somebody-else-" + RUN + "@release.test").param("password", "x")).andReturn();
        assertThat(otherName.getResponse().getStatus()).isEqualTo(302);

        User user = myEdit("rate");
        assertThat(signIn(user.getMail(), PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    // ---- signing out, the session, CSRF ----

    /**
     * No Logout in QTracker (decision of 2026-10-16): no link in the sidebar and no /logout, the session goes on.
     * LogoutSwitchedOnIT: what it does with AUTH_LOGOUT_ENABLED=true.
     */
    @Test
    void thereIsNoLogout_noLinkAndNoAddress_theSessionGoesOn() throws Exception {
        User user = myEdit("no-logout");
        MockHttpSession session = login(user);
        MvcResult dashboard = call(session, get("/"));
        assertThat(dashboard.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(dashboard)).doesNotContain("href=\"/logout\"").doesNotContain("sidebar-logout\"");

        assertThat(call(session, get("/logout")).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(session, post("/logout").with(csrf())).getResponse().getStatus()).isIn(404, 405);
        assertThat(call(session, get("/")).getResponse().getStatus()).isEqualTo(200);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void anExpiredSession_sendsPagesAndApiCallsToTheSignInPage_withoutData() throws Exception {
        User user = myEdit("expired");
        Control control = control("RS-EXP", "IN_PROGRESS", people().facilitator(user));
        // The server no longer knows the session of the cookie: the request arrives without one
        MockHttpSession expired = new MockHttpSession();

        assertThat(outcome(call(expired, get("/view-control/{id}", control.getId())))).isEqualTo("login");
        MvcResult read = call(expired, get("/api/control-details").param("controlId", String.valueOf(control.getId())));
        assertThat(outcome(read)).isEqualTo("login");
        MvcResult write = callJson(expired, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"After expiry\"}");
        assertThat(outcome(write)).isEqualTo("login");
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Steps performed");
    }

    /**
     * The server side of an out-of-date page: a token that is not the session's (an old tab after signing in
     * again) or none at all. The old-tab case itself is checked in the browser on the H2 stand (report, stage A):
     * MockMvc's csrf() helper replaces the token store of the shared filter, so cookies cannot be followed here.
     */
    @Test
    void aWrongOrMissingCsrfToken_isRefusedWithTheOutOfDateMessage_andNothingIsSaved() throws Exception {
        User user = myEdit("csrf");
        Control control = control("RS-CSRF", "IN_PROGRESS", people().facilitator(user));
        MockHttpSession session = login(user);
        String json = "{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Saved with %s\"}";

        MvcResult stale = mockMvc.perform(post("/api/control-details").session(session).with(ownAddress())
                .with(csrf().asHeader().useInvalidToken())
                .contentType("application/json").content(json.formatted("stale"))).andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(403);
        JsonNode error = json(stale);
        assertThat(error.path("code").asText()).isEqualTo("CSRF_INVALID");
        assertThat(error.path("message").asText()).isEqualTo("This page is out of date. Reload the page and try again.");

        MvcResult missing = mockMvc.perform(post("/api/control-details").session(session).with(ownAddress())
                .contentType("application/json").content(json.formatted("none"))).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(missing).path("code").asText()).isEqualTo("CSRF_INVALID");
        // A page form (mark all notifications read) without a token
        MvcResult form = mockMvc.perform(post("/notifications/mark-all-read").session(session).with(ownAddress()))
                .andReturn();
        assertThat(form.getResponse().getStatus()).isEqualTo(403);
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Steps performed");

        MvcResult ok = callJson(session, post("/api/control-details"), json.formatted("fresh"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Saved with fresh");
    }

    // ---- Admin Panel ----

    @Test
    void adminPanel_opensOnlyForSoqmTeam_everyoneElseIsSentHome_andItsApiRefusesThem() throws Exception {
        User soqm = soqm("admin");
        MvcResult panel = call(login(soqm), get("/admin/users"));
        assertThat(panel.getResponse().getStatus()).isEqualTo(200);
        assertThat(panel.getModelAndView().getViewName()).isEqualTo("admin-users");

        User target = myEdit("admin-target");
        String made = "made-" + RUN + "@release.test";
        for (User user : List.of(myEdit("adm-my"), allEdit("adm-all"), myRead("adm-ro"), allRead("adm-allro"),
                kdn("adm-kdn"))) {
            MockHttpSession session = login(user);
            assertThat(call(session, get("/admin/users")).getResponse().getRedirectedUrl())
                    .as(user.getMail()).isEqualTo("/");
            assertThat(status(session, get("/api/users"))).as("list " + user.getMail()).isEqualTo(403);
            assertThat(status(session, post("/api/users").param("email", made).param("displayName", "Made Up")
                    .param("role", "SOQM_TEAM"))).as("create " + user.getMail()).isEqualTo(403);
            assertThat(status(session, post("/api/users/{id}/access", target.getId())
                    .param("role", "SOQM_TEAM").param("enabled", "true"))).as("change " + user.getMail()).isEqualTo(403);
        }
        User stored = userRepository.findById(target.getId()).orElseThrow();
        assertThat(stored.getAccessLevel()).isEqualTo(target.getAccessLevel());
        assertThat(userRepository.findByMail(made)).isEmpty();
    }

    // ---- requests around the pages ----

    @Test
    void anotherPersonsControl_isRefusedOnEveryReadAndWrite_byIdOrBySwappingTheControlId() throws Exception {
        User owner = myEdit("idor-owner");
        User stranger = myEdit("idor-stranger");
        Control theirs = control("RS-IDOR", "IN_PROGRESS", people().facilitator(owner), control -> {
            try {
                control.setAttachmentDetailsPath(fileStorageService.saveFile(new MockMultipartFile("file",
                        "their-only.pdf", "application/pdf", "%PDF-1.4 theirs".getBytes()), control.getControlId()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Control mine = control("RS-MINE", "IN_PROGRESS", people().facilitator(stranger));
        String theirFile = reload(theirs).getAttachmentDetailsPath();
        assertThat(theirFile).isNotEqualTo(reload(mine).getAttachmentDetailsPath());
        MockHttpSession session = login(stranger);
        String id = String.valueOf(theirs.getId());

        assertThat(status(session, get("/view-control/{id}", theirs.getId()))).isEqualTo(403);
        for (String path : List.of("/api/control-details?controlId=" + id, "/api/control-assignment?controlId=" + id,
                "/api/controls/" + id + "/changelog",
                "/api/attachments/info/" + id, "/api/attachments/download/" + theirFile + "?controlId=" + id,
                "/api/users/all?controlId=" + id)) {
            MvcResult result = call(session, get(path));
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(403);
            assertThat(body(result)).as(path).doesNotContain("Steps performed").doesNotContain(owner.getMail());
        }
        // Writes with their id
        assertThat(status(session, post("/api/control-details").contentType("application/json")
                .content("{\"controlId\":" + id + ",\"controlStepsPerformed\":\"Hijacked\"}"))).isEqualTo(403);
        assertThat(status(session, post("/api/control-assignment").contentType("application/json")
                .content("{\"controlId\":" + id + ",\"controlSharedWith\":[\"" + stranger.getMail() + "\"]}")))
                .isEqualTo(403);
        assertThat(status(session, put("/api/controls/{id}", id).contentType("application/json")
                .content("{\"controlDescription\":\"Hijacked\"}"))).isEqualTo(403);
        assertThat(status(session, multipart("/api/attachments/upload/{id}", id)
                .file(new MockMultipartFile("attachmentDetails", "x.pdf", "application/pdf", "%PDF-1.4".getBytes()))))
                .isEqualTo(403);
        assertThat(status(session, delete("/api/attachments/delete/{id}", id)
                .param("filename", theirFile).param("type", "details"))).isEqualTo(403);
        assertThat(status(session, post("/api/workflow/submit-to-control-operator")
                .param("controlId", id).param("comments", "x"))).isIn(403, 409);
        // Their file through my control
        assertThat(status(session, get("/api/attachments/download/{f}", theirFile)
                .param("controlId", String.valueOf(mine.getId())))).isEqualTo(404);
        assertThat(status(session, delete("/api/attachments/delete/{id}", mine.getId())
                .param("filename", theirFile).param("type", "details"))).isIn(403, 404);

        Control after = reload(theirs);
        assertThat(after.getPerformanceStatus()).isEqualTo("IN_PROGRESS");
        assertThat(after.getControlDescription()).isEqualTo("Release test control");
        assertThat(after.getAttachmentDetailsPath()).isEqualTo(theirFile);
        assertThat(detailsRepository.findByControlId(theirs.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Steps performed");
        assertThat(assignmentRepository.findByControlId(theirs.getId()).orElseThrow().getControlSharedWith()).isNull();

        // Helpers of the page answer with flags only: no data of the control
        JsonNode permissions = json(call(session, get("/api/permissions/{id}", id)));
        assertThat(permissions.path("permissions").path("canView").asBoolean()).isFalse();
        assertThat(permissions.path("permissions").path("canEdit").asBoolean()).isFalse();
        assertThat(permissions.toString()).doesNotContain(theirs.getControlId()).doesNotContain(owner.getMail());
        assertThat(json(call(session, get("/api/workflow/{id}/available-buttons", id))).size()).isZero();

        // An id nobody has
        assertThat(status(session, get("/api/control-details").param("controlId", "987654321"))).isEqualTo(404);
        assertThat(status(session, get("/view-control/987654321"))).isEqualTo(404);
        // Someone else's list of created controls
        assertThat(status(session, get("/api/controls/user/{mail}", owner.getMail()))).isEqualTo(403);
    }

    @Test
    void theControlIdInTheBodyOfASave_neverRenamesTheControl_onlyRenameIdDoes() throws Exception {
        User soqm = soqm("swap");
        Control control = control("RS-SWAP", "IN_PROGRESS", people().soqm(soqm));
        Control other = control("RS-OTHER", "IN_PROGRESS", people().soqm(soqm));
        MockHttpSession session = login(soqm);

        MvcResult saved = callJson(session, put("/api/controls/{id}", control.getId()),
                "{\"controlId\":\"" + other.getControlId() + "\",\"controlDescription\":\"Changed\"}");
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        Control after = reload(control);
        assertThat(after.getControlId()).isEqualTo(control.getControlId());
        assertThat(after.getControlDescription()).isEqualTo("Changed");
        assertThat(reload(other).getControlDescription()).isEqualTo("Release test control");
    }

    @Test
    void anotherPersonsNotification_isNotShownNorMarkedRead() throws Exception {
        User owner = myEdit("notif-owner");
        User stranger = myEdit("notif-stranger");
        Control control = control("RS-NOTIF", "IN_PROGRESS", people().facilitator(owner, stranger));
        Notification notification = new Notification();
        notification.setControlId(control.getId());
        notification.setUserId(owner.getId());
        notification.setType("WORKFLOW_STEP");
        notification.setTitle("Private title " + RUN);
        notification.setMessage("Private message");
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        notification = notificationRepository.save(notification);
        MockHttpSession session = login(stranger);

        MvcResult page = call(session, get("/notification/{id}", notification.getId()));
        assertThat(body(page)).doesNotContain("Private title").contains("Notification not found");
        assertThat(status(session, post("/notifications/{id}/read", notification.getId()))).isEqualTo(404);
        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getIsRead()).isFalse();
    }

    // ---- Swagger, actuator, error pages ----

    @Test
    void swaggerAndTheApiDescription_areClosedWithoutSigningIn() throws Exception {
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html",
                "/swagger-ui.html")) {
            MvcResult result = mockMvc.perform(get(path).with(ownAddress())).andReturn();
            assertThat(outcome(result)).as(path).isIn("login", "404");
            assertThat(body(result)).as(path).doesNotContain("/api/controls").doesNotContain("openapi");
        }
        // The dev profile turns it on for people who are signed in (off everywhere else: application.yml)
        MvcResult signedIn = call(login(myEdit("swagger")), get("/v3/api-docs"));
        assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(signedIn)).contains("openapi");
    }

    @Test
    void actuator_showsOnlyTheHealthStatus() throws Exception {
        MvcResult health = mockMvc.perform(get("/actuator/health").with(ownAddress())).andReturn();
        // 503 here: no mail server in the tests, and the mail check is part of the health (see the report)
        assertThat(health.getResponse().getStatus()).isIn(200, 503);
        assertThat(body(health)).contains("\"status\"").doesNotContain("components").doesNotContain("diskSpace");
        User soqm = soqm("actuator");
        MockHttpSession session = login(soqm);
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/mappings")) {
            assertThat(status(session, get(path))).as(path).isEqualTo(404);
        }
    }

    @Test
    void errorPagesAndErrorAnswers_showNoInternalDetails() throws Exception {
        User user = myEdit("errors");
        MockHttpSession session = login(user);

        MvcResult missingPage = call(session, get("/no-such-page-" + RUN));
        assertThat(missingPage.getResponse().getStatus()).isEqualTo(404);
        assertNoLeak(missingPage);

        MvcResult missingControl = call(session, get("/view-control/987654321"));
        assertThat(missingControl.getResponse().getStatus()).isEqualTo(404);
        assertNoLeak(missingControl);

        MvcResult badId = call(session, get("/view-control/not-a-number"));
        assertThat(badId.getResponse().getStatus()).isIn(400, 404, 500);
        assertNoLeak(badId);

        MvcResult badApi = call(session, get("/api/controls/not-a-number/changelog"));
        assertThat(badApi.getResponse().getStatus()).isEqualTo(400);
        assertNoLeak(badApi);

        MvcResult badJson = call(session, post("/api/control-details").contentType("application/json").content("{not json"));
        assertThat(badJson.getResponse().getStatus()).isEqualTo(400);
        assertNoLeak(badJson);

        // Without signing in nothing tells whether a path exists
        assertThat(outcome(mockMvc.perform(get("/no-such-page-" + RUN).with(ownAddress())).andReturn()))
                .isEqualTo("login");
    }

    @Test
    void pages_carryTheSecurityHeaders() throws Exception {
        User user = myEdit("headers");
        MvcResult page = call(login(user), get("/"));
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'self'")
                .contains("frame-ancestors 'self'").contains("object-src 'none'");
        assertThat(page.getResponse().getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(page.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(page.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    private void assertNoLeak(MvcResult result) throws Exception {
        String text = body(result);
        for (String leak : LEAKS) {
            assertThat(text).as("%s in %s", leak, result.getRequest().getRequestURI()).doesNotContain(leak);
        }
    }

    private String signInPage(MvcResult failed) throws Exception {
        MockHttpSession session = (MockHttpSession) failed.getRequest().getSession(false);
        return body(mockMvc.perform(get("/login").param("error", "").session(session).with(ownAddress())).andReturn());
    }
}
