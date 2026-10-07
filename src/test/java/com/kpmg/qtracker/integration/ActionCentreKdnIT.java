package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
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
import com.kpmg.qtracker.service.KdnControlsOverview;
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
 * The Action Centre's "KDN controls" block end to end, on a database of its own (exact numbers): who gets it,
 * which controls it lists (the policy's, KDN only), and that its counters, its list and the Controls list behind
 * each counter agree, Overdue and Reopened included.
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
        // nobody is on, a completed one past its deadline (not overdue), one completed and reopened by SoQM
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
    void kdnUser_everyKdnControl_draftsIncluded_blockFirst_noActionBlocks() throws Exception {
        MvcResult dashboard = perform(get("/"), login(kdn));
        KdnControlsOverview.Overview overview = overview(dashboard);
        String html = dashboard.getResponse().getContentAsString();

        assertThat(ids(overview.controls())).containsExactly("KDN-AC-1", "kdn-ac-2", "KDN-AC-5", "KDNAC3", "KDN-AC-4");
        assertCounters(overview, 5, 1, 2, 1, 2, 1);
        assertThat(html).doesNotContain("id=\"actionQueueTitle\"", "<section class=\"action-queue\"", "awaiting your action")
                .contains("<section class=\"kdn-block is-primary\"");
        assertThat(html.indexOf("id=\"kdnBlockTitle\"")).isLessThan(html.indexOf("class=\"ac-summary\""));
        // KDN users see only KDN controls: the dashboard tiles count the same controls
        assertThat(dashboard.getModelAndView().getModel()).containsEntry("totalControls", 5)
                .containsEntry("overdueControls", 2);
        assertBlockAgreesWithTheControlsList(overview, login(kdn));
    }

    @Test
    void soqmTeam_everyKdnControl_blockAfterTheComponents_actionQueueKept() throws Exception {
        MockHttpSession session = login(soqm);
        MvcResult dashboard = perform(get("/"), session);
        KdnControlsOverview.Overview overview = overview(dashboard);
        String html = dashboard.getResponse().getContentAsString();

        assertThat(ids(overview.controls())).containsExactly("KDN-AC-1", "kdn-ac-2", "KDN-AC-5", "KDNAC3", "KDN-AC-4");
        assertCounters(overview, 5, 1, 2, 1, 2, 1);
        assertThat(html).contains("id=\"actionQueueTitle\"", "<section class=\"kdn-block\"");
        assertThat(html.indexOf("id=\"kdnBlockTitle\"")).isGreaterThan(html.indexOf("class=\"ac-legend\""));
        assertBlockAgreesWithTheControlsList(overview, session);
    }

    @Test
    void userAllControls_everyKdnControl() throws Exception {
        MockHttpSession session = login(allRead);
        KdnControlsOverview.Overview overview = overview(perform(get("/"), session));

        assertThat(ids(overview.controls())).containsExactly("KDN-AC-1", "kdn-ac-2", "KDN-AC-5", "KDNAC3", "KDN-AC-4");
        assertCounters(overview, 5, 1, 2, 1, 2, 1);
        assertBlockAgreesWithTheControlsList(overview, session);
    }

    @Test
    void userMyControls_onlyTheirOwnKdnControls_orNoBlock() throws Exception {
        MockHttpSession session = login(mine);
        MvcResult dashboard = perform(get("/"), session);
        KdnControlsOverview.Overview overview = overview(dashboard);

        // On KDN-AC-1 and X-KDN-AC-6: only the KDN one, and their action queue stays
        assertThat(ids(overview.controls())).containsExactly("KDN-AC-1");
        assertCounters(overview, 1, 1, 0, 0, 1, 0);
        assertThat(dashboard.getResponse().getContentAsString()).contains("id=\"actionQueueTitle\"");
        assertBlockAgreesWithTheControlsList(overview, session);

        MvcResult none = perform(get("/"), login(mineNoKdn));
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(none.getModelAndView().getModel()).doesNotContainKey("kdnOverview");
        assertThat(none.getResponse().getContentAsString()).doesNotContain("kdnBlockTitle");
    }

    @Test
    void notSignedIn_signInPage() throws Exception {
        MvcResult dashboard = perform(get("/"), null);
        MvcResult controlsList = perform(get("/controls").param("kdn", "1"), null);

        assertThat(dashboard.getResponse().getRedirectedUrl()).contains("/login");
        assertThat(controlsList.getResponse().getRedirectedUrl()).contains("/login");
    }

    @Test
    void rows_overdueAndReopenedMarked_eachOpensViewControl() throws Exception {
        String html = perform(get("/"), login(soqm)).getResponse().getContentAsString();
        String block = html.substring(html.indexOf("id=\"kdnBlockTitle\""));

        assertThat(block).contains(viewLink("KDN-AC-1"), viewLink("kdn-ac-2"), viewLink("KDNAC3"), viewLink("KDN-AC-4"),
                viewLink("KDN-AC-5")).doesNotContain(viewLink("X-KDN-AC-6"), viewLink("HR-AC-7"));
        assertThat(block.split("<i class=\"bi bi-exclamation-triangle-fill\" aria-hidden=\"true\"></i>Overdue", -1)).hasSize(3);
        assertThat(block.split("<i class=\"bi bi-arrow-counterclockwise\" aria-hidden=\"true\"></i>Reopened", -1)).hasSize(2);
        String reopenedRow = block.substring(block.indexOf(viewLink("KDN-AC-5")));
        assertThat(reopenedRow.substring(0, reopenedRow.indexOf("</a>"))).contains("Reopened", "Review");
    }

    // ------------------------------------------------------------------ the block and the Controls list agree

    private void assertBlockAgreesWithTheControlsList(KdnControlsOverview.Overview overview, MockHttpSession session)
            throws Exception {
        MvcResult all = perform(get("/controls").param("kdn", "1"), session);
        assertThat(ids(listed(all))).containsExactlyInAnyOrderElementsOf(ids(overview.controls()));
        assertThat(all.getModelAndView().getModel()).containsEntry("totalControls", (int) overview.total())
                .containsEntry("overdueControls", (int) overview.overdue())
                .containsEntry("completedControls", (int) overview.completed());

        assertThat(listed(perform(get("/controls").param("kdn", "1").param("status", "IN_PROGRESS"), session)))
                .hasSize((int) overview.inProgress());
        assertThat(listed(perform(get("/controls").param("kdn", "1").param("status", "IN_REVIEW"), session)))
                .hasSize((int) overview.inReview());
        assertThat(listed(perform(get("/controls").param("kdn", "1").param("filter", "COMPLETED"), session)))
                .hasSize((int) overview.completed());
        List<ControlResponseDTO> overdue = listed(perform(get("/controls").param("kdn", "1").param("filter", "OVERDUE"), session));
        assertThat(ids(overdue)).containsExactlyInAnyOrderElementsOf(
                ids(overview.controls().stream().filter(ControlResponseDTO::isOverdue).toList()));
    }

    private static void assertCounters(KdnControlsOverview.Overview overview, long total, long inProgress, long inReview,
                                       long completed, long overdue, long drafts) {
        assertThat(List.of(overview.total(), overview.inProgress(), overview.inReview(), overview.completed(),
                overview.overdue(), overview.drafts()))
                .as("total, in progress, in review, completed, overdue, drafts")
                .containsExactly(total, inProgress, inReview, completed, overdue, drafts);
    }

    private static KdnControlsOverview.Overview overview(MvcResult dashboard) {
        assertThat(dashboard.getResponse().getStatus()).isEqualTo(200);
        Object overview = dashboard.getModelAndView().getModel().get("kdnOverview");
        assertThat(overview).as("KDN block").isNotNull();
        return (KdnControlsOverview.Overview) overview;
    }

    @SuppressWarnings("unchecked")
    private static List<ControlResponseDTO> listed(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (List<ControlResponseDTO>) result.getModelAndView().getModel().get("controls");
    }

    private static List<String> ids(List<ControlResponseDTO> controls) {
        return controls.stream().map(ControlResponseDTO::getControlId).toList();
    }

    private String viewLink(String controlId) {
        return "href=\"/view-control/" + controls.get(controlId).getId() + "\"";
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
