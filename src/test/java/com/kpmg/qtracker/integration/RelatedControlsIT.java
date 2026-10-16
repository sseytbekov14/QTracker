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
import org.springframework.web.util.HtmlUtils;

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
 * Other Related Controls (RelatedControls, business decision of 2026-10-15): SoQM Admin Team link other controls from a
 * control's Details tab, each chip opening that control. Saved with Details as relatedControlIds, kept by the controls'
 * ids (a Rename ID keeps them), shown with the current Control ID; nobody else sees or changes them.
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

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("soqm-" + s, AccessLevel.SOQM, AccessScope.ALL);
        fac = saveUser("fac-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        op = saveUser("op-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        po = saveUser("po-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        readOnlyAll = saveUser("ro-all-" + s, AccessLevel.READ_ONLY, AccessScope.ALL);
        editAll = saveUser("edit-all-" + s, AccessLevel.PARTICIPANT, AccessScope.ALL);
    }

    @Test
    void soqm_linksControls_inTheChosenOrder_andThePageShowsEachAsALinkToIt() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control first = control("COMPLETED", "EP");
        Control second = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);

        assertThat(page(control, session)).contains("id=\"relatedControlsField\"")
                .contains("No related controls")
                .doesNotContain("class=\"sw-chip rc-chip\"");

        assertThat(save(control, session, List.of(second.getId(), first.getId()))).isEqualTo(200);
        assertThat(relatedControls.ids(control.getId())).containsExactly(second.getId(), first.getId());

        String page = page(control, session);
        int secondAt = page.indexOf("href=\"/view-control/" + second.getId() + "\"");
        int firstAt = page.indexOf("href=\"/view-control/" + first.getId() + "\"");
        assertThat(secondAt).isPositive();
        assertThat(firstAt).isGreaterThan(secondAt);
        assertThat(page).contains(HtmlUtils.htmlEscape(second.getControlId()))
                .contains(HtmlUtils.htmlEscape(first.getControlId()))
                .contains("Completed")
                .contains("2 controls")
                .contains("/js/related-controls.js?v=");

        // The Details API names them too, for SoQM Admin Team
        JsonNode details = json(get("/api/control-details").param("controlId", String.valueOf(control.getId())), session);
        assertThat(details.path("relatedControls").get(0).asText()).isEqualTo(second.getControlId());
        assertThat(details.path("relatedControls").get(1).asText()).isEqualTo(first.getControlId());
        assertThat(details.has("relatedControlIds")).isFalse();
    }

    @Test
    void aRenameId_keepsTheLink_andThePageShowsTheNewId() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control related = control("IN_PROGRESS", "EP");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, List.of(related.getId()))).isEqualTo(200);

        Control renamed = controlRepository.findById(related.getId()).orElseThrow();
        renamed.setControlId(related.getControlId() + "-RENAMED");
        controlRepository.save(renamed);

        assertThat(page(control, session)).contains(HtmlUtils.htmlEscape(renamed.getControlId()))
                .contains("href=\"/view-control/" + related.getId() + "\"");
    }

    @Test
    void aSave_nullKeepsThem_anEmptyListClearsThem_aRepeatIsKeptOnce() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control a = control("IN_PROGRESS", "EP");
        Control b = control("IN_PROGRESS", "EP");
        MockHttpSession session = login(soqm);

        assertThat(save(control, session, List.of(a.getId(), b.getId(), a.getId()))).isEqualTo(200);
        assertThat(relatedControls.ids(control.getId())).containsExactly(a.getId(), b.getId());

        // A save that does not send them (another field, or the page outside edit mode) keeps them
        assertThat(saveRaw(control, session, "{\"processName\":\"Payroll\",\"relatedControlIds\":null}")).isEqualTo(200);
        assertThat(relatedControls.ids(control.getId())).containsExactly(a.getId(), b.getId());

        assertThat(save(control, session, List.of())).isEqualTo(200);
        assertThat(relatedControls.ids(control.getId())).isEmpty();
    }

    @Test
    void aControlItself_orOneThatIsGone_isRefused_andNothingIsSaved() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control other = control("IN_PROGRESS", "EP");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, List.of(other.getId()))).isEqualTo(200);

        MvcResult itself = saveResult(control, session, "{\"processName\":\"Not saved\",\"relatedControlIds\":["
                + other.getId() + "," + control.getId() + "]}");
        assertThat(itself.getResponse().getStatus()).isEqualTo(400);
        assertThat(itself.getResponse().getContentAsString())
                .isEqualTo("VALIDATION_ERROR: Other Related Controls: a control cannot be related to itself");

        MvcResult gone = saveResult(control, session, "{\"relatedControlIds\":[" + (other.getId() + 100000) + "]}");
        assertThat(gone.getResponse().getStatus()).isEqualTo(400);
        assertThat(gone.getResponse().getContentAsString())
                .contains("Other Related Controls: a chosen control no longer exists. Reload the page and choose again.");

        assertThat(relatedControls.ids(control.getId())).containsExactly(other.getId());
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getProcessName()).isNull();
    }

    @Test
    void anyoneElse_seesThem_butCannotChangeThem_norGetTheList() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control linked = control("IN_PROGRESS", "EP");
        Control other = control("IN_PROGRESS", "EP");
        assertThat(save(control, login(soqm), List.of(linked.getId()))).isEqualTo(200);

        // The Facilitator may save the steps; the links sent along are kept as stored
        assertThat(saveRaw(control, login(fac), "{\"controlStepsPerformed\":\"Steps\",\"relatedControlIds\":["
                + other.getId() + "]}")).isEqualTo(200);
        assertThat(relatedControls.ids(control.getId())).containsExactly(linked.getId());

        for (User user : List.of(fac, op, po, readOnlyAll, editAll)) {
            MockHttpSession session = login(user);
            assertThat(perform(get("/api/controls/{id}/related-options", control.getId()), session)
                    .getResponse().getStatus()).as(user.getMail()).isEqualTo(403);
            JsonNode details = json(get("/api/control-details").param("controlId", String.valueOf(control.getId())), session);
            assertThat(details.path("relatedControls").get(0).asText()).as(user.getMail()).isEqualTo(linked.getControlId());
            assertThat(page(control, session)).as(user.getMail())
                    .contains(HtmlUtils.htmlEscape(linked.getControlId()))
                    .doesNotContain("id=\"relatedControlsSearchInput\"");
        }
    }

    @Test
    void aLinkedControl_isALink_forWhoeverMayOpenIt_itsIdAloneForAnyoneElse() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control theirs = control("IN_PROGRESS", "EP");
        Control notTheirs = control("IN_PROGRESS", "EP");
        ControlAssignment elsewhere = assignmentRepository.findByControlId(notTheirs.getId()).orElseThrow();
        elsewhere.setFacilitator(soqm.getMail());
        elsewhere.setControlOperator(soqm.getMail());
        elsewhere.setProcessOwner(soqm.getMail());
        assignmentRepository.save(elsewhere);
        assertThat(save(control, login(soqm), List.of(theirs.getId(), notTheirs.getId()))).isEqualTo(200);

        String forFacilitator = page(control, login(fac));
        assertThat(forFacilitator).contains("href=\"/view-control/" + theirs.getId() + "\"")
                .doesNotContain("href=\"/view-control/" + notTheirs.getId() + "\"")
                .contains(HtmlUtils.htmlEscape(notTheirs.getControlId()))
                .contains("rc-chip-link is-plain");

        for (User user : List.of(soqm, editAll, readOnlyAll)) {
            assertThat(page(control, login(user))).as(user.getMail())
                    .contains("href=\"/view-control/" + theirs.getId() + "\"")
                    .contains("href=\"/view-control/" + notTheirs.getId() + "\"")
                    .doesNotContain("rc-chip-link is-plain");
        }
    }

    @Test
    void theList_offersEveryOtherControl_byControlId_withItsStatus() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control draft = control("DRAFT", "EP");
        Control superseded = control("COMPLETED", "EP");
        superseded.setControlStatus("SUPERSEDED");
        superseded.setControlDescription("  A   long\ndescription  ");
        controlRepository.save(superseded);

        JsonNode options = json(get("/api/controls/{id}/related-options", control.getId()), login(soqm));
        List<Long> ids = new ArrayList<>();
        options.forEach(option -> ids.add(option.path("id").asLong()));
        assertThat(ids).doesNotContain(control.getId()).contains(draft.getId(), superseded.getId());
        List<String> controlIds = new ArrayList<>();
        options.forEach(option -> controlIds.add(option.path("controlId").asText().toLowerCase()));
        assertThat(controlIds).isSorted();

        JsonNode supersededOption = option(options, superseded.getId());
        assertThat(supersededOption.path("status").asText()).isEqualTo("Completed · Superseded");
        assertThat(supersededOption.path("description").asText()).isEqualTo("A long description");
        assertThat(option(options, draft.getId()).path("status").asText()).isEqualTo("Draft");

        assertThat(perform(get("/api/controls/{id}/related-options", 987654321L), login(soqm))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theChangelog_namesTheLinkedControls() throws Exception {
        Control control = control("IN_PROGRESS", "HR");
        Control a = control("IN_PROGRESS", "EP");
        Control b = control("IN_PROGRESS", "EP");
        MockHttpSession session = login(soqm);
        assertThat(save(control, session, List.of(a.getId()))).isEqualTo(200);
        assertThat(save(control, session, List.of(b.getId(), a.getId()))).isEqualTo(200);
        // The same links again: nothing to record
        assertThat(save(control, session, List.of(b.getId(), a.getId()))).isEqualTo(200);

        List<String[]> rows = rows(control, session, RelatedControls.LABEL);
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> assertThat(row).containsExactly("", a.getControlId()));
        assertThat(rows).anySatisfy(row -> assertThat(row)
                .containsExactly(a.getControlId(), b.getControlId() + ", " + a.getControlId()));

        // Everyone who sees the control reads them
        assertThat(rows(control, login(fac), RelatedControls.LABEL)).hasSize(2);
    }

    @Test
    void onACompletedControl_aChangeNeedsAReason() throws Exception {
        Control control = control("COMPLETED", "HR");
        Control other = control("IN_PROGRESS", "EP");
        MockHttpSession session = login(soqm);

        MvcResult noReason = saveResult(control, session, "{\"relatedControlIds\":[" + other.getId() + "]}");
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(relatedControls.ids(control.getId())).isEmpty();

        assertThat(saveRaw(control, session, "{\"relatedControlIds\":[" + other.getId() + "],\"editReason\":\"Linked\"}"))
                .isEqualTo(200);
        assertThat(relatedControls.ids(control.getId())).containsExactly(other.getId());
    }

    @Test
    void theEarlierText_isShownUnderTheLinks_andAnEmptyOneIsNot() throws Exception {
        Control withText = control("IN_PROGRESS", "HR");
        ControlDetails details = detailsRepository.findByControlId(withText.getId()).orElseThrow();
        details.setOtherRelatedControls("HR-12, see the SharePoint list");
        detailsRepository.save(details);
        Control withoutText = control("IN_PROGRESS", "HR");
        MockHttpSession session = login(soqm);

        assertThat(page(withText, session)).contains("Earlier text (not linked)")
                .contains("value=\"HR-12, see the SharePoint list\"");
        assertThat(page(withoutText, session)).doesNotContain("Earlier text (not linked)")
                .doesNotContain("name=\"otherRelatedControls\"");

        // Cleared on Save: the Changelog names the earlier text
        assertThat(saveRaw(withText, session, "{\"otherRelatedControls\":\"\"}")).isEqualTo(200);
        assertThat(rows(withText, session, RelatedControls.EARLIER_TEXT_LABEL))
                .containsExactly(new String[]{"HR-12, see the SharePoint list", ""});
        assertThat(page(withText, session)).doesNotContain("Earlier text (not linked)");
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode option(JsonNode options, Long id) {
        for (JsonNode option : options) {
            if (option.path("id").asLong() == id) {
                return option;
            }
        }
        throw new AssertionError("no option " + id);
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

    private int save(Control control, MockHttpSession session, List<Long> ids) throws Exception {
        return saveRaw(control, session, "{\"relatedControlIds\":" + objectMapper.writeValueAsString(ids) + "}");
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
