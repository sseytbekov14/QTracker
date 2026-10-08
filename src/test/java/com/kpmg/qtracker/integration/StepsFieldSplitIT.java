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
import com.kpmg.qtracker.service.AccessPolicy;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The two steps fields of Details (ControlStepsFields), through the real dev chain: Control Steps Performed and
 * Results written by the Facilitator in In Progress and the Control Operator in Review, Control Operator's Program
 * by SoQM Team only; what Submit to SoQM requires, and that returns and reassignment keep both values while the
 * history names every author.
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
                .contains(ControlStepsFields.OPERATOR_PROGRAM_LABEL + " is filled in by the SoQM Team");
        assertThat(details(control).getControlOperatorReview()).isNull();

        setStatus(control, "REVIEW");
        assertThat(save(control, facSession, STEPS, "Facilitator again in Review")).isEqualTo(403);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Facilitator steps");
    }

    @Test
    void split_operatorWritesTheStepsFieldInReview_onlyThere_andTheHistoryNamesEachAuthor() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail(), op.getMail(), null, null);
        MockHttpSession opSession = login(op);
        assertThat(save(control, login(fac), STEPS, "Facilitator steps")).isEqualTo(200);

        // Not the Operator's step yet
        assertThat(save(control, opSession, STEPS, "Too early")).isEqualTo(403);
        assertThat(save(control, opSession, REVIEW, "Too early")).isEqualTo(403);

        // Someone who edits another field of the control is told whose field it is
        setStatus(control, "PROCESS_OWNER_REVIEW");
        MvcResult notTheirs = saveResult(control, login(po), STEPS, "Process Owner writes the steps");
        assertThat(notTheirs.getResponse().getStatus()).isEqualTo(403);
        assertThat(notTheirs.getResponse().getContentAsString())
                .contains(ControlStepsFields.STEPS_LABEL + " is filled in by the Facilitator while the control is In Progress"
                        + " and by the Control Operator while it is in Review");

        // One field for both steps, as in the old system: the Operator writes it in Review
        setStatus(control, "REVIEW");
        assertThat(save(control, opSession, STEPS, "Facilitator steps\nChecked by the Operator")).isEqualTo(200);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Facilitator steps\nChecked by the Operator");
        // The Changelog keeps the Facilitator's text as the previous value (saves this quick may share a time stamp:
        // any order)
        assertThat(fieldAuthors(control, ControlStepsFields.STEPS_LABEL)).containsExactlyInAnyOrder(op.getMail(), fac.getMail());
        assertThat(fieldPreviousValues(control, ControlStepsFields.STEPS_LABEL))
                .containsExactlyInAnyOrder("Facilitator steps", "");

        // Control Operator's Program is not theirs: SoQM Team puts in what they sent
        MvcResult program = saveResult(control, opSession, REVIEW, "Operator writes the Program");
        assertThat(program.getResponse().getStatus()).isEqualTo(403);
        assertThat(program.getResponse().getContentAsString())
                .contains(ControlStepsFields.OPERATOR_PROGRAM_LABEL + " is filled in by the SoQM Team");
        assertThat(details(control).getControlOperatorReview()).isNull();

        // The page sends null for the fields the user cannot change: that is no change
        assertThat(save(control, opSession, "{\"" + STEPS + "\":\"Steps v2\",\"" + REVIEW + "\":null}")).isEqualTo(200);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Steps v2");
        assertThat(details(control).getControlOperatorReview()).isNull();

        setStatus(control, "SOQM_HEAD_REVIEW");
        assertThat(save(control, opSession, STEPS, "After submit")).isEqualTo(403);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Steps v2");
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
        assertThat(save(control, login(op2), STEPS, "Steps by the second Operator")).isEqualTo(200);
        assertThat(save(control, login(op), STEPS, "Steps by the first Operator")).isEqualTo(200);

        // saves this quick may share a time stamp: any order
        assertThat(fieldAuthors(control, ControlStepsFields.STEPS_LABEL))
                .containsExactlyInAnyOrder(op.getMail(), op2.getMail(), fac2.getMail());
        assertThat(fieldPreviousValues(control, ControlStepsFields.STEPS_LABEL))
                .containsExactlyInAnyOrder("Steps by the second Operator", "Steps by the second Facilitator", "");
    }

    // ------------------------------------------------------------------ one person: Facilitator and Operator

    @Test
    void onePersonInBothSlots_writesTheStepsOnBothSteps_neverTheProgram() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail() + ";" + fac2.getMail(), " " + fac.getMail().toUpperCase() + " ", null, null);
        MockHttpSession facSession = login(fac);

        assertThat(save(control, facSession, STEPS, "Steps as Facilitator")).isEqualTo(200);
        setStatus(control, "REVIEW");
        assertThat(save(control, facSession, STEPS, "Steps as Operator")).isEqualTo(200);
        assertThat(details(control).getControlStepsPerformed()).isEqualTo("Steps as Operator");
        MvcResult program = saveResult(control, facSession, REVIEW, "Program as Operator");
        assertThat(program.getResponse().getStatus()).isEqualTo(403);
        assertThat(program.getResponse().getContentAsString())
                .contains(ControlStepsFields.OPERATOR_PROGRAM_LABEL + " is filled in by the SoQM Team");
        assertThat(details(control).getControlOperatorReview()).isNull();

        // The other Facilitator is not the Operator: the Review step is not theirs
        assertThat(save(control, login(fac2), STEPS, "Not my step")).isEqualTo(403);
        assertThat(save(control, login(fac2), REVIEW, "Not my step")).isEqualTo(403);

        JsonNode permissions = json(get("/api/permissions/{id}", control.getId()), facSession).path("permissions");
        assertThat(permissions.has("canEditOperatorReview")).isFalse();
        assertThat(permissions.path("canEditStepsPerformed").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ Control Operator's Program: SoQM Team only

    @Test
    void theProgram_isWrittenBySoqmTeamOnly_inEveryStatusButCompleted_everyoneElseGets403() throws Exception {
        User kdn = saveUser("kdn-" + UUID.randomUUID().toString().substring(0, 8), AccessLevel.READ_ONLY);
        kdn.setAccessScope(AccessScope.KDN);
        kdn = userRepository.save(kdn);
        MockHttpSession soqmSession = login(soqm);

        for (String status : List.of("DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW")) {
            // Read Only and KDN are listed as Control Operators too (old data): listing gives no write
            Control control = control(status, fac.getMail(),
                    op.getMail() + ";" + readOnly.getMail() + ";" + kdn.getMail(), "Steps", null);
            Control stored = controlRepository.findById(control.getId()).orElseThrow();
            stored.setControlId("KDN-PROG-" + UUID.randomUUID().toString().substring(0, 8));
            control = controlRepository.save(stored);
            ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
            assignment.setControlSharedWith(sharedUser.getMail());
            assignmentRepository.save(assignment);

            for (User who : List.of(op, fac, po, outsider, sharedUser, readOnly, kdn, readOnlyAll())) {
                assertThat(save(control, login(who), REVIEW, "by " + who.getMail()))
                        .as(status + " " + who.getMail()).isEqualTo(403);
            }
            assertThat(details(control).getControlOperatorReview()).as(status).isNull();

            assertThat(save(control, soqmSession, REVIEW, "Program v1")).as(status).isEqualTo(200);
            assertThat(save(control, soqmSession, REVIEW, "Program v2")).as(status).isEqualTo(200);
            assertThat(details(control).getControlOperatorReview()).isEqualTo("Program v2");
            // Every save is a Changelog entry with its author; the earlier text stays there
            assertThat(fieldAuthors(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL))
                    .as(status).containsExactly(soqm.getMail(), soqm.getMail());
            assertThat(fieldPreviousValues(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL))
                    .as(status).containsExactlyInAnyOrder("Program v1", ""); // the same millisecond: any order

            // Everyone who sees the control reads it
            for (User who : List.of(op, fac, po, sharedUser, kdn)) {
                MvcResult read = perform(get("/api/control-details").param("controlId", String.valueOf(control.getId())), login(who));
                if ("DRAFT".equals(status) && who != kdn) {
                    continue; // a draft is not open to the participants yet
                }
                assertThat(read.getResponse().getStatus()).as(status + " read " + who.getMail()).isEqualTo(200);
                assertThat(objectMapper.readTree(read.getResponse().getContentAsString()).path(REVIEW).asText())
                        .isEqualTo("Program v2");
            }
        }

        // The Control Operator on their step is told whose field it is
        Control review = control("REVIEW", fac.getMail(), op.getMail(), "Steps", null);
        MvcResult refused = saveResult(review, login(op), REVIEW, "Program by the Operator");
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains(ControlStepsFields.OPERATOR_PROGRAM_LABEL + " is filled in by the SoQM Team");
    }

    @Test
    void theProgram_ofACompletedControl_isLockedForEveryone_soqmIncluded() throws Exception {
        Control control = control("COMPLETED", fac.getMail(), op.getMail(), "Steps", "Program");

        MvcResult soqmSave = saveResult(control, login(soqm), REVIEW, "Changed after completion");
        assertThat(soqmSave.getResponse().getStatus()).isEqualTo(403);
        assertThat(soqmSave.getResponse().getContentAsString()).contains(AccessPolicy.LOCKED_MESSAGE);
        for (User who : List.of(op, fac, po)) {
            assertThat(save(control, login(who), REVIEW, "by " + who.getMail())).as(who.getMail()).isEqualTo(403);
        }
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Program");

        // Returned to an earlier status, SoQM Team writes it again
        setStatus(control, "PROCESS_OWNER_REVIEW");
        assertThat(save(control, login(soqm), REVIEW, "Corrected")).isEqualTo(200);
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Corrected");
    }

    @Test
    void theProgram_isNeverWrittenThroughTheOtherSavePaths() throws Exception {
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession opSession = login(op);

        // PUT /api/controls: the control's own fields; the Program sent along is ignored, for SoQM Team as well
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "Program");
        for (MockHttpSession session : List.of(soqmSession, opSession)) {
            perform(put("/api/controls/{id}", control.getId()).with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"controlOperatorReview\":\"Through PUT\",\"controlDescription\":\"Description\"}"), session);
            assertThat(details(control).getControlOperatorReview()).isEqualTo("Program");
        }

        // The workflow steps and SoQM's move take no Details fields
        MvcResult submitted = perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(control.getId()))
                .param("controlOperatorReview", "Through submit"), opSession);
        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Program");
        MvcResult moved = perform(post("/api/workflow/move").with(csrf().asHeader())
                .param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", "REVIEW")
                .param("comments", "Back to the Operator")
                .param("controlOperatorReview", "Through move"), soqmSession);
        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        assertThat(status(control)).isEqualTo("REVIEW");
        assertThat(details(control).getControlOperatorReview()).isEqualTo("Program");
        assertThat(fieldAuthors(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL)).isEmpty();
    }

    // ------------------------------------------------------------------ attachments

    @Test
    void controlOperatorInReview_attachesDocuments_onBothTabs_andDeletesTheirOwn_onlyOnTheirStep() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail(), op.getMail() + ";" + readOnly.getMail(), "Steps", null);
        MockHttpSession opSession = login(op);

        // Not their step yet
        assertThat(upload(control, opSession, "attachmentDetails", "too-early.pdf").getResponse().getStatus()).isEqualTo(403);

        setStatus(control, "REVIEW");
        MvcResult details = upload(control, opSession, "attachmentDetails", "operator-evidence.pdf");
        assertThat(details.getResponse().getStatus()).isEqualTo(200);
        String detailsFile = objectMapper.readTree(details.getResponse().getContentAsString()).path("detailsFiles").asText();
        MvcResult documents = upload(control, opSession, "attachmentDocuments", "operator-sample.pdf");
        assertThat(documents.getResponse().getStatus()).isEqualTo(200);
        String documentsFile = objectMapper.readTree(documents.getResponse().getContentAsString()).path("documentsFiles").asText();
        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(stored.getAttachmentDetailsPath()).contains(detailsFile);
        assertThat(stored.getAttachmentDocumentsPath()).contains(documentsFile);

        // Their own files are theirs to delete while the step is theirs; everyone who sees the control downloads them
        JsonNode info = json(get("/api/attachments/info/{id}", control.getId()), opSession);
        assertThat(info.path("deletableDetails").toString()).contains(detailsFile);
        for (User reader : List.of(fac, soqm, po)) {
            assertThat(perform(get("/api/attachments/download/{name}", detailsFile)
                    .param("controlId", String.valueOf(control.getId())), login(reader)).getResponse().getStatus())
                    .as(reader.getMail()).isEqualTo(200);
        }
        assertThat(perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/attachments/delete/{id}", control.getId()).with(csrf().asHeader())
                .param("filename", documentsFile).param("type", "documents"), opSession).getResponse().getStatus())
                .isEqualTo(200);

        // Listed as Control Operator but Read Only: no upload
        assertThat(upload(control, login(readOnly), "attachmentDetails", "read-only.pdf").getResponse().getStatus()).isEqualTo(403);

        // Submitted: the step is over
        setStatus(control, "SOQM_HEAD_REVIEW");
        assertThat(upload(control, opSession, "attachmentDetails", "too-late.pdf").getResponse().getStatus()).isEqualTo(403);
    }

    private MvcResult upload(Control control, MockHttpSession session, String tab, String name) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/attachments/upload/{id}", control.getId())
                .file(new org.springframework.mock.web.MockMultipartFile(tab, name, "application/pdf",
                        "%PDF-1.4 operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .with(csrf().asHeader()), session);
    }

    // ------------------------------------------------------------------ Submit to SoQM

    @Test
    void submitToSoqm_needsNoOperatorsProgram_differentPeopleOrOne() throws Exception {
        Control split = control("REVIEW", fac.getMail(), op.getMail(), "Steps", null);
        MockHttpSession opSession = login(op);

        JsonNode permissions = json(get("/api/permissions/{id}", split.getId()), opSession).path("permissions");
        assertThat(permissions.has("operatorProgramRequired")).isFalse();
        assertThat(permissions.has("stepsSplit")).isFalse();

        assertThat(perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(split.getId())), opSession).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(split)).isEqualTo("SOQM_HEAD_REVIEW");
        assertThat(details(split).getControlOperatorReview()).isNull();

        // The other way in, as View Control's action buttons send it
        Control viaAction = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "  ");
        assertThat(perform(post("/api/workflow/perform-action").with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + viaAction.getId() + ",\"action\":\"SUBMIT_FOR_SOQM\"}"), opSession)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(status(viaAction)).isEqualTo("SOQM_HEAD_REVIEW");

        Control onePerson = control("REVIEW", fac.getMail(), fac.getMail(), "Steps", null);
        assertThat(perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(onePerson.getId())), login(fac)).getResponse().getStatus()).isEqualTo(200);
        assertThat(status(onePerson)).isEqualTo("SOQM_HEAD_REVIEW");

        // The steps field stays required: its message never names the Program
        Control noSteps = control("REVIEW", fac.getMail(), op.getMail(), null, null);
        MvcResult missing = perform(post("/api/workflow/submit-to-soqm-lead").with(csrf().asHeader())
                .param("controlId", String.valueOf(noSteps.getId())), opSession);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString())
                .contains("Required field is missing: " + ControlStepsFields.STEPS_LABEL)
                .doesNotContain(ControlStepsFields.OPERATOR_PROGRAM_LABEL)
                .doesNotContain(HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL));
        assertThat(status(noSteps)).isEqualTo("REVIEW");
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
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps v1", "Program v1");
        MockHttpSession facSession = login(fac);
        MockHttpSession opSession = login(op);
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession poSession = login(po);

        // Control Operator -> Facilitator
        assertThat(workflow("/api/workflow/return-to-facilitator", control, opSession, "Steps are incomplete")).isEqualTo(200);
        assertThat(status(control)).isEqualTo("IN_PROGRESS");
        assertValues(control, "Steps v1", "Program v1");
        assertThat(save(control, facSession, STEPS, "Steps v2")).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-control-operator", control, facSession, null)).isEqualTo(200);
        assertValues(control, "Steps v2", "Program v1");
        assertThat(save(control, opSession, STEPS, "Steps v3")).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, opSession, null)).isEqualTo(200);

        // SoQM -> Control Operator
        assertThat(workflow("/api/workflow/return-to-operator", control, soqmSession, "Name the sample")).isEqualTo(200);
        assertThat(status(control)).isEqualTo("REVIEW");
        assertValues(control, "Steps v3", "Program v1");
        assertThat(save(control, soqmSession, REVIEW, "Program v2")).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, opSession, null)).isEqualTo(200);
        assertThat(workflow("/api/workflow/submit-to-process-owner", control, soqmSession, null)).isEqualTo(200);

        // Process Owner -> Control Operator
        assertThat(workflow("/api/workflow/return-to-operator", control, poSession, "The sample is too small")).isEqualTo(200);
        assertThat(status(control)).isEqualTo("REVIEW");
        assertValues(control, "Steps v3", "Program v2");
        assertThat(save(control, opSession, STEPS, "Steps v4")).isEqualTo(200);
        assertValues(control, "Steps v4", "Program v2");

        // saves this quick may share a time stamp: any order
        assertThat(fieldAuthors(control, ControlStepsFields.STEPS_LABEL))
                .containsExactlyInAnyOrder(op.getMail(), op.getMail(), fac.getMail());
        assertThat(fieldPreviousValues(control, ControlStepsFields.STEPS_LABEL))
                .containsExactlyInAnyOrder("Steps v3", "Steps v2", "Steps v1");
        assertThat(fieldAuthors(control, ControlStepsFields.OPERATOR_PROGRAM_LABEL)).containsExactly(soqm.getMail());
    }

    @Test
    void reassignment_keepsBothValues_andNeverMakesTheProgramRequired() throws Exception {
        Control control = control("REVIEW", fac.getMail(), op.getMail(), "Steps", "Review by the old Operator");
        MockHttpSession soqmSession = login(soqm);

        // The Facilitator becomes the Operator too, then someone else again: both values stay
        assertThat(reassignOperator(control, fac.getMail(), soqmSession)).isEqualTo(200);
        assertValues(control, "Steps", "Review by the old Operator");
        ControlDetails cleared = details(control);
        cleared.setControlOperatorReview("");
        detailsRepository.save(cleared);
        assertThat(reassignOperator(control, op.getMail(), soqmSession)).isEqualTo(200);
        assertValues(control, "Steps", "");

        // Different people and an empty Program: Submit to SoQM Team passes all the same
        assertThat(workflow("/api/workflow/submit-to-soqm-lead", control, login(op), null)).isEqualTo(200);
        assertThat(status(control)).isEqualTo("SOQM_HEAD_REVIEW");
    }

    @Test
    void soqm_writesBothFields_onePersonOrNot() throws Exception {
        MockHttpSession soqmSession = login(soqm);
        Control split = control("SOQM_HEAD_REVIEW", fac.getMail(), op.getMail(), "Steps", "Review");
        assertThat(save(split, soqmSession, STEPS, "Steps by SoQM")).isEqualTo(200);
        assertThat(save(split, soqmSession, REVIEW, "Review by SoQM")).isEqualTo(200);
        assertValues(split, "Steps by SoQM", "Review by SoQM");

        Control onePerson = control("SOQM_HEAD_REVIEW", fac.getMail(), fac.getMail(), "Steps", "Kept from before");
        assertThat(save(onePerson, soqmSession, STEPS, "Steps by SoQM")).isEqualTo(200);
        assertThat(save(onePerson, soqmSession, REVIEW, "Program by SoQM")).isEqualTo(200);
        assertValues(onePerson, "Steps by SoQM", "Program by SoQM");
        assertThat(fieldAuthors(onePerson, ControlStepsFields.OPERATOR_PROGRAM_LABEL)).containsExactly(soqm.getMail());

        // A field the user may not change, sent as the page loaded it (a textarea turns CRLF into LF): no change
        Control inProgress = control("IN_PROGRESS", fac.getMail(), fac.getMail(), "Steps", "Line 1\r\nLine 2");
        assertThat(save(inProgress, login(fac), "{\"" + STEPS + "\":\"Steps again\",\"" + REVIEW + "\":\"Line 1\\nLine 2\"}"))
                .isEqualTo(200);
        assertValues(inProgress, "Steps again", "Line 1\r\nLine 2");
    }

    // ------------------------------------------------------------------ View Control

    @Test
    void viewControl_onePerson_rendersBothFields_theStepsFieldIsTheOperatorsStepInReview() throws Exception {
        Control control = control("REVIEW", fac.getMail(), fac.getMail(), "Steps", null);

        String page = page(control, login(fac));

        assertThat(page).contains("name=\"controlStepsPerformed\"")
                .contains(">" + ControlStepsFields.STEPS_LABEL + "<")
                .contains("id=\"operatorReviewRow\"")
                .contains("id=\"controlOperatorReview\"")
                .contains(">" + HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL) + "<")
                .contains("<span class=\"steps-field-owner\">Facilitator</span>")
                .doesNotContain("<span class=\"steps-field-owner\">Control Operator</span>")
                .doesNotContain("id=\"stepsSplit\"")
                .doesNotContain("id=\"operatorProgramRequired\"")
                .contains("id=\"allowedEditableFields\" value=\"controlStepsPerformed\"")
                // Your step: check the steps field, which Submit to SoQM Team needs
                .contains("data-step-field=\"controlStepsPerformed\"", "data-step-required=\"true\"")
                .doesNotContain(" (optional)");

        // The same person on the Facilitator's step writes the steps field only
        setStatus(control, "IN_PROGRESS");
        assertThat(page(control, login(fac)))
                .contains("id=\"controlOperatorReview\"")
                .contains("id=\"allowedEditableFields\" value=\"controlStepsPerformed\"");
    }

    @Test
    void viewControl_theProgram_isShownToEveryoneWhoSeesTheControl_readOnlyEmptyReads_NotFilledYet() throws Exception {
        for (String operators : List.of(fac.getMail(), op.getMail())) {
            Control control = control("REVIEW", fac.getMail(), operators, "Steps", null);
            ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
            assignment.setControlSharedWith(sharedUser.getMail());
            assignmentRepository.save(assignment);

            for (User reader : List.of(fac, op, soqm, po, sharedUser, readOnlyAll())) {
                if (reader == op && operators.equals(fac.getMail())) {
                    continue; // not on the one-person control
                }
                assertThat(page(control, login(reader))).as(operators + " / " + reader.getMail())
                        .contains("id=\"controlOperatorReview\"")
                        .contains("placeholder=\"Not filled yet\"")
                        .contains(">" + HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL) + "<");
            }
        }
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
                .doesNotContain("<span class=\"steps-field-owner\">Control Operator</span>")
                .doesNotContain("id=\"operatorReviewSubmitHint\"")
                .doesNotContain("id=\"stepsSplit\"")
                .doesNotContain("id=\"operatorProgramRequired\"")
                // Your step: the steps field, one for the Facilitator and the Control Operator
                .contains("data-step-field=\"controlStepsPerformed\"", "data-step-required=\"true\"")
                .contains("id=\"allowedEditableFields\" value=\"controlStepsPerformed\"");

        for (User reader : List.of(fac, sharedUser, po)) {
            assertThat(page(control, login(reader))).as(reader.getMail())
                    .contains("id=\"controlOperatorReview\"")
                    .contains("id=\"allowedEditableFields\" value=\"\"");
        }
        assertThat(page(control, login(soqm))).contains("id=\"controlOperatorReview\"")
                .contains("id=\"canEditAll\" value=\"true\"");
    }

    @Test
    void viewControl_details_haveTheProgramRightAfterProcessActivities_beforeOtherRelatedControls() throws Exception {
        Control control = control("IN_PROGRESS", fac.getMail(), op.getMail(), "Steps", "Program");

        for (User reader : List.of(soqm, op, readOnlyAll())) {
            String page = page(control, login(reader));
            List<String> fieldsInOrder = List.of(
                    "name=\"processName\" maxlength",
                    "name=\"department\" maxlength",
                    "name=\"processActivities\" rows",
                    "id=\"controlOperatorReview\" name=",
                    "name=\"otherRelatedControls\" maxlength",
                    "name=\"itApplications\" maxlength",
                    "id=\"controlStepsPerformed\" name=",
                    "id=\"soqmHeadComments\"",
                    "id=\"processOwnerComments\"");
            int previous = -1;
            for (String field : fieldsInOrder) {
                int at = page.indexOf(field);
                assertThat(at).as(reader.getMail() + ": " + field + " after the field before it").isGreaterThan(previous);
                previous = at;
            }
            // The Program's label is its name only, no role next to it
            assertThat(page).contains("<label class=\"form-label\" for=\"controlOperatorReview\"><span>"
                    + HtmlUtils.htmlEscape(ControlStepsFields.OPERATOR_PROGRAM_LABEL) + "</span></label>");
        }
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
    void completedExport_hasTheOperatorsProgram_onePersonOrNot() throws Exception {
        MockHttpSession soqmSession = login(soqm);

        Map<String, String> split = exportRows(control("COMPLETED", fac.getMail(), op.getMail(), "Steps", "Review"), soqmSession);
        assertThat(split).containsEntry(ControlStepsFields.STEPS_LABEL, "Steps")
                .containsEntry(ControlStepsFields.OPERATOR_PROGRAM_LABEL, "Review");
        // As on the Details tab: right after Process Activities
        assertThat(new ArrayList<>(split.keySet()).indexOf(ControlStepsFields.OPERATOR_PROGRAM_LABEL))
                .isEqualTo(new ArrayList<>(split.keySet()).indexOf("Process Activities") + 1);

        Map<String, String> onePerson = exportRows(control("COMPLETED", fac.getMail(), fac.getMail(), "Steps", "Kept from before"), soqmSession);
        assertThat(onePerson).containsEntry(ControlStepsFields.STEPS_LABEL, "Steps")
                .containsEntry(ControlStepsFields.OPERATOR_PROGRAM_LABEL, "Kept from before")
                .doesNotContainKey(ControlStepsFields.FORMER_OPERATOR_REVIEW_LABEL);

        // An empty Program has no row, as every empty field
        Map<String, String> empty = exportRows(control("COMPLETED", fac.getMail(), op.getMail(), "Steps", null), soqmSession);
        assertThat(empty).containsKey(ControlStepsFields.STEPS_LABEL)
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

    /** The values the field had before each history entry that changed it, newest first. */
    private List<String> fieldPreviousValues(Control control, String label) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode entry : json(get("/api/controls/{id}/changelog", control.getId()), login(soqm))) {
            for (JsonNode change : entry.path("fieldChanges")) {
                if (label.equals(change.path("field").asText())) {
                    values.add(change.path("oldValue").asText());
                }
            }
        }
        return values;
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
        details.setProcessActivities("Process activities");
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Process Owner comments");
        detailsRepository.save(details);
        return control;
    }

    /** A Read Only user of All controls: sees the control without being on it. */
    private User readOnlyAll() {
        User user = saveUser("ro-all-" + UUID.randomUUID().toString().substring(0, 8), AccessLevel.READ_ONLY);
        user.setAccessScope(AccessScope.ALL);
        return userRepository.save(user);
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
