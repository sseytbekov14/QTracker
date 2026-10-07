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
import com.kpmg.qtracker.service.ComponentControlsList;
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
 * The Action Centre's "KDN" card and the lists behind the cards (/component/KDN, /component/HR) end to end, on a
 * database of its own (exact numbers): who gets them, which controls they hold (the policy's, KDN only for the KDN
 * list), that the card, the list and each counter's link agree, and the list's columns, rows, sorting, search,
 * filter and pages.
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
        // Human Resources, more than a page: SoQM Team and All controls see them; one Facilitator has no user
        List<String> statuses = List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "COMPLETED", "DRAFT");
        for (int i = 1; i <= 27; i++) {
            control(String.format("HR-PAGE-%02d", i), statuses.get(i % statuses.size()), today.plusDays(i - 10),
                    null, "HR", i == 1 ? "ghost.person@nowhere.test" : null);
        }

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

        assertCard(dashboard, 5, 1, 2, 1, 2);
        assertThat(dashboard.getResponse().getContentAsString())
                .doesNotContain("id=\"actionQueueTitle\"", "<section class=\"action-queue\"", "awaiting your action");
        assertThat(pane.indexOf("href=\"/component/KDN\"")).as("KDN card first in the grid")
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

        assertCard(dashboard, 5, 1, 2, 1, 2);
        assertThat(dashboard.getResponse().getContentAsString()).contains("id=\"actionQueueTitle\"");
        assertThat(pane.indexOf("href=\"/component/KDN\"")).as("KDN card last in the grid")
                .isGreaterThan(pane.indexOf("href=\"/component/RAP\""));
        assertCardAgreesWithTheControlsList(dashboard, session,
                List.of("KDN-AC-1", "kdn-ac-2", "KDNAC3", "KDN-AC-4", "KDN-AC-5"));
    }

    @Test
    void userAllControls_everyKdnControl() throws Exception {
        MockHttpSession session = login(allRead);
        MvcResult dashboard = perform(get("/"), session);

        assertCard(dashboard, 5, 1, 2, 1, 2);
        assertCardAgreesWithTheControlsList(dashboard, session,
                List.of("KDN-AC-1", "kdn-ac-2", "KDNAC3", "KDN-AC-4", "KDN-AC-5"));
    }

    @Test
    void userMyControls_onlyTheirOwnKdnControls_orNoCard() throws Exception {
        MockHttpSession session = login(mine);
        MvcResult dashboard = perform(get("/"), session);

        // On KDN-AC-1 and X-KDN-AC-6: only the KDN one, and their action queue stays
        assertCard(dashboard, 1, 1, 0, 0, 1);
        assertThat(dashboard.getResponse().getContentAsString()).contains("id=\"actionQueueTitle\"");
        assertCardAgreesWithTheControlsList(dashboard, session, List.of("KDN-AC-1"));

        MvcResult none = perform(get("/"), login(mineNoKdn));
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(none.getModelAndView().getModel()).doesNotContainKey("kdnSummary");
        assertThat(actionCentre(none)).doesNotContain("/component/KDN", "View all KDN controls");
    }

    @Test
    void notSignedIn_signInPage() throws Exception {
        MvcResult dashboard = perform(get("/"), null);
        MvcResult controlsList = perform(get("/controls").param("kdn", "1"), null);

        assertThat(dashboard.getResponse().getRedirectedUrl()).contains("/login");
        assertThat(controlsList.getResponse().getRedirectedUrl()).contains("/login");
    }

    // ------------------------------------------------------------------ the lists behind the cards

    @Test
    void kdnList_eachRole_exactlyTheKdnControlsTheySee_draftsIncluded() throws Exception {
        List<String> every = List.of("KDN-AC-1", "kdn-ac-2", "KDNAC3", "KDN-AC-4", "KDN-AC-5");
        Map<User, List<String>> expected = new java.util.LinkedHashMap<>();
        expected.put(kdn, every);
        expected.put(soqm, every);
        expected.put(allRead, every);
        expected.put(mine, List.of("KDN-AC-1"));
        expected.put(mineNoKdn, List.of());
        for (Map.Entry<User, List<String>> entry : expected.entrySet()) {
            MvcResult page = perform(get("/component/KDN"), login(entry.getKey()));
            ComponentControlsList.Result list = list(page);
            assertThat(rowIds(list)).as(entry.getKey().getMail())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue())
                    .doesNotContain("X-KDN-AC-6", "HR-AC-7");
            assertThat(list.counts().total()).isEqualTo(entry.getValue().size());
            assertThat(page.getResponse().getContentAsString()).contains("<h1>Performance: KDN controls (KDN)</h1>")
                    .doesNotContain("X-KDN-AC-6", "HR-AC-7", "HR-PAGE-");
        }
        assertThat(perform(get("/component/KDN"), login(mineNoKdn)).getResponse().getContentAsString())
                .contains("There are no KDN controls you can see.");
    }

    @Test
    void allComponentsList_eachRole_theControlsTheySee_theNumbersOfTheAllCard() throws Exception {
        Map<User, Integer> expected = new java.util.LinkedHashMap<>();
        expected.put(soqm, 34);       // 7 GOV + 27 HR
        expected.put(allRead, 34);
        expected.put(kdn, 5);         // the KDN controls only
        expected.put(mine, 2);        // KDN-AC-1 and X-KDN-AC-6
        expected.put(mineNoKdn, 1);   // HR-AC-7
        for (Map.Entry<User, Integer> entry : expected.entrySet()) {
            MockHttpSession session = login(entry.getKey());
            MvcResult dashboard = perform(get("/"), session);
            ViewController.ComponentSummary card =
                    (ViewController.ComponentSummary) dashboard.getModelAndView().getModel().get("componentSummaryAll");
            assertThat(actionCentre(dashboard)).contains("<a class=\"ac-summary\" href=\"/component/ALL\">");
            ComponentControlsList.Result all = list(perform(get("/component/ALL").param("size", "50"), session));
            assertThat(all.matching()).as(entry.getKey().getMail()).isEqualTo(entry.getValue());
            assertThat(all.counts()).as(entry.getKey().getMail()).isEqualTo(card.counts());
            assertThat(rowIds(all)).as(entry.getKey().getMail()).allMatch(id ->
                    controls.containsKey(id) && (entry.getKey() != kdn || id.toUpperCase().startsWith("KDN")));
        }
    }

    @Test
    void notSignedIn_noList() throws Exception {
        for (String path : List.of("/component/KDN", "/component/HR", "/component/ALL", "/component/GOV?q=KDN")) {
            assertThat(perform(get(path), null).getResponse().getRedirectedUrl()).as(path).contains("/login");
        }
    }

    @Test
    void kdnList_eachRowOpensItsOwnControl_theDraftToo() throws Exception {
        MockHttpSession session = login(kdn);
        MvcResult page = perform(get("/component/KDN"), session);
        String html = page.getResponse().getContentAsString();
        for (ComponentControlsList.Row row : list(page).rows()) {
            String href = "/view-control/" + controls.get(row.controlId()).getId();
            assertThat(row.href()).as(row.controlId()).isEqualTo(href);
            assertThat(html).contains("data-href=\"" + href + "\"", "href=\"" + href + "\">" + row.controlId() + "</a>");
            MvcResult opened = perform(get(href), session);
            assertThat(opened.getResponse().getStatus()).as(row.controlId()).isEqualTo(200);
            assertThat(opened.getModelAndView().getViewName()).as(row.controlId()).isEqualTo("view-control");
            assertThat(opened.getModelAndView().getModel().get("control")).extracting("controlId").isEqualTo(row.controlId());
        }
        assertThat(rowIds(list(page))).contains("KDNAC3");
    }

    @Test
    void componentList_tenColumns_namesSortSearchFilterPages() throws Exception {
        MockHttpSession session = login(soqm);
        MvcResult first = perform(get("/component/HR"), session);
        String html = first.getResponse().getContentAsString();
        java.util.regex.Matcher header = java.util.regex.Pattern
                .compile("<a class=\"sort-link\"[^>]*>\\s*<span>([^<]+)</span>").matcher(html);
        List<String> headers = new java.util.ArrayList<>();
        while (header.find()) {
            headers.add(header.group(1));
        }
        assertThat(headers).containsExactly("Control ID", "Control Type", "Control Frequency",
                "Facilitator / Preparer(s)", "Control Operator", "Process Owner", "SoQM Lead / Delegate",
                "Control Category", "Control Operation Date", "Performance Status");
        assertThat(html).contains("<h1>Performance: Human Resources (HR)</h1>", "of <span>27</span>");

        // pages of 25 (default) or 50; past the end is the last page
        ComponentControlsList.Result page1 = list(first);
        assertThat(page1.rows()).hasSize(25);
        assertThat(page1.counts().total()).isEqualTo(27);
        assertThat(rowIds(page1)).allMatch(id -> id.startsWith("HR-PAGE-"));
        assertThat(rowIds(list(perform(get("/component/HR").param("page", "2"), session)))).containsExactly("HR-PAGE-26", "HR-PAGE-27");
        assertThat(list(perform(get("/component/HR").param("page", "9"), session)).page()).isEqualTo(2);
        assertThat(list(perform(get("/component/HR").param("size", "50"), session)).rows()).hasSize(27);

        // sorting
        assertThat(rowIds(list(perform(get("/component/HR").param("sort", "id").param("dir", "desc"), session))))
                .startsWith("HR-PAGE-27", "HR-PAGE-26");
        List<String> statuses = list(perform(get("/component/HR").param("sort", "status").param("size", "50"), session))
                .rows().stream().map(ComponentControlsList.Row::status).toList();
        List<String> order = List.of("DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");
        assertThat(statuses).isSortedAccordingTo(java.util.Comparator.comparing(order::indexOf));

        // search: Control ID, a person's name or address
        assertThat(rowIds(list(perform(get("/component/HR").param("q", "page-0"), session)))).hasSize(9);
        assertThat(rowIds(list(perform(get("/component/HR").param("q", "GHOST.person"), session)))).containsExactly("HR-PAGE-01");
        assertThat(list(perform(get("/component/HR").param("q", "ac-op"), session)).matching()).isEqualTo(27);

        // the people: names from the users table, the local part for an address without a user
        ComponentControlsList.Row withGhost = list(perform(get("/component/HR").param("q", "HR-PAGE-01"), session)).rows().get(0);
        assertThat(withGhost.facilitators()).isEqualTo("ac-fac, ghost.person");
        assertThat(withGhost.operators()).isEqualTo("ac-op");
        assertThat(withGhost.owners()).isEqualTo("ac-po");
        assertThat(withGhost.soqmLeads()).isEqualTo("ac-soqm");
        assertThat(perform(get("/component/HR").param("q", "HR-PAGE-01"), session).getResponse().getContentAsString())
                .contains(withGhost.operationDateText());
        assertThat(withGhost.operationDateText()).matches("\\d{2}\\.\\d{2}\\.\\d{4}");

        // status filter, alone and with the search
        ComponentControlsList.Result completed = list(perform(get("/component/HR").param("status", "COMPLETED"), session));
        assertThat(completed.rows()).isNotEmpty().allMatch(row -> row.status().equals("COMPLETED"));
        assertThat(completed.matching()).isEqualTo(completed.counts().completed());
        assertThat(list(perform(get("/component/HR").param("status", "COMPLETED").param("q", "nobody"), session)).matching()).isZero();
        // other components' controls never in the HR list
        assertThat(rowIds(list(perform(get("/component/HR").param("size", "50"), session)))).noneMatch(id -> !id.startsWith("HR-PAGE-"));
    }

    // ------------------------------------------------------------------ the card and the Controls list agree

    /** The card in the model and on the page: its five counters and where it leads. */
    private static void assertCard(MvcResult dashboard, long total, long inProgress, long inReview, long completed,
                                   long overdue) throws Exception {
        assertThat(dashboard.getResponse().getStatus()).isEqualTo(200);
        ViewController.ComponentSummary summary =
                (ViewController.ComponentSummary) dashboard.getModelAndView().getModel().get("kdnSummary");
        assertThat(List.of(summary.code(), summary.name())).containsExactly("KDN", "KDN controls");
        assertThat(List.of(summary.total(), summary.inProgress(), summary.inReview(), summary.completed(), summary.overdue()))
                .containsExactly(total, inProgress, inReview, completed, overdue);
        assertThat(dashboard.getModelAndView().getModel()).containsEntry("kdnHref", "/component/KDN");
        String pane = actionCentre(dashboard);
        int at = pane.indexOf("href=\"/component/KDN\"");
        assertThat(at).as("KDN card").isPositive();
        String card = pane.substring(pane.lastIndexOf("<a ", at), pane.indexOf("</a>", at));
        assertThat(card).contains("<span class=\"ac-code\">KDN</span>", "View all KDN controls",
                "<dt>Total</dt><dd>" + total + "</dd>", "<dt>In progress</dt><dd>" + inProgress + "</dd>",
                "<dt>In review</dt><dd>" + inReview + "</dd>", "<dt>Completed</dt><dd>" + completed + "</dd>",
                "<dt>Overdue</dt><dd>" + overdue + "</dd>");
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

        // The card leads to /component/KDN: the same controls, the same five numbers, and each counter's link
        // lists exactly its number
        ComponentControlsList.Result kdnList = list(perform(get((String) dashboard.getModelAndView().getModel().get("kdnHref")), session));
        assertThat(rowIds(kdnList)).containsExactlyInAnyOrderElementsOf(kdnIds);
        ComponentControlsList.Counts counts = kdnList.counts();
        assertThat(List.of(counts.total(), counts.inProgress(), counts.inReview(), counts.completed(), counts.overdue()))
                .containsExactly(card.total(), card.inProgress(), card.inReview(), card.completed(), card.overdue());
        Map<String, Long> links = new java.util.LinkedHashMap<>();
        links.put("", card.total());
        links.put("IN_PROGRESS", card.inProgress());
        links.put("IN_REVIEW", card.inReview());
        links.put("COMPLETED", card.completed());
        links.put("OVERDUE", card.overdue());
        for (Map.Entry<String, Long> link : links.entrySet()) {
            assertThat((long) list(perform(get(kdnList.statusHref(link.getKey())), session)).matching())
                    .as("%s -> %s", link.getKey(), kdnList.statusHref(link.getKey())).isEqualTo(link.getValue());
        }
    }

    private static ComponentControlsList.Result list(MvcResult page) {
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getModelAndView().getViewName()).isEqualTo("component-controls");
        return (ComponentControlsList.Result) page.getModelAndView().getModel().get("list");
    }

    private static List<String> rowIds(ComponentControlsList.Result list) {
        return list.rows().stream().map(ComponentControlsList.Row::controlId).toList();
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
        control(controlId, status, deadline, facilitatorToo, "GOV", null);
    }

    private void control(String controlId, String status, LocalDate deadline, User facilitatorToo, String component,
                         String otherFacilitator) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent(component);
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
        assignment.setFacilitator(facilitator.getMail() + (facilitatorToo != null ? "," + facilitatorToo.getMail() : "")
                + (otherFacilitator != null ? "," + otherFacilitator : ""));
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
