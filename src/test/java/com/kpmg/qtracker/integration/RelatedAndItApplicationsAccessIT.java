package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Other Related Controls and IT Applications on the Details tab (business decision of 2026-10-16): everyone who sees the
 * control sees them - on View Control, from /api/control-details and in the Changelog; only SoQM Admin Team change
 * them (the Details save takes them with canEditAll only) and only they get the list of controls to choose from.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:related-it-access-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "notifications.scheduler.enabled=false",
        "file.upload.dir=target/it-uploads-related-access"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class RelatedAndItApplicationsAccessIT {

    private static final String PASSWORD = "Test#123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private ControlDetailsRepository detailsRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    private static int addressCount;

    private User soqm;
    private User fac;
    private User op;
    private User po;
    private User shared;
    private User readOnlyAll;
    private User editAll;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("soqm-" + s, AccessLevel.SOQM, AccessScope.ALL);
        fac = saveUser("fac-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        op = saveUser("op-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        po = saveUser("po-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        shared = saveUser("shared-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        readOnlyAll = saveUser("ro-all-" + s, AccessLevel.READ_ONLY, AccessScope.ALL);
        editAll = saveUser("edit-all-" + s, AccessLevel.PARTICIPANT, AccessScope.ALL);
    }

    private List<User> everyoneButSoqm() {
        return List.of(fac, op, po, shared, readOnlyAll, editAll);
    }

    @Test
    void viewControl_everyoneHasBothFields_onlySoqmGetsThePicker() throws Exception {
        Control control = control("IN_PROGRESS");

        assertThat(page(control, login(soqm)))
                .contains("id=\"relatedDetailsRow\"")
                .contains("id=\"relatedControlsField\"")
                .contains("id=\"relatedControlsSearchInput\"")
                .contains("/js/multi-value.js?v=")
                .contains("data-editable=\"true\"")
                // No input for the free text the field held before the links
                .doesNotContain("name=\"otherRelatedControls\"")
                .contains("id=\"itApplicationsField\"");

        for (User reader : everyoneButSoqm()) {
            assertThat(page(control, login(reader))).as(reader.getMail())
                    .contains("id=\"relatedDetailsRow\"")
                    .contains("id=\"relatedControlsField\"")
                    .doesNotContain("name=\"otherRelatedControls\"")
                    .contains("id=\"itApplicationsField\"")
                    // Nothing to choose with
                    .doesNotContain("id=\"relatedControlsSearchInput\"")
                    .doesNotContain("data-editable=\"true\"");
        }
    }

    @Test
    void detailsApi_givesBothValuesToEveryoneWhoSeesTheControl() throws Exception {
        Control control = control("IN_PROGRESS");

        for (User reader : List.of(soqm, fac, op, po, shared, readOnlyAll, editAll)) {
            JsonNode details = json(get("/api/control-details").param("controlId", String.valueOf(control.getId())),
                    login(reader));
            assertThat(details.path("otherRelatedControls").asText()).as(reader.getMail()).isEqualTo("HR-REF-1; EP-REF-2");
            assertThat(details.path("itApplications").asText()).as(reader.getMail()).isEqualTo("KICS");
            assertThat(details.has("relatedControls")).as(reader.getMail()).isFalse();
        }
    }

    @Test
    void changelog_showsTheirRowsToEveryone() throws Exception {
        Control control = control("IN_PROGRESS");
        MockHttpSession soqmSession = login(soqm);
        assertThat(save(control, soqmSession, "{\"processName\":\"Payroll\",\"itApplications\":\"CQS\"}"))
                .isEqualTo(200);
        assertThat(save(control, soqmSession, "{\"itApplications\":\"GLMS\"}")).isEqualTo(200);

        for (User reader : List.of(soqm, fac, op, po, shared, readOnlyAll, editAll)) {
            assertThat(editedFields(control, login(reader))).as(reader.getMail()).containsExactlyInAnyOrder(
                    List.of("Process Name", "IT Applications"),
                    List.of("IT Applications"));
        }
    }

    @Test
    void aSaveByAnyoneElse_neverChangesThem() throws Exception {
        Control control = control("IN_PROGRESS");

        // The Facilitator may save the steps; the two fields sent along are kept as stored
        assertThat(save(control, login(fac), "{\"controlStepsPerformed\":\"Steps\",\"itApplications\":\"OpenPD\","
                + "\"otherRelatedControls\":\"By the Facilitator\"}")).isEqualTo(200);
        // What their page sends: null for the fields they cannot change
        assertThat(save(control, login(fac), "{\"controlStepsPerformed\":\"Steps 2\",\"itApplications\":null,"
                + "\"otherRelatedControls\":null}")).isEqualTo(200);

        ControlDetails stored = detailsRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(stored.getControlStepsPerformed()).isEqualTo("Steps 2");
        assertThat(stored.getItApplications()).isEqualTo("KICS");
        assertThat(stored.getOtherRelatedControls()).isEqualTo("HR-REF-1; EP-REF-2");
    }

    @Test
    void theRuleOfThePicker_isSoqmAdminTeam() {
        assertThat(AccessPolicy.choosesRelatedControls(AccessPolicy.Subject.of(soqm))).isTrue();
        for (User reader : everyoneButSoqm()) {
            assertThat(AccessPolicy.choosesRelatedControls(AccessPolicy.Subject.of(reader))).as(reader.getMail()).isFalse();
        }
        assertThat(AccessPolicy.choosesRelatedControls(AccessPolicy.Subject.of(null))).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    /** The fields of each "Edit Control" entry of the Changelog as the session gets it. */
    private List<List<String>> editedFields(Control control, MockHttpSession session) throws Exception {
        List<List<String>> edits = new ArrayList<>();
        for (JsonNode entry : json(get("/api/controls/{id}/changelog", control.getId()), session)) {
            if (!entry.path("eventName").asText().startsWith("Edit Control")) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            entry.path("fieldChanges").forEach(change -> fields.add(change.path("field").asText()));
            edits.add(fields);
        }
        return edits;
    }

    private String page(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/view-control/{id}", control.getId()), session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    /** A raw body; controlId is added in front. */
    private int save(Control control, MockHttpSession session, String fieldsJson) throws Exception {
        String body = "{\"controlId\":" + control.getId() + "," + fieldsJson.substring(1);
        return perform(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), session).getResponse().getStatus();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult result = perform(request, session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** IN_PROGRESS by default: Facilitator fac, Control Operator op, Process Owner po, shared with shared. */
    private Control control(String status) {
        Control control = new Control();
        control.setControlId("HR-SOQMONLY-" + UUID.randomUUID().toString().substring(0, 8));
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("HR");
        control.setOperatedBy("Finance");
        control.setPriority("High");
        control.setNonAuditServicesApplicability("No");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control.setSoqmYear(SoqmYear.current(LocalDate.now()));
        control.setCreatedBy(soqm);
        control.setCreatedAt(LocalDateTime.now());
        control = controlRepository.save(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(fac.getMail());
        assignment.setControlOperator(op.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(po.getMail());
        assignment.setControlSharedWith(shared.getMail());
        assignment.setControlOperationDate(LocalDate.now().plusDays(10));
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setProcessActivities("Process activities");
        details.setItApplications("KICS");
        details.setOtherRelatedControls("HR-REF-1; EP-REF-2");
        detailsRepository.save(details);
        return control;
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setMail(name + "@related-access.test");
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(false);
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
        return mockMvc.perform(request.with(ownAddress()).session(session)).andReturn();
    }

    /** RateLimitingFilter counts logins per address: every request comes from an address of its own. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor ownAddress() {
        String address = "10.13." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
