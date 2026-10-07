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
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.ControlStepsFields;
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

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Control Steps Performed as one field or two (ControlStepsFields), through the real dev chain: who writes
 * which field on which step, what Submit to SoQM requires, and that returns and reassignment keep both
 * values while the history names every author.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:steps-split-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads-steps"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class StepsFieldSplitIT {

    private static final String PASSWORD = "Test#123";
    private static final String STEPS = "controlStepsPerformed";
    private static final String REVIEW = "controlOperatorReview";

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
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    private static int addressCount;

    private User fac;
    private User fac2;
    private User op;
    private User op2;
    private User soqm;
    private User po;
    private User outsider;
    private User sharedUser;
    private User readOnly;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        fac = saveUser("fac-" + s, AccessLevel.PARTICIPANT);
        fac2 = saveUser("fac2-" + s, AccessLevel.PARTICIPANT);
        op = saveUser("op-" + s, AccessLevel.PARTICIPANT);
        op2 = saveUser("op2-" + s, AccessLevel.PARTICIPANT);
        soqm = saveUser("soqm-" + s, AccessLevel.SOQM);
        po = saveUser("po-" + s, AccessLevel.PARTICIPANT);
        outsider = saveUser("outsider-" + s, AccessLevel.PARTICIPANT);
        sharedUser = saveUser("shared-" + s, AccessLevel.PARTICIPANT);
        readOnly = saveUser("ro-" + s, AccessLevel.READ_ONLY);
    }

    // ------------------------------------------------------------------ different people: two fields

    @Test
    void split_facilitatorWritesTheStepsFieldInProgress_onlyThere() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail(), op.getMail(), null, null);
        MockHttpSession facSession = login(fac);

        assertThat(save(control, facSession, STEPS, "Facilitator steps")).isEqualTo(200);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Facilitator steps");

        MvcResult refused = saveResult(control, facSession, REVIEW, "Facilitator writes the review");
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains(ControlStepsFields.OPERATOR_PROGRAM_LABEL + " is filled in by the Control Operator");
        assertThat(details(control).getControlOperatorReview()).isNull();

        setStatus(control, "REVIEW");
        assertThat(save(control, facSession, STEPS, "Facilitator again in Review")).isEqualTo(403);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Facilitator steps");
    }

    @Test
    void split_operatorWritesTheOwnFieldInReview_notTheFacilitators() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail(), op.getMail(), "Facilitator steps", null);
        MockHttpSession opSession = login(op);

        assertThat(save(control, opSession, REVIEW, "Too early")).isEqualTo(403);

        setStatus(control, "REVIEW");
        assertThat(save(control, opSession, REVIEW, "Operator review")).isEqualTo(200);
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Operator review");

        MvcResult refused = saveResult(control, opSession, STEPS, "Operator rewrites the steps");
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains(ControlStepsFields.STEPS_LABEL + " is filled in by the Facilitator while the control is In Progress");
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Facilitator steps");

        // The page sends null for the fields the user cannot change: that is no change
        assertThat(save(control, opSession, "{\"" + STEPS + "\":null,\"" + REVIEW + "\":\"Operator review 2\"}")).isEqualTo(200);
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Operator review 2");

        setStatus(control, "SOQM_HEAD_REVIEW");
        assertThat(save(control, opSession, REVIEW, "After submit")).isEqualTo(403);
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Operator review 2");
    }

    @Test
    void others_andReadOnly_get403_onBothFields() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail() + ";" + readOnly.getMail(), "Steps", "Review");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlSharedWith(sharedUser.getMail());
        assignmentRepository.save(assignment);

        for (User who : List.of(outsider, sharedUser, readOnly, po, fac)) {
            MockHttpSession session = login(who);
            assertThat(save(control, session, STEPS, "by " + who.getMail())).as(who.getMail() + " steps").isEqualTo(403);
            assertThat(save(control, session, REVIEW, "by " + who.getMail())).as(who.getMail() + " review").isEqualTo(403);
        }
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Steps");
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Review");
    }

    @Test
    void everyoneWhoSeesTheControl_readsBothFields() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "Review");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlSharedWith(sharedUser.getMail());
        assignmentRepository.save(assignment);

        for (User who : List.of(fac, op, soqm, po, sharedUser)) {
            JsonNode read = json(get("/api/control-details").param("controlId", String.valueOf(control.getId())), login(who));
            assertThat(read.path(STEPS).asText()).as(who.getMail()).isEqualTo("Steps");
            assertThat(read.path(REVIEW).asText()).as(who.getMail()).isEqualTo("Review");
        }
    }

    @Test
    void severalPeopleInASlot_anyOfThemWrites_andTheHistoryNamesEach() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail() + "; " + fac2.getMail(), op.getMail() + "," + op2.getMail(), null, null);

        assertThat(save(control, login(fac2), STEPS, "Steps by the second Facilitator")).isEqualTo(200);
        setStatus(control, "REVIEW");
        assertThat(save(control, login(op2), REVIEW, "Review by the second Operator")).isEqualTo(200);
        assertThat(save(control, login(op), REVIEW, "Review by the first Operator")).isEqualTo(200);

        List<String> authors = fieldAuthors(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL);
        assertThat(authors).containsExactlyInAnyOrder(op.getMail(), op2.getMail());
        assertThat(fieldAuthors(control, ControlStepsFields.STEPS_LABEL)).containsExactly(fac2.getMail());
    }

    // ------------------------------------------------------------------ one person: one field

    @Test
    void onePersonInBothSlots_writesTheOneField_atBothSteps() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail() + ";" + fac2.getMail(), " " + fac.getMail().toUpperCase() + " ", null, null);
        MockHttpSession facSession = login(fac);

        assertThat(save(control, facSession, STEPS, "Steps as Facilitator")).isEqualTo(200);
        setStatus(control, "REVIEW");
        assertThat(save(control, facSession, STEPS, "Steps as Operator")).isEqualTo(200);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Steps as Operator");

        MvcResult refused = saveResult(control, facSession, REVIEW, "A second field");
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("is used only when the Facilitator and the Control Operator are different people");
        assertThat(details(control).getControlOperatorReview()).isNull();

        // The other Facilitator is not the Operator: the Review step is not theirs
        assertThat(save(control, login(fac2), STEPS, "Not my step")).isEqualTo(403);

        JsonNode permissions = json(get("/api/permissions/{id}", control.getId()), facSession).path("permissions");
        assertThat(permissions.path("stepsSplit").asBoolean()).isFalse();
        assertThat(permissions.path("canEditStepsPerformed").asBoolean()).isTrue();
        assertThat(permissions.path("canEditOperatorReview").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ Submit to SoQM

    @Test
    void submitToSoqm_needsTheOperatorField_onlyWhenFacilitatorAndOperatorDiffer() throws Exception {
        Control split = control("REVIEW", fac.getMail(), op.getMail(), "Steps", null);
        MockHttpSession opSession = login(op);

        MvcResult missing = perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(split.getId())), opSession);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString())
                .contains("Required field is missing: " + ControlStepsFields.OPERATOR_PROGRAM_LABEL);
        assertThat(status(split)).isEqualTo("REVIEW");

        // The other way in, as View Control's action buttons send it
        MvcResult viaAction = perform(post("/api/workflow/perform-action").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + split.getId() + ",\"action\":\"SUBMIT_FOR_SOQM\"}"), opSession);
        assertThat(viaAction.getResponse().getStatus()).isEqualTo(400);
        assertThat(viaAction.getResponse().getContentAsString()).contains(ControlStepsFields.OPERATOR_PROGRAM_LABEL);
        assertThat(status(split)).isEqualTo("REVIEW");

        assertThat(save(split, opSession, REVIEW, "Reviewed")).isEqualTo(200);
        assertThat(perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(split.getId())), opSession).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(split)).isEqualTo("SOQM_HEAD_REVIEW");

        Control onePerson = control("REVIEW", fac.getMail(), fac.getMail(), "Steps", null);
        assertThat(perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(onePerson.getId())), login(fac)).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(onePerson)).isEqualTo("SOQM_HEAD_REVIEW");
    }

    @Test
    void otherSteps_doNotNeedTheOperatorField() throws Exception {
        Control inProgress = control("IN_PROGRESS", fac.getMail(), op.getMail(), "Steps", null);
        assertThat(perform(post("/api/workflow/submit-to-control-operator").with(csrf().asHeader())
                .param("controlId", String.valueOf(inProgress.getId())), login(fac)).getResponse().getStatus()).isEqualTo(200);

        Control soqmReview = control("SOQM_HEAD_REVIEW", fac.getMail(), op.getMail(), "Steps", null);
        assertThat(perform(post("/api/workflow/submit-to-process-owner").with(csrf().asHeader())
                .param("controlId", String.valueOf(soqmReview.getId())), login(soqm)).getResponse().getStatus()).isEqualTo(200);

        Control review = control("REVIEW", fac.getMail(), op.getMail(), "Steps", null);
        assertThat(perform(post("/api/workflow/return-to-facilitator").with(csrf().asHeader())
                .param("controlId", String.valueOf(review.getId()))
                .param("comments", "Please add the sample"), login(op)).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ returns and reassignment

    @Test
    void returns_keepBothValues_andEveryRewriteIsAHistoryEntryWithItsAuthor() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps v1", "Review v1");
        MockHttpSession facSession = login(fac);
        MockHttpSession opSession = login(op);
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession poSession = login(po);

        // Control Operator -> Facilitator
        assertThat(workflow("/api/workflow/return-to-facilitator", control, opSession, "Steps are incomplete")).isEqualTo(200);
        assertThat(status(control)).isEqualTo("IN_PROGRESS");
        assertValues(control, "Steps v1", "Review v1");
        assertThat(save(control, facSession, STEPS, "Steps v2")).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-control-operator", control, facSession, null)).isEqualTo(200);
        assertValues(control, "Steps v2", "Review v1");
        assertThat(save(control, opSession, REVIEW, "Review v2")).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, opSession, null)).isEqualTo(200);

        // SoQM -> Control Operator
        assertThat(workflow("/api/workflow/return-to-operator", control, soqmSession, "Name the sample")).isEqualTo(200);
        assertThat(status(control)).isEqualTo("REVIEW");
        assertValues(control, "Steps v2", "Review v2");
        assertThat(save(control, opSession, REVIEW, "Review v3")).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, opSession, null)).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-process-owner", control, soqmSession, null)).isEqualTo(200);

        // Process Owner -> Control Operator
        assertThat(workflow("/api/workflow/return-to-operator", control, poSession, "The sample is too small")).isEqualTo(200);
        assertThat(status(control)).isEqualTo("REVIEW");
        assertValues(control, "Steps v2", "Review v3");
        assertThat(save(control, opSession, REVIEW, "Review v4")).isEqualTo(200);
        assertValues(control, "Steps v2", "Review v4");

        assertThat(fieldAuthors(control, ControlStepsFields.STEPS_LABEL)).containsExactly(fac.getMail());
        assertThat(fieldAuthors(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL))
                .containsExactly(op.getMail(), op.getMail(), op.getMail());
    }

    @Test
    void reassignment_keepsBothValues_andChangesOnlyWhatIsShownAndRequired() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "Review by the old Operator");
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession facSession = login(fac);

        // The Facilitator becomes the Operator too: one person, one field
        assertThat(reassignOperator(control, fac.getMail(), soqmSession)).isEqualTo(200);
        assertValues(control, "Steps", "Review by the old Operator");
        JsonNode permissions = json(get("/api/permissions/{id}", control.getId()), facSession).path("permissions");
        assertThat(permissions.path("stepsSplit").asBoolean()).isFalse();
        assertThat(save(control, facSession, STEPS, "Steps, rewritten by the new Operator")).isEqualTo(200);
        assertThat(save(control, facSession, REVIEW, "Hidden field")).isEqualTo(403);

        // Not required any more while it is one person, even when empty; the stored value stays
        ControlDetails cleared = details(control);
        cleared.setControlOperatorReview("");
        detailsRepository.save(cleared);
        assertThat(reassignOperator(control, op.getMail(), soqmSession)).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, login(op), null)).isEqualTo(400);
        assertThat(reassignOperator(control, fac.getMail(), soqmSession)).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, facSession, null)).isEqualTo(200);
        assertThat(status(control)).isEqualTo("SOQM_HEAD_REVIEW");

        // Back to different people: the value written before comes back with the field
        ControlDetails restored = details(control);
        restored.setControlOperatorReview("Review by the old Operator");
        detailsRepository.save(restored);
        assertThat(reassignOperator(control, op.getMail(), soqmSession)).isEqualTo(200);
        assertValues(control, "Steps, rewritten by the new Operator", "Review by the old Operator");
        assertThat(json(get("/api/permissions/{id}", control.getId()), facSession)
                .path("permissions").path("stepsSplit").asBoolean()).isTrue();
    }

    @Test
    void soqm_writesBothFieldsWhileSplit_andOnlyTheStepsFieldForOnePerson() throws Exception {
        MockHttpSession soqmSession = login(soqm);
        Control split = control("SOQM_HEAD_REVIEW", fac.getMail(), op.getMail(), "Steps", "Review");
        assertThat(save(split, soqmSession, STEPS, "Steps by SoQM")).isEqualTo(200);
        assertThat(save(split, soqmSession, REVIEW, "Review by SoQM")).isEqualTo(200);
        assertValues(split, "Steps by SoQM", "Review by SoQM");

        Control onePerson = control("SOQM_HEAD_REVIEW", fac.getMail(), fac.getMail(), "Steps", "Kept from before");
        assertThat(save(onePerson, soqmSession, STEPS, "Steps by SoQM")).isEqualTo(200);
        assertThat(save(onePerson, soqmSession, REVIEW, "Hidden field")).isEqualTo(403);
        // Edit mode sends the hidden field as the page loaded it (a textarea turns CRLF into LF): no change
        ControlDetails crlf = details(onePerson);
        crlf.setControlOperatorReview("Line 1\r\nLine 2");
        detailsRepository.save(crlf);
        assertThat(save(onePerson, soqmSession, "{\"" + STEPS + "\":\"Steps again\",\"" + REVIEW + "\":\"Line 1\\nLine 2\"}"))
                .isEqualTo(200);
        assertValues(onePerson, "Steps again", "Line 1\r\nLine 2");
    }

    // ------------------------------------------------------------------ View Control

    @Test
    void viewControl_onePerson_rendersOneField_namedByTheConstant() throws Exception {
        Control control = control("REVIEW", fac.getMail(), fac.getMail(), "Steps", "Kept from before");

        String page = page(control, login(fac));

        assertThat(page).contains("name=\"controlStepsPerformed\"")
                .contains(">" + ControlStepsFields.STEPS_LABEL + "<")
                .contains("id=\"stepsSplit\" value=\"false\"")
                .contains("id=\"allowedEditableFields\" value=\"controlStepsPerformed\"")
                // the page script names the field; the markup has neither the row nor the textarea
                .doesNotContain("id=\"operatorReviewRow\"")
                .doesNotContain("id=\"controlOperatorReview\"")
                .doesNotContain(">" + HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL) + "<")
                .doesNotContain("class=\"steps-field-owner\"");
    }

    @Test
    void viewControl_differentPeople_rendersBothFields_forEveryoneWhoSeesIt_editableOnlyByTheStepsOwner() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "Review");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlSharedWith(sharedUser.getMail());
        assignmentRepository.save(assignment);

        String operatorPage = page(control, login(op));
        assertThat(operatorPage).contains("name=\"controlStepsPerformed\"")
                .contains("id=\"controlOperatorReview\"")
                .contains(">" + HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL) + "<")
                .contains("<span class=\"steps-field-owner\">Facilitator</span>")
                .contains("<span class=\"steps-field-owner\">Control Operator</span>")
                .contains("id=\"operatorReviewSubmitHint\"")
                .contains("id=\"stepsSplit\" value=\"true\"")
                .contains("id=\"allowedEditableFields\" value=\"controlOperatorReview\"");

        for (User reader : List.of(fac, sharedUser, po)) {
            assertThat(page(control, login(reader))).as(reader.getMail())
                    .contains("id=\"controlOperatorReview\"")
                    .contains("id=\"allowedEditableFields\" value=\"\"");
        }
        assertThat(page(control, login(soqm))).contains("id=\"controlOperatorReview\"")
                .contains("id=\"canEditAll\" value=\"true\"");
    }

    @Test
    void viewControl_differentPeople_facilitatorInProgress_editsOnlyTheStepsField() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail(), op.getMail(), null, null);

        assertThat(page(control, login(fac)))
                .contains("id=\"controlOperatorReview\"")
                .contains("id=\"allowedEditableFields\" value=\"controlStepsPerformed\"");
        assertThat(page(control, login(op)))
                .contains("id=\"allowedEditableFields\" value=\"\"");
    }

    // ------------------------------------------------------------------ Changelog

    @Test
    void changelog_showsAnEntryWrittenUnderTheFormerName_underTheNewOne_andLeavesTheStoredEntryAsItWas() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "Program");
        AdminAuditLog former = new AdminAuditLog();
        former.setAdminEmail(op.getMail());
        former.setAdminName(op.getDisplayName());
        former.setActionType("EDIT");
        former.setControlId(control.getId());
        former.setControlControlId(control.getControlId());
        former.setActionDescription("Edit Control");
        former.setChangedFields("[\"" + ControlStepsFields.FORMER_OPERATOR_REVIEW_LABEL + "\"]");
        former.setPreviousValues("{\"" + ControlStepsFields.FORMER_OPERATOR_REVIEW_LABEL + "\":\"\"}");
        former.setNewValues("{\"" + ControlStepsFields.FORMER_OPERATOR_REVIEW_LABEL + "\":\"Program\"}");
        former.setCreatedAt(LocalDateTime.now().minusDays(1));
        former = auditLogRepository.save(former);

        assertThat(fieldAuthors(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL)).containsExactly(op.getMail());
        assertThat(fieldAuthors(control, ControlStepsFields.FORMER_OPERATOR_REVIEW_LABEL)).isEmpty();
        assertThat(auditLogRepository.findById(former.getId()).orElseThrow().getChangedFields())
                .contains(ControlStepsFields.FORMER_OPERATOR_REVIEW_LABEL);
    }

    // ------------------------------------------------------------------ Excel

    @Test
    void completedExport_hasTheOperatorFieldOnlyWhenFacilitatorAndOperatorDiffer() throws Exception {
        MockHttpSession soqmSession = login(soqm);

        Map<String, String> split = exportRows(control("COMPLETED", fac.getMail(), op.getMail(), "Steps", "Review"), soqmSession);
        assertThat(split).containsEntry(ControlStepsFields.STEPS_LABEL, "Steps")
                .containsEntry(ControlStepsFields.OPERATOR_PROGRAM_LABEL, "Review");
        assertThat(new ArrayList<>(split.keySet()).indexOf(ControlStepsFields.OPERATOR_PROGRAM_LABEL))
                .isEqualTo(new ArrayList<>(split.keySet()).indexOf(ControlStepsFields.STEPS_LABEL) + 1);

        Map<String, String> onePerson = exportRows(control("COMPLETED", fac.getMail(), fac.getMail(), "Steps", "Kept from before"), soqmSession);
        assertThat(onePerson).containsEntry(ControlStepsFields.STEPS_LABEL, "Steps")
                .doesNotContainKey(ControlStepsFields.OPERATOR_PROGRAM_LABEL);
    }

    private Map<String, String> exportRows(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/api/controls/{id}/export/completed", control.getId()), session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, String> rows = new LinkedHashMap<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (Row row : workbook.getSheetAt(0)) {
                rows.put(row.getCell(0).getStringCellValue(),
                        row.getCell(1) != null ? row.getCell(1).getStringCellValue() : null);
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ helpers

    private String page(Control control, MockHttpSession session) throws Exception {
        MvcResult result = perform(get("/view-control/{id}", control.getId()), session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private int save(Control control, MockHttpSession session, String field, String value) throws Exception {
        return saveResult(control, session, field, value).getResponse().getStatus();
    }

    private MvcResult saveResult(Control control, MockHttpSession session, String field, String value) throws Exception {
        return perform(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("controlId", control.getId(), field, value))), session);
    }

    /** A raw body; controlId is added in front. */
    private int save(Control control, MockHttpSession session, String fieldsJson) throws Exception {
        String body = "{\"controlId\":" + control.getId() + "," + fieldsJson.substring(1);
        return perform(post("/api/control-details").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), session).getResponse().getStatus();
    }

    private int workflow(String path, Control control, MockHttpSession session, String comments) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(csrf().asHeader())
                .param("controlId", String.valueOf(control.getId()));
        if (comments != null) {
            request.param("comments", comments);
        }
        return perform(request, session).getResponse().getStatus();
    }

    private int reassignOperator(Control control, String operatorMail, MockHttpSession soqmSession) throws Exception {
        ControlAssignment a = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        String body = "{\"controlId\":" + control.getId()
                + ",\"facilitator\":[\"" + a.getFacilitator() + "\"]"
                + ",\"controlOperator\":[\"" + operatorMail + "\"]"
                + ",\"soqmLead\":[\"" + a.getSoqmLead() + "\"]"
                + ",\"processOwner\":[\"" + a.getProcessOwner() + "\"]"
                + ",\"controlOperationDate\":\"" + a.getControlOperationDate() + "\"}";
        return perform(post("/api/control-assignment").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), soqmSession).getResponse().getStatus();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult result = perform(request, session);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The authors of the history entries that changed the field, newest first. */
    private List<String> fieldAuthors(Control control, String label) throws Exception {
        List<String> authors = new ArrayList<>();
        for (JsonNode entry : json(get("/api/controls/{id}/changelog", control.getId()), login(soqm))) {
            for (JsonNode change : entry.path("fieldChanges")) {
                if (label.equals(change.path("field").asText())) {
                    authors.add(entry.path("actorEmail").asText());
                }
            }
        }
        return authors;
    }

    private void assertValues(Control control, String steps, String review) {
        ControlDetails details = details(control);
        assertThat(details.getControlStepsPerformed()).as("steps").isEqualTo(steps);
        assertThat(details.getControlOperatorReview()).as("operator review").isEqualTo(review);
    }

    private ControlDetails details(Control control) {
        return detailsRepository.findByControlId(control.getId()).orElseThrow();
    }

    private String status(Control control) {
        return controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus();
    }

    private void setStatus(Control control, String status) {
        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        stored.setPerformanceStatus(status);
        controlRepository.save(stored);
    }

    private Control control(String status, String facilitators, String operators, String steps, String review) {
        Control control = new Control();
        control.setControlId("HR-SPLIT-" + UUID.randomUUID().toString().substring(0, 8));
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
        assignment.setFacilitator(facilitators);
        assignment.setControlOperator(operators);
        assignment.setSoqmLead(soqm.getMail());
        assignment.setProcessOwner(po.getMail());
        assignment.setControlOperationDate(LocalDate.now().plusDays(10));
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setControlStepsPerformed(steps);
        details.setControlOperatorReview(review);
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Process Owner comments");
        detailsRepository.save(details);
        return control;
    }

    private User saveUser(String name, AccessLevel level) {
        User user = new User();
        user.setMail(name + "@split.test");
        user.setDisplayName(name);
        user.setAccessLevel(level);
        user.setAccessScope(level == AccessLevel.SOQM ? AccessScope.ALL : AccessScope.OWN);
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
        String address = "10.7." + (addressCount / 250) + "." + (1 + addressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
