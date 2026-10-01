package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API access through the real dev security chain: form login, session, CSRF rules.
 * The dev profile is active only for its security chain; the datasource is overridden to
 * in-memory H2 and the dev user seeder is replaced by a mock.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:api-security-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "file.upload.dir=target/it-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class ApiSecurityMockMvcIT {

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
    private ControlDocumentsRepository documentsRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ControlDetailsRepository detailsRepository;

    @Autowired
    private ControlAttachmentRepository attachmentRepository;

    @Autowired
    private UserService userService;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    @MockitoSpyBean
    private WorkflowHistoryRepository workflowHistoryRepository;

    private static int loginCount;

    private final List<Long> createdControlIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // controls.created_by references users, so controls go first
        controlRepository.deleteAllById(createdControlIds);
        userRepository.deleteAllById(createdUserIds);
        createdControlIds.clear();
        createdUserIds.clear();
    }

    @Test
    void devProfile_usesInMemoryH2_withoutSeededUsers() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        assertThat(userRepository.existsByMail("fac1@qtracker.local")).isFalse();
    }

    @Test
    void loginWithDbUser_thenOwnControls_returns200() throws Exception {
        String qaEmail = "qa-user-" + suffix() + "@example.test";
        User qaUser = saveUser("qa-user", qaEmail, "FACILITATOR");
        createControl("CTRL-DB-" + suffix(), qaUser, "DRAFT");

        MockHttpSession session = login(qaEmail);

        mockMvc.perform(get("/api/controls/user/{email}", qaEmail)
                        .session(session))
                .andExpect(status().isOk());
    }

    @Test
    void readForeignControl_returns403() throws Exception {
        User owner = saveUser("owner-" + suffix(), "owner-" + suffix() + "@example.test", "PROCESS_OWNER");
        Control foreignControl = createControl("CTRL-FGN-" + suffix(), owner, "IN_PROGRESS");

        String facEmail = "fac-" + suffix() + "@example.test";
        saveUser(facEmail, facEmail, "FACILITATOR");
        MockHttpSession session = login(facEmail);

        mockMvc.perform(get("/api/controls/{id}/changelog", foreignControl.getId())
                        .session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void forbiddenWorkflowTransition_returns403() throws Exception {
        String ownerEmail = "owner2-" + suffix() + "@example.test";
        User processOwner = saveUser(ownerEmail, ownerEmail, "PROCESS_OWNER");
        Control control = createControl("CTRL-WF-" + suffix(), processOwner, "IN_PROGRESS");

        MockHttpSession session = login(ownerEmail);

        // The message proves the 403 comes from the workflow guard, not from the security filters
        mockMvc.perform(post("/api/workflow/submit-to-control-operator")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Only the assigned Facilitator")));
    }

    @Test
    void soqmLead_read_modify_workflow_are200() throws Exception {
        User soqmLead = saveUser("soqm-" + suffix(), "soqm-" + suffix() + "@example.test", "SOQM_TEAM");
        Control control = createControl("CTRL-SOQM-" + suffix(), soqmLead, "SOQM_HEAD_REVIEW");

        MockHttpSession session = login(soqmLead.getMail());

        mockMvc.perform(get("/api/controls/{id}/changelog", control.getId())
                        .session(session))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/controls/{id}/rename-id", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newControlId\":\"" + control.getControlId() + "-R\"}")
                        .session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workflow/my-approvals")
                        .session(session))
                .andExpect(status().isOk());
    }

    @Test
    void assignedFacilitator_cannotReassignRoles_returns403() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ASG-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        MockHttpSession session = login(p.facilitator.getMail());

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId()
                                + ",\"processOwner\":[\"" + p.facilitator.getMail() + "\"]}")
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Only SoQM Team can change control assignment")));

        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getProcessOwner())
                .isEqualTo(p.owner.getMail());
    }

    @Test
    void soqm_canReassignRoles_returns200() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ASG-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        String newOwner = "owner-new-" + suffix() + "@example.test";
        saveUser(newOwner, newOwner, "PROCESS_OWNER");

        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId()
                                + ",\"processOwner\":[\"" + newOwner + "\"]}")
                        .session(session))
                .andExpect(status().isOk());

        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getProcessOwner())
                .isEqualTo(newOwner);
    }

    @Test
    void assignedFacilitator_resendingUnchangedControlForm_returns200() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-PUT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        MockHttpSession session = login(p.facilitator.getMail());

        // Same values as stored, with the whitespace a form round-trip may add
        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlForm(" monthly ", "HR ", "IN_PROGRESS"))
                        .session(session))
                .andExpect(status().isOk());
    }

    @Test
    void assignedFacilitator_changingMasterField_returns403() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-PUT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        MockHttpSession session = login(p.facilitator.getMail());

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlForm("Monthly", "GOV", "IN_PROGRESS"))
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Component can be changed only by SoQM Team")));

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlForm("Monthly", "HR", "DELETED"))
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Control Status can be changed only by SoQM Team")));

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlForm("Quarterly", "HR", "IN_PROGRESS"))
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Control Frequency can be changed only by SoQM Team")));

        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(stored.getComponent()).isEqualTo("HR");
        assertThat(stored.getControlStatus()).isEqualTo("IN_PROGRESS");
        assertThat(stored.getControlFrequency()).isEqualTo("Monthly");
    }

    @Test
    void soqm_changingMasterField_returns200() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-PUT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlForm("Monthly", "GOV", "IN_PROGRESS"))
                        .session(session))
                .andExpect(status().isOk());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getComponent()).isEqualTo("GOV");
    }

    @Test
    void assignedFacilitator_cannotAutoSaveSoqmYear_returns403() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-YEAR-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);

        MockHttpSession session = login(p.facilitator.getMail());

        mockMvc.perform(post("/api/performance/auto-save")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "2031")
                        .session(session))
                .andExpect(status().isForbidden());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getSoqmYear()).isNull();
    }

    @Test
    void soqm_canAutoSaveSoqmYear_returns200() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-YEAR-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);

        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/performance/auto-save")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "2031")
                        .session(session))
                .andExpect(status().isOk());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getSoqmYear()).isEqualTo("2031");
    }

    @Test
    void assignedFacilitator_cannotChangeSoqmDevelopmentMaterials_returns403() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-DOC-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        MockHttpSession session = login(p.facilitator.getMail());

        mockMvc.perform(post("/api/control-documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"soqmDevelopmentMaterials\":\"Available\"}")
                        .session(session))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Only SoQM Team can change SoQM Development Materials")));

        assertThat(documentsRepository.findByControlId(control.getId()).orElseThrow().getSoqmDevelopmentMaterials())
                .isNull();
    }

    @Test
    void soqm_canChangeSoqmDevelopmentMaterials_returns200() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-DOC-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/control-documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"soqmDevelopmentMaterials\":\"Available\"}")
                        .session(session))
                .andExpect(status().isOk());

        assertThat(documentsRepository.findByControlId(control.getId()).orElseThrow().getSoqmDevelopmentMaterials())
                .isEqualTo("Available");
    }

    @Test
    void roleChangedByAdmin_appliesToExistingSession() throws Exception {
        User soqm = saveUser("soqm-" + suffix(), "soqm-" + suffix() + "@example.test", "SOQM_TEAM");
        Control control = createControl("CTRL-ROLE-" + suffix(), soqm, "DRAFT");

        MockHttpSession session = login(soqm.getMail());

        soqm.setRole("FACILITATOR");
        userRepository.save(soqm);

        mockMvc.perform(post("/api/controls/{id}/rename-id", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newControlId\":\"" + control.getControlId() + "-R\"}")
                        .session(session))
                .andExpect(status().isForbidden());

        assertThat(((User) session.getAttribute("currentUser")).getRole()).isEqualTo("FACILITATOR");
    }

    @Test
    void transitionFailingAfterStatusChange_rollsBackStatus() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-TX-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setControlStepsPerformed("Steps performed");
        detailsRepository.save(details);
        doThrow(new IllegalStateException("history store unavailable"))
                .when(workflowHistoryRepository).save(any(WorkflowHistory.class));

        MockHttpSession session = login(p.facilitator.getMail());

        mockMvc.perform(post("/api/workflow/submit-to-control-operator")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isInternalServerError());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void hardDeleteOfControl_isNotExposed() throws Exception {
        User soqm = saveUser("soqm-" + suffix(), "soqm-" + suffix() + "@example.test", "SOQM_TEAM");
        Control control = createControl("CTRL-DEL-" + suffix(), soqm, "DRAFT");

        MockHttpSession session = login(soqm.getMail());

        // Controls are removed only by the soft delete (Control Status = Deleted)
        mockMvc.perform(delete("/api/controls/{id}", control.getId())
                        .session(session))
                .andExpect(status().isMethodNotAllowed());

        assertThat(controlRepository.findById(control.getId())).isPresent();
    }

    @Test
    void legacyStepApproveAndReturn_areNotExposed() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-LEG-" + suffix(), p.soqm, "REVIEW");
        assign(control, p);

        MockHttpSession session = login(p.operator.getMail());

        for (String url : List.of("/api/workflow/approve", "/api/workflow/return")) {
            mockMvc.perform(post(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"controlId\":" + control.getId() + "}")
                            .session(session))
                    .andExpect(status().isNotFound());
        }

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("REVIEW");
    }

    @Test
    void duplicateWorkflowInitiate_isNotExposed_performanceInitiateStillWorks() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-INIT-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);

        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/workflow/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isNotFound());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("DRAFT");

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isOk());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void soqmTransitionFailingAfterStatusChange_rollsBackStatus() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-TX2-" + suffix(), p.soqm, "SOQM_HEAD_REVIEW");
        assign(control, p);
        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setControlStepsPerformed("Steps performed");
        details.setSoqmHeadComments("SoQM comments");
        detailsRepository.save(details);
        doThrow(new IllegalStateException("history store unavailable"))
                .when(workflowHistoryRepository).save(any(WorkflowHistory.class));

        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/workflow/submit-to-process-owner")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isBadRequest());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("SOQM_HEAD_REVIEW");

        mockMvc.perform(post("/api/workflow/perform-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"action\":\"SEND_TO_PROCESS_OWNER\"}")
                        .session(session))
                .andExpect(status().isBadRequest());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("SOQM_HEAD_REVIEW");
    }

    @Test
    void uploader_canDeleteOwnFile_inSameStage() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ATT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        MockHttpSession session = login(p.facilitator.getMail());

        String stored = upload(control, session, "Отчёт о проверке.pdf");
        assertThat(stored).isEqualTo("Отчёт_о_проверке.pdf");
        ControlAttachment record = attachmentRepository
                .findByControlIdAndTabAndFileName(control.getId(), ControlAttachment.TAB_DETAILS, stored).orElseThrow();
        assertThat(record.getUploadedByEmail()).isEqualTo(p.facilitator.getMail());
        assertThat(record.getUploadedStage()).isEqualTo("IN_PROGRESS");

        deleteAttachment(control, session, stored).andExpect(status().isOk());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getAttachmentDetailsPath()).isNull();
        assertThat(attachmentRepository.findByControlIdAndTabAndFileName(
                control.getId(), ControlAttachment.TAB_DETAILS, stored)).isEmpty();
    }

    @Test
    void otherParticipant_cannotDeleteSomeoneElsesFile_returns403() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ATT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        String stored = upload(control, login(p.facilitator.getMail()), "evidence.pdf");
        moveTo(control, "REVIEW");

        // The Control Operator may edit in REVIEW, but did not upload the file
        deleteAttachment(control, login(p.operator.getMail()), stored)
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Only the user who uploaded this file")));

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getAttachmentDetailsPath())
                .isEqualTo(stored);
    }

    @Test
    void uploader_cannotDeleteOwnFile_afterStageChanged_returns403() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ATT-" + suffix(), p.soqm, "IN_PROGRESS");
        // One person is both Facilitator and Control Operator, so they can edit in both stages
        assign(control, new Participants(p.facilitator, p.facilitator, p.soqm, p.owner));
        MockHttpSession session = login(p.facilitator.getMail());
        String stored = upload(control, session, "evidence.pdf");
        moveTo(control, "REVIEW");

        deleteAttachment(control, session, stored).andExpect(status().isForbidden());
    }

    @Test
    void fileWithoutUploadRecord_onlySoqmCanDelete() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ATT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        // Attached before V5 by the facilitator: listed on the control, no record of who uploaded it
        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        stored.setAttachmentDetailsPath("Старый_файл.pdf");
        controlRepository.save(stored);

        deleteAttachment(control, login(p.facilitator.getMail()), "Старый_файл.pdf")
                .andExpect(status().isForbidden());

        deleteAttachment(control, login(p.soqm.getMail()), "Старый_файл.pdf")
                .andExpect(status().isOk());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getAttachmentDetailsPath()).isNull();
    }

    @Test
    void attachmentInfo_listsAsDeletableOnlyWhatTheUserMayDelete() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ATT-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        MockHttpSession facilitator = login(p.facilitator.getMail());
        String stored = upload(control, facilitator, "Отчёт.pdf");

        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).session(facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletableDetails[0]").value(stored))
                .andExpect(jsonPath("$.deletableDocuments").isEmpty());

        moveTo(control, "REVIEW");

        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).session(facilitator))
                .andExpect(jsonPath("$.deletableDetails").isEmpty());
        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).session(login(p.operator.getMail())))
                .andExpect(jsonPath("$.attachmentDetailsPath").value(stored))
                .andExpect(jsonPath("$.deletableDetails").isEmpty());
        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).session(login(p.soqm.getMail())))
                .andExpect(jsonPath("$.deletableDetails[0]").value(stored));
    }

    private String upload(Control control, MockHttpSession session, String originalName) throws Exception {
        MockMultipartFile file = new MockMultipartFile("attachmentDetails", originalName, "application/pdf",
                "%PDF-1.4 test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String body = mockMvc.perform(multipart("/api/attachments/upload/{id}", control.getId())
                        .file(file)
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.detailsFiles");
    }

    private org.springframework.test.web.servlet.ResultActions deleteAttachment(Control control, MockHttpSession session,
                                                                               String fileName) throws Exception {
        return mockMvc.perform(delete("/api/attachments/delete/{id}", control.getId())
                .param("filename", fileName)
                .param("type", "details")
                .session(session));
    }

    private void moveTo(Control control, String status) {
        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        stored.setPerformanceStatus(status);
        controlRepository.save(stored);
    }

    @Test
    void deadlineCountdown_listsOverdueSeparately_andKeepsTodayInUpcoming() throws Exception {
        String mail = "deadline-fac-" + suffix() + "@example.test";
        User facilitator = saveUser("deadline-fac", mail, "FACILITATOR");
        LocalDate today = DeadlineOverdue.today(Instant.now());

        Control overdue = deadlineControl("CTRL-OVERDUE-" + suffix(), facilitator, "IN_PROGRESS", today.minusDays(5));
        Control dueToday = deadlineControl("CTRL-TODAY-" + suffix(), facilitator, "IN_PROGRESS", today);
        Control dueSoon = deadlineControl("CTRL-SOON-" + suffix(), facilitator, "IN_PROGRESS", today.plusDays(2));
        deadlineControl("CTRL-LATER-" + suffix(), facilitator, "IN_PROGRESS", today.plusDays(10));
        deadlineControl("CTRL-DONE-" + suffix(), facilitator, "COMPLETED", today.minusDays(3));
        deadlineControl("CTRL-DRAFT-" + suffix(), facilitator, "DRAFT", today.minusDays(3));

        mockMvc.perform(get("/api/dashboard/deadline-countdown").param("days", "3").session(login(mail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueTotal").value(1))
                .andExpect(jsonPath("$.overdue.length()").value(1))
                .andExpect(jsonPath("$.overdue[0].id").value(overdue.getId()))
                .andExpect(jsonPath("$.overdue[0].overdue").value(true))
                .andExpect(jsonPath("$.overdue[0].daysOverdue").value(5))
                .andExpect(jsonPath("$.overdue[0].deadline").value(today.minusDays(5) + "T23:59:00+05:00"))
                .andExpect(jsonPath("$.upcoming.length()").value(2))
                .andExpect(jsonPath("$.upcoming[0].id").value(dueToday.getId()))
                .andExpect(jsonPath("$.upcoming[0].overdue").value(false))
                .andExpect(jsonPath("$.upcoming[0].daysOverdue").value(0))
                .andExpect(jsonPath("$.upcoming[1].id").value(dueSoon.getId()));
    }

    @Test
    void needsAttention_isRenderedForSoqmAndAdmin_notForFacilitator() throws Exception {
        String s = suffix();
        User facilitator = saveUser("na-fac-" + s, "na-fac-" + s + "@example.test", "FACILITATOR");
        User soqm = saveUser("na-soqm-" + s, "na-soqm-" + s + "@example.test", "SOQM_TEAM");
        User admin = saveUser("na-admin-" + s, "na-admin-" + s + "@example.test", "ADMIN");
        admin.setAdminAccess(true);
        userRepository.save(admin);

        // Only the Facilitator is assigned, so the control is visible to them and lacks CO, SoQM lead and PO
        Control control = createControl("CTRL-NA-" + s, soqm, "REVIEW");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(facilitator.getMail());
        assignmentRepository.save(assignment);

        // SoQM sent it back to the operator; the later comment keeps the step and is not a move
        LocalDate today = DeadlineOverdue.today(Instant.now());
        saveHistory(control, WorkflowActionType.SUBMIT_TO_OPERATOR, "IN_PROGRESS", "REVIEW", today.minusDays(5));
        saveHistory(control, WorkflowActionType.SUBMIT_TO_SOQM_TEAM, "REVIEW", "SOQM_HEAD_REVIEW", today.minusDays(4));
        saveHistory(control, WorkflowActionType.RETURN_TO_OPERATOR, "SOQM_HEAD_REVIEW", "REVIEW", today.minusDays(2));
        saveHistory(control, WorkflowActionType.COMMENT, "REVIEW", "REVIEW", today);

        for (User viewer : List.of(soqm, admin)) {
            mockMvc.perform(get("/").session(login(viewer.getMail())))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeExists("needsAttention"))
                    .andExpect(content().string(containsString("id=\"needsAttention\"")))
                    .andExpect(content().string(containsString(control.getControlId())))
                    .andExpect(content().string(containsString("No CO, SoQM, PO")))
                    .andExpect(content().string(containsString("Returned by SoQM · 2d ago")));
        }

        mockMvc.perform(get("/").session(login(facilitator.getMail())))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("needsAttention"))
                .andExpect(content().string(not(containsString("id=\"needsAttention\""))))
                .andExpect(content().string(not(containsString("No CO, SoQM, PO"))));
    }

    @Test
    void overdueTile_deadlinesBlock_andOverdueList_agree_forSoqmAdminAndFacilitator() throws Exception {
        String s = suffix();
        User facilitator = saveUser("ov-fac-" + s, "ov-fac-" + s + "@example.test", "FACILITATOR");
        User otherFacilitator = saveUser("ov-other-" + s, "ov-other-" + s + "@example.test", "FACILITATOR");
        User soqm = saveUser("ov-soqm-" + s, "ov-soqm-" + s + "@example.test", "SOQM_TEAM");
        User admin = saveUser("ov-admin-" + s, "ov-admin-" + s + "@example.test", "ADMIN");
        admin.setAdminAccess(true);
        userRepository.save(admin);
        LocalDate today = DeadlineOverdue.today(Instant.now());

        Control openOverdue = deadlineControl("CTRL-OV-OPEN-" + s, facilitator, "REVIEW", today.minusDays(4));
        Control draftOverdue = deadlineControl("CTRL-OV-DRAFT-" + s, facilitator, "DRAFT", today.minusDays(2));
        Control closedLate = deadlineControl("CTRL-OV-LATE-" + s, facilitator, "COMPLETED", today.minusDays(6));
        saveHistory(closedLate, WorkflowActionType.APPROVE, "PROCESS_OWNER_REVIEW", "COMPLETED", today.minusDays(1));
        Control dueToday = deadlineControl("CTRL-OV-TODAY-" + s, facilitator, "IN_PROGRESS", today);
        Control othersOverdue = deadlineControl("CTRL-OV-OTHER-" + s, otherFacilitator, "PROCESS_OWNER_REVIEW",
                today.minusDays(1));

        // Facilitator: their open controls only, drafts hidden
        assertThat(overdueEverywhere(facilitator)).containsExactly(openOverdue.getId());

        // SoQM and admin: every control, drafts included; never a completed one
        for (User viewer : List.of(soqm, admin)) {
            assertThat(overdueEverywhere(viewer))
                    .as(viewer.getRole())
                    .contains(openOverdue.getId(), draftOverdue.getId(), othersOverdue.getId())
                    .doesNotContain(closedLate.getId(), dueToday.getId());
        }

        // The control finished after its deadline is labelled "Closed late" in the list instead
        MvcResult completed = mockMvc.perform(get("/controls").param("filter", "COMPLETED").session(login(soqm.getMail())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(controlsIn(completed))
                .filteredOn(control -> closedLate.getId().equals(control.getId()))
                .singleElement()
                .satisfies(control -> {
                    assertThat(control.isClosedLate()).isTrue();
                    assertThat(control.isOverdue()).isFalse();
                });
        assertThat(completed.getResponse().getContentAsString()).contains(">Closed late<");
    }

    /**
     * The Overdue tile on the dashboard, the total in the deadlines block and the Overdue list
     * behind "View all" must show the same number; returns the ids in that list.
     */
    private List<Long> overdueEverywhere(User viewer) throws Exception {
        MockHttpSession session = login(viewer.getMail());

        Object tile = mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("overdueControls");

        String block = mockMvc.perform(get("/api/dashboard/deadline-countdown").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int blockTotal = com.jayway.jsonpath.JsonPath.read(block, "$.overdueTotal");

        MvcResult list = mockMvc.perform(get("/controls").param("filter", "OVERDUE").session(session))
                .andExpect(status().isOk())
                .andReturn();
        List<com.kpmg.qtracker.dto.ControlResponseDTO> listed = controlsIn(list);

        assertThat(tile).as("tile vs deadlines block for " + viewer.getRole()).isEqualTo(blockTotal);
        assertThat(listed).as("View all list for " + viewer.getRole()).hasSize(blockTotal);
        assertThat(list.getModelAndView().getModel().get("overdueControls")).isEqualTo(blockTotal);
        assertThat(listed).allSatisfy(control -> assertThat(control.isOverdue()).isTrue());
        return listed.stream().map(com.kpmg.qtracker.dto.ControlResponseDTO::getId).toList();
    }

    @SuppressWarnings("unchecked")
    private List<com.kpmg.qtracker.dto.ControlResponseDTO> controlsIn(MvcResult result) {
        return (List<com.kpmg.qtracker.dto.ControlResponseDTO>) result.getModelAndView().getModel().get("controls");
    }

    @Test
    void emailCase_isIgnored_forLoginUserLookupAndDuplicateCheck() throws Exception {
        String s = suffix();
        // Stored with capitals, as an older row might be
        User stored = saveUser("legacy-" + s, "Legacy.User-" + s + "@Example.TEST", "FACILITATOR");
        String lower = "legacy.user-" + s + "@example.test";

        MockHttpSession lowerCaseLogin = login(lower);
        assertThat(((User) lowerCaseLogin.getAttribute("currentUser")).getMail()).isEqualTo(stored.getMail());
        login(lower.toUpperCase());

        assertThat(userRepository.findByMail(" " + lower.toUpperCase() + " ")).map(User::getId).contains(stored.getId());
        assertThat(userRepository.existsByMail(lower)).isTrue();
        assertThatThrownBy(() -> userService.createUser(lower, "Copy", "FACILITATOR", false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void controlsList_findsTheControl_whenAssignedInAnotherCase() throws Exception {
        String s = suffix();
        User facilitator = saveUser("case-fac-" + s, "case-fac-" + s + "@example.test", "FACILITATOR");
        Control control = createControl("CTRL-CASE-" + s, facilitator, "IN_PROGRESS");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(" " + facilitator.getMail().toUpperCase());
        assignmentRepository.save(assignment);

        MvcResult list = mockMvc.perform(get("/controls").session(login(facilitator.getMail())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(controlsIn(list)).extracting(com.kpmg.qtracker.dto.ControlResponseDTO::getId)
                .contains(control.getId());
    }

    @Test
    void controlsList_matchesWholeAddresses_aDoesNotSeeTheControlOfBa() throws Exception {
        User a = saveUser("a-user", "a@kpmg.kz", "FACILITATOR");
        User ba = saveUser("ba-user", "ba@kpmg.kz", "FACILITATOR");
        LocalDate deadline = DeadlineOverdue.today(Instant.now()).plusDays(10);
        Control ownControl = deadlineControl("CTRL-A-" + suffix(), a, "IN_PROGRESS", deadline);
        // "a@kpmg.kz" is part of "ba@kpmg.kz", which a plain LIKE used to treat as a match
        Control baControl = deadlineControl("CTRL-BA-" + suffix(), ba, "IN_PROGRESS", deadline);

        MvcResult asA = mockMvc.perform(get("/controls").session(login(a.getMail())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(controlsIn(asA)).extracting(com.kpmg.qtracker.dto.ControlResponseDTO::getId)
                .contains(ownControl.getId())
                .doesNotContain(baControl.getId());

        MvcResult asBa = mockMvc.perform(get("/controls").session(login(ba.getMail())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(controlsIn(asBa)).extracting(com.kpmg.qtracker.dto.ControlResponseDTO::getId)
                .contains(baControl.getId())
                .doesNotContain(ownControl.getId());
    }

    private void saveHistory(Control control, WorkflowActionType type, String fromStep, String toStep, LocalDate day) {
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(control.getId());
        history.setActionType(type);
        history.setFromStep(fromStep);
        history.setToStep(toStep);
        history.setCreatedAt(day.atTime(10, 0));
        workflowHistoryRepository.save(history);
    }

    private Control deadlineControl(String controlId, User facilitator, String status, LocalDate deadline) {
        Control control = createControl(controlId, facilitator, status);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(facilitator.getMail());
        assignment.setControlOperationDeadline(deadline);
        assignmentRepository.save(assignment);
        return control;
    }

    private User saveUser(String username, String mail, String role) {
        User user = new User();
        user.setMail(mail);
        user.setRole(role);
        user.setDisplayName(username);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Control createControl(String controlId, User createdBy, String performanceStatus) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("HR");
        control.setOperatedBy("Finance");
        control.setPriority("High");
        control.setNonAuditServicesApplicability("No");
        control.setControlStatus(performanceStatus);
        control.setPerformanceStatus(performanceStatus);
        control.setCreatedBy(createdBy);
        // As ControlService does on create; the Controls list sorts by updated/created time
        control.setCreatedAt(java.time.LocalDateTime.now());
        Control saved = controlRepository.save(control);
        createdControlIds.add(saved.getId());
        return saved;
    }

    /** Body of the control form as view-control.js sends it; other fields match the createControl fixture. */
    private String controlForm(String frequency, String component, String controlStatus) {
        return "{\"controlFrequency\":\"" + frequency + "\",\"controlCategory\":\"Manual\","
                + "\"controlType\":\"Preventive\",\"component\":\"" + component + "\","
                + "\"operatedBy\":\"Finance\",\"priority\":\"High\",\"nonAuditServicesApplicability\":\"No\","
                + "\"controlStatus\":\"" + controlStatus + "\",\"controlDescription\":\"\",\"prp\":\"\"}";
    }

    /** One user per workflow role, each with a unique mail. */
    private Participants participants() {
        String s = suffix();
        return new Participants(
                saveUser("fac-" + s, "fac-" + s + "@example.test", "FACILITATOR"),
                saveUser("op-" + s, "op-" + s + "@example.test", "CONTROL_OPERATOR"),
                saveUser("soqm-" + s, "soqm-" + s + "@example.test", "SOQM_TEAM"),
                saveUser("po-" + s, "po-" + s + "@example.test", "PROCESS_OWNER"));
    }

    private void assign(Control control, Participants p) {
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(p.facilitator.getMail());
        assignment.setControlOperator(p.operator.getMail());
        assignment.setSoqmLead(p.soqm.getMail());
        assignment.setProcessOwner(p.owner.getMail());
        assignment.setControlOperationDate(LocalDate.of(2026, 1, 15));
        assignmentRepository.save(assignment);
    }

    private record Participants(User facilitator, User operator, User soqm, User owner) {
    }

    /** Logs in through the form login filter; its success handler puts currentUser into the session. */
    private MockHttpSession login(String mail) throws Exception {
        // Each login from its own address: RateLimitingFilter allows 20 logins per minute per address
        String clientAddress = "10.0." + (loginCount / 250) + "." + (1 + loginCount++ % 250);
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                        })
                        .param("username", mail)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("currentUser")).isNotNull();
        return session;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
