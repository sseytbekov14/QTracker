package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.RelatedControlRef;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.RelatedControlRefRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.RelatedControls;
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
 * Other Related Controls (RelatedControls, business decision of 2026-10-19): SoQM Admin Team choose Control IDs of one
 * list for every control (related_control_ref) on a control's Details tab. Saved with Details as otherRelatedControls
 * ("A; B"), checked against the active list (an ID the control already had stays, "not in list"), plain text
 * everywhere: no link to any control. Everyone who sees the control sees them; nobody else changes them. At most
 * related-controls.max-per-control (3 here, 50 by default).
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:related-controls-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "related-controls.max-per-control=3",
        "file.upload.dir=target/it-uploads-related"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class RelatedControlsIT {

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
    private RelatedControlRefRepository refRepository;
    @Autowired
    private RelatedControls relatedControls;
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
    private User readOnlyAll;
    private User editAll;
    private User kdn;
    private String a;
    private String b;
    private String c;
    private String d;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("soqm-" + s, AccessLevel.SOQM, AccessScope.ALL);
        fac = saveUser("fac-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        op = saveUser("op-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        po = saveUser("po-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        readOnlyAll = saveUser("ro-all-" + s, AccessLevel.READ_ONLY, AccessScope.ALL);
        editAll = saveUser("edit-all-" + s, AccessLevel.PARTICIPANT, AccessScope.ALL);
        kdn = saveUser("kdn-" + s, AccessLevel.READ_ONLY, AccessScope.KDN);
        // Four made-up Control IDs of the list, different in every test
        a = "REF-" + s + "-A/FY27/Central";
        b = "REF-" + s + "-B/FY27/Central";
        c = "REF-" + s + "-C/FY27";
        d = "REF-" + s + "-D";
        refRepository.saveAll(List.of(new RelatedControlRef(a), new RelatedControlRef(b), new RelatedControlRef(c),
                new RelatedControlRef(d)));
    }

    @Test
    void soqm_choosesIdsOfTheList_inTheirOrder_shownAsPlainText() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);

        assertThat(field(page(control, session))).contains("Not selected")
                .contains("data-source-url=\"/api/related-controls\"")
                .contains("No controls in the list yet, contact SoQM Admin Team")
                .contains("data-max=\"3\"")
                .contains("bi-link-45deg")
                .doesNotContain("class=\"sw-chip mv-chip");

        assertThat(save(control, session, b + "; " + a)).isEqualTo(200);
        assertThat(stored(control)).isEqualTo(b + "; " + a);

        String field = field(page(control, session));
        int bAt = field.indexOf("data-value=\"" + b + "\"");
        int aAt = field.indexOf("data-value=\"" + a + "\"");
        assertThat(bAt).isPositive();
        assertThat(aAt).isGreaterThan(bAt);
        assertThat(field).contains("2 controls").doesNotContain("not in list").doesNotContain("<a ")
                .doesNotContain("href=");
        assertThat(page(control, session)).contains("/js/multi-value.js?v=");

        // The Details API gives them as stored, to everyone who sees the control
        JsonNode details = json(get("/api/control-details").param("controlId", String.valueOf(control.getId())), session);
        assertThat(details.path("otherRelatedControls").asText()).isEqualTo(b + "; " + a);
        assertThat(details.has("relatedControlIds")).isFalse();
        assertThat(details.has("relatedControls")).isFalse();
    }

    @Test
    void aValueInAnotherCaseOrWithSpaces_isStoredAsTheListWritesIt() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        assertThat(save(control, login(soqm), "  " + a.toLowerCase() + " ;" + b.replace("-", " - ") + ";;"))
                .isEqualTo(200);
        assertThat(stored(control)).isEqualTo(a + "; " + b);
    }

    @Test
    void anIdNotInTheList_aRepeat_orTooMany_isRefused_andNothingIsSaved() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, a)).isEqualTo(200);

        MvcResult unknown = saveResult(control, session, "{\"processName\":\"Not saved\",\"otherRelatedControls\":\""
                + a + "; HR-NOT-IN-LIST-1\"}");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo("VALIDATION_ERROR: Other Related Controls: "
                + "HR-NOT-IN-LIST-1 is not in the list. Reload the page and choose again.");

        MvcResult twice = saveResult(control, session, "{\"otherRelatedControls\":\"" + b + "; " + b.toLowerCase() + "\"}");
        assertThat(twice.getResponse().getStatus()).isEqualTo(400);
        assertThat(twice.getResponse().getContentAsString())
                .isEqualTo("VALIDATION_ERROR: Other Related Controls: " + b.toLowerCase() + " is given twice");

        MvcResult tooMany = saveResult(control, session, "{\"otherRelatedControls\":\"" + String.join("; ", a, b, c, d) + "\"}");
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooMany.getResponse().getContentAsString())
                .isEqualTo("VALIDATION_ERROR: Other Related Controls: at most 3 controls");
        assertThat(stored(control)).isEqualTo(a);
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getProcessName()).isNull();

        assertThat(save(control, session, String.join("; ", a, b, c))).isEqualTo(200);
        assertThat(stored(control)).isEqualTo(String.join("; ", a, b, c));
    }

    @Test
    void anIdTheListNoLongerHas_staysMarkedNotInList_untilRemoved() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, a + "; " + b)).isEqualTo(200);
        RelatedControlRef gone = refRepository.findAll().stream().filter(ref -> ref.getControlId().equals(a))
                .findFirst().orElseThrow();
        gone.setActive(false);
        refRepository.save(gone);

        String field = field(page(control, session));
        assertThat(field).contains("not in list").contains("data-not-in-list=\"true\"");
        assertThat(relatedControls.of(control.getId())).extracting("notInList").containsExactly(true, false);

        // Kept by a save that still has it (in any case), another one added
        assertThat(save(control, session, a.toLowerCase() + "; " + b + "; " + c)).isEqualTo(200);
        assertThat(stored(control)).isEqualTo(a + "; " + b + "; " + c);
        // Once removed it cannot come back
        assertThat(save(control, session, b)).isEqualTo(200);
        assertThat(saveRaw(control, session, "{\"otherRelatedControls\":\"" + a + "\"}")).isEqualTo(400);
        assertThat(stored(control)).isEqualTo(b);
    }

    @Test
    void aSave_nullKeepsThem_anEmptyValueClearsThem_evenWithAnEmptyList() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, a + "; " + b)).isEqualTo(200);

        // A save that does not send them (another field, or the page outside edit mode) keeps them
        assertThat(saveRaw(control, session, "{\"processName\":\"Payroll\",\"otherRelatedControls\":null}")).isEqualTo(200);
        assertThat(stored(control)).isEqualTo(a + "; " + b);

        refRepository.deleteAll();
        assertThat(save(control, session, "")).isEqualTo(200);
        assertThat(stored(control)).isEmpty();
        assertThat(field(page(control, session))).contains("Not selected");
    }

    @Test
    void anyoneElse_seesThem_butCannotChangeThem() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        control.setControlId("KDN-" + control.getControlId());
        controlRepository.save(control);
        assertThat(save(control, login(soqm), a)).isEqualTo(200);

        // The Facilitator may save the steps; the IDs sent along are kept as stored
        assertThat(saveRaw(control, login(fac), "{\"controlStepsPerformed\":\"Steps\",\"otherRelatedControls\":\""
                + b + "\"}")).isEqualTo(200);
        assertThat(stored(control)).isEqualTo(a);

        for (User user : List.of(fac, op, po, readOnlyAll, editAll, kdn)) {
            MockHttpSession session = login(user);
            JsonNode details = json(get("/api/control-details").param("controlId", String.valueOf(control.getId())), session);
            assertThat(details.path("otherRelatedControls").asText()).as(user.getMail()).isEqualTo(a);
            assertThat(field(page(control, session))).as(user.getMail())
                    .contains("data-value=\"" + a + "\"")
                    .contains("data-editable=\"false\"")
                    .doesNotContain("relatedControlsSearchInput")
                    .doesNotContain("href=");
        }
    }

    @Test
    void theChangelog_givesTheListBeforeAndAfter() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, a)).isEqualTo(200);
        assertThat(save(control, session, b + ";" + a)).isEqualTo(200);
        // The same IDs again, written otherwise: nothing to record
        assertThat(save(control, session, " " + b + " ; " + a.toLowerCase())).isEqualTo(200);

        List<String[]> rows = rows(control, session, RelatedControls.LABEL);
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> assertThat(row).containsExactly("", a));
        assertThat(rows).anySatisfy(row -> assertThat(row).containsExactly(a, b + "; " + a));

        // Everyone who sees the control reads them
        assertThat(rows(control, login(fac), RelatedControls.LABEL)).hasSize(2);
    }

    @Test
    void onACompletedControl_aChangeNeedsAReason() throws Exception {
        Control control = control("COMPLETED", "HR");
        MockHttpSession session = login(soqm);

        MvcResult noReason = saveResult(control, session, "{\"otherRelatedControls\":\"" + a + "\"}");
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(stored(control)).isNull();

        assertThat(saveRaw(control, session, "{\"otherRelatedControls\":\"" + a + "\",\"editReason\":\"Related\"}"))
                .isEqualTo(200);
        assertThat(stored(control)).isEqualTo(a);
    }

    @Test
    void theEarlierWaysOfTheLinks_areGone() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);

        // Sent by an older page: accepted and left out, no Changelog row
        assertThat(saveRaw(control, session, "{\"relatedControlIds\":[1,2]}")).isEqualTo(200);
        assertThat(stored(control)).isNull();
        assertThat(rows(control, session, RelatedControls.LABEL)).isEmpty();
        assertThat(perform(get("/api/controls/{id}/related-options", control.getId()), session).getResponse().getStatus())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private String stored(Control control) {
        return detailsRepository.findByControlId(control.getId()).orElseThrow().getOtherRelatedControls();
    }

    /** The Other Related Controls field of the page, up to the next column. */
    private static String field(String page) {
        int start = page.indexOf("id=\"relatedControlsField\"");
        assertThat(start).as("the field is on the page").isPositive();
        int end = page.indexOf("<div class=\"col-md-6 mb-3\">", start);
        return page.substring(start, end > start ? end : page.length());
    }

    /** {old, new} of each Changelog row of the field, as the session gets them. */
    private List<String[]> rows(Control control, MockHttpSession session, String field) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (JsonNode entry : json(get("/api/controls/{id}/changelog", control.getId()), session)) {
            for (JsonNode change : entry.path("fieldChanges")) {
                if (field.equals(change.path("field").asText())) {
                    rows.add(new String[]{change.path("oldValue").asText(), change.path("newValue").asText()});
                }
            }
        }
        return rows;
    }

    private String page(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/view-control/{id}", control.getId()), session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private int save(Control control, MockHttpSession session, String values) throws Exception {
        return saveRaw(control, session, "{\"otherRelatedControls\":" + objectMapper.writeValueAsString(values) + "}");
    }

    private int saveRaw(Control control, MockHttpSession session, String fieldsJson) throws Exception {
        return saveResult(control, session, fieldsJson).getResponse().getStatus();
    }

    /** A raw body; controlId is added in front. */
    private MvcResult saveResult(Control control, MockHttpSession session, String fieldsJson) throws Exception {
        String body = "{\"controlId\":" + control.getId() + "," + fieldsJson.substring(1);
        return perform(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), session);
    }

    private JsonNode json(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult result = perform(request, session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Facilitator fac, Control Operator op, Process Owner po, SoQM soqm. */
    private Control control(String status, String component) {
        Control control = new Control();
        control.setControlId(component + "-REL-" + UUID.randomUUID().toString().substring(0, 8) + "/FY27/Central/OCT");
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent(component);
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
        assignment.setControlOperationDate(LocalDate.now().plusDays(10));
        assignmentRepository.save(assignment);
        return control;
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setMail(name + "@related.test");
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
        String address = "10.11." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
