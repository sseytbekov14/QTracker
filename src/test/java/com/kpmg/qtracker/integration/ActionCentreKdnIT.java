package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.controller.ViewController;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Action Centre's "KDN" card end to end, on a database of its own (exact numbers): who gets it, which
 * controls it counts (the policy's, KDN only), and that its numbers agree with the Controls list it opens.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:action-centre-kdn-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads-action-centre-kdn"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionCentreKdnIT {

    private static final String PASSWORD = "Test#123";

    private static int addressCount;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    private User facilitator;
    private User operator;
    private User soqm;
    private User owner;
    private User kdn;
    private User allRead;
    private User mine;
    private User mineNoKdn;
    private LocalDate today;

    /** Controls by Control ID. */
    private final Map<String, Control> controls = new java.util.LinkedHashMap<>();

    /** One set of people and controls for every test: the tests only read. */
    @BeforeAll
    void setUp() throws Exception {
        today = DeadlineOverdue.today(Instant.now());
        facilitator = saveUser("ac-fac", AccessLevel.PARTICIPANT, AccessScope.OWN);
        operator = saveUser("ac-op", AccessLevel.PARTICIPANT, AccessScope.OWN);
        soqm = saveUser("ac-soqm", AccessLevel.SOQM, AccessScope.ALL);
        owner = saveUser("ac-po", AccessLevel.PARTICIPANT, AccessScope.OWN);
        kdn = saveUser("ac-kdn", AccessLevel.READ_ONLY, AccessScope.KDN);
        allRead = saveUser("ac-all-ro", AccessLevel.READ_ONLY, AccessScope.ALL);
        mine = saveUser("ac-mine", AccessLevel.PARTICIPANT, AccessScope.OWN);
        mineNoKdn = saveUser("ac-mine-none", AccessLevel.PARTICIPANT, AccessScope.OWN);

        // KDN controls: overdue in progress (mine is its Facilitator), overdue in Process Owner review, a draft
        // nobody is on, a completed one past its deadline (not overdue), one completed and reopened by SoQM (active)
        control("KDN-AC-1", "IN_PROGRESS", today.minusDays(3), mine);
        control("kdn-ac-2", "PROCESS_OWNER_REVIEW", today.minusDays(1), null);
        control("KDNAC3", "DRAFT", null, null);
        control("KDN-AC-4", "COMPLETED", today.minusDays(20), null);
        control("KDN-AC-5", "COMPLETED", today.plusDays(6), null);
        // Not KDN controls, overdue: never in the block, whoever sees them
        control("X-KDN-AC-6", "IN_PROGRESS", today.minusDays(4), mine);
        control("HR-AC-7", "REVIEW", today.minusDays(4), mineNoKdn);

        MockHttpSession soqmSession = login(soqm);
        MvcResult reopen = perform(post("/api/workflow/move").with(csrf().asHeader())
                .param("controlId", String.valueOf(controls.get("KDN-AC-5").getId()))
                .param("targetStatus", "REVIEW")
                .param("comments", "Wrong sample, redo"), soqmSession);
        assertThat(reopen.getResponse().getStatus()).as("SoQM reopens KDN-AC-5").isEqualTo(200);
        assertThat(controlRepository.findById(controls.get("KDN-AC-5").getId()).orElseThrow().getReopenedAt()).isNotNull();
    }

    @Test
    void kdnUser_everyKdnControl_draftsIncluded_cardFirst_noActionBlocks() throws Exception {
        MockHttpSession session = login(kdn);
        MvcResult dashboard = perform(get("/"), session);
        String pane = actionCentre(dashboard);

        assertCard(dashboard, 5, 2, 2, 1);
        assertThat(dashboard.getResponse().getContentAsString())
                .doesNotContain("id=\"actionQueueTitle\"", "<section class=\"action-queue\"", "awaiting your action");
        assertThat(pane.indexOf("href=\"/controls?kdn=1\"")).as("KDN card first in the grid")
                .isLessThan(pane.indexOf("href=\"/component/HR\""));
        // KDN users see only KDN controls: the dashboard tiles count the same controls
        assertThat(dashboard.getModelAndView().getModel()).containsEntry("totalControls", 5)
                .containsEntry("overdueControls", 2);
        assertCardAgreesWithTheControlsList(dashboard, session,
                List.of("KDN-AC-1", "kdn-ac-2", "KDNAC3", "KDN-AC-4", "KDN-AC-5"));
    }

    @Test
    void soqmTeam_everyKdnControl_cardLast_actionQueueKept() throws Exception {
        MockHttpSession session = login(soqm);
        MvcResult dashboard = perform(get("/"), session);
        String pane = actionCentre(dashboard);

        assertCard(dashboard, 5, 2, 2, 1);
        assertThat(dashboard.getResponse().getContentAsString()).contains("id=\"actionQueueTitle\"");
        assertThat(pane.indexOf("href=\"/controls?kdn=1\"")).as("KDN card last in the grid")
                .isGreaterThan(pane.indexOf("href=\"/component/RAP\""));
        assertCardAgreesWithTheControlsList(dashboard, session,
                List.of("KDN-AC-1", "kdn-ac-2", "KDNAC3", "KDN-AC-4", "KDN-AC-5"));
    }

    @Test
    void userAllControls_everyKdnControl() throws Exception {
        MockHttpSession session = login(allRead);
        MvcResult dashboard = perform(get("/"), session);

        assertCard(dashboard, 5, 2, 2, 1);
        assertCardAgreesWithTheControlsList(dashboard, session,
                List.of("KDN-AC-1", "kdn-ac-2", "KDNAC3", "KDN-AC-4", "KDN-AC-5"));
    }

    @Test
    void userMyControls_onlyTheirOwnKdnControls_orNoCard() throws Exception {
        MockHttpSession session = login(mine);
        MvcResult dashboard = perform(get("/"), session);

        // On KDN-AC-1 and X-KDN-AC-6: only the KDN one, and their action queue stays
        assertCard(dashboard, 1, 0, 1, 0);
        assertThat(dashboard.getResponse().getContentAsString()).contains("id=\"actionQueueTitle\"");
        assertCardAgreesWithTheControlsList(dashboard, session, List.of("KDN-AC-1"));

        MvcResult none = perform(get("/"), login(mineNoKdn));
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(none.getModelAndView().getModel()).doesNotContainKey("kdnSummary");
        assertThat(actionCentre(none)).doesNotContain("kdn=1");
    }

    @Test
    void notSignedIn_signInPage() throws Exception {
        MvcResult dashboard = perform(get("/"), null);
        MvcResult controlsList = perform(get("/controls").param("kdn", "1"), null);

        assertThat(dashboard.getResponse().getRedirectedUrl()).contains("/login");
        assertThat(controlsList.getResponse().getRedirectedUrl()).contains("/login");
    }

    // ------------------------------------------------------------------ the card and the Controls list agree

    /** The card in the model and on the page: total, active, overdue, completed. */
    private static void assertCard(MvcResult dashboard, long total, long active, long overdue, long completed)
            throws Exception {
        assertThat(dashboard.getResponse().getStatus()).isEqualTo(200);
        assertThat(dashboard.getModelAndView().getModel().get("kdnSummary"))
                .isEqualTo(new ViewController.ComponentSummary("KDN", "KDN controls", total, active, overdue, completed));
        String pane = actionCentre(dashboard);
        int at = pane.indexOf("href=\"/controls?kdn=1\"");
        assertThat(at).as("KDN card").isPositive();
        String card = pane.substring(pane.lastIndexOf("<a ", at), pane.indexOf("</a>", at));
        assertThat(card).contains("<span class=\"ac-code\">KDN</span>", "<strong>" + total + "</strong>",
                active + " active", completed + " done");
        if (overdue > 0) {
            assertThat(card).contains("<span class=\"ac-overdue-flag\">" + overdue + " overdue</span>");
        }
    }

    /** /controls?kdn=1 behind the card lists exactly the KDN controls the card counts, with the same numbers. */
    private void assertCardAgreesWithTheControlsList(MvcResult dashboard, MockHttpSession session, List<String> kdnIds)
            throws Exception {
        ViewController.ComponentSummary card =
                (ViewController.ComponentSummary) dashboard.getModelAndView().getModel().get("kdnSummary");
        MvcResult all = perform(get("/controls").param("kdn", "1"), session);
        assertThat(ids(listed(all))).containsExactlyInAnyOrderElementsOf(kdnIds).doesNotContain("X-KDN-AC-6", "HR-AC-7");
        assertThat(all.getModelAndView().getModel()).containsEntry("totalControls", (int) card.total())
                .containsEntry("activeControls", (int) card.active())
                .containsEntry("overdueControls", (int) card.overdue())
                .containsEntry("completedControls", (int) card.completed());
        assertThat(listed(perform(get("/controls").param("kdn", "1").param("filter", "COMPLETED"), session)))
                .hasSize((int) card.completed());
        assertThat(listed(perform(get("/controls").param("kdn", "1").param("filter", "OVERDUE"), session)))
                .hasSize((int) card.overdue()).allMatch(ControlResponseDTO::isOverdue);
    }

    private static String actionCentre(MvcResult dashboard) throws Exception {
        String html = dashboard.getResponse().getContentAsString();
        return html.substring(html.indexOf("id=\"pane-action\""), html.indexOf("id=\"pane-notifications\""));
    }

    @SuppressWarnings("unchecked")
    private static List<ControlResponseDTO> listed(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");
    }

    private static List<String> ids(List<ControlResponseDTO> controls) {
        return controls.stream().map(ControlResponseDTO::getControlId).toList();
    }

    // ------------------------------------------------------------------ data and requests

    private void control(String controlId, String status, LocalDate deadline, User facilitatorToo) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("GOV");
        control.setOperatedBy("Finance");
        control.setPriority("High");
        control.setNonAuditServicesApplicability("No");
        control.setControlStatus("ACTIVE");
        control.setControlDescription("Description of " + controlId);
        control.setPerformanceStatus(status);
        control.setSoqmYear(SoqmYear.current(today));
        control.setCreatedBy(soqm);
        control.setCreatedAt(LocalDateTime.now());
        control = controlRepository.save(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(facilitator.getMail() + (facilitatorToo != null ? "," + facilitatorToo.getMail() : ""));
        assignment.setControlOperator(operator.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(owner.getMail());
        assignment.setControlOperationDate(deadline != null ? deadline.minusDays(7) : null);
        assignment.setControlOperationDeadline(deadline);
        assignmentRepository.save(assignment);
        controls.put(controlId, control);
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setMail(name + "@action-centre.test");
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    private MockHttpSession login(User user) throws Exception {
        MvcResult login = mockMvc.perform(post("/login").with(csrf()).with(ownAddress())
                        .param("username", user.getMail())
                        .param("password", PASSWORD))
                .andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).as("login of %s", user.getMail()).isEqualTo("/");
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        request.with(ownAddress());
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** Every request from its own address: RateLimitingFilter counts per address. */
    private static RequestPostProcessor ownAddress() {
        String address = "10.8." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
