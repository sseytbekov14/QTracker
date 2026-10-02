package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.SoqmYear;
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

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
    private AdminAuditLogRepository auditLogRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationRepository notificationRepository;

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    @MockitoSpyBean
    private WorkflowHistoryRepository workflowHistoryRepository;

    private static int loginCount;

    private final List<Long> createdControlIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdNotificationIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        notificationRepository.deleteAllById(createdNotificationIds);
        createdNotificationIds.clear();
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

        // The former "My Approvals" page and its API are gone
        mockMvc.perform(get("/api/workflow/my-approvals")
                        .session(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/workflow/approvals")
                        .session(session))
                .andExpect(status().isNotFound());
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
    void viewControl_showsAsRequiredFrequencyAsStored_notAsAdHoc() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-ASR-" + suffix(), p.soqm, "IN_PROGRESS");
        // The server reads it as Annual; shown as Ad-hoc the page previewed other dates and Save rewrote it
        control.setControlFrequency("As-required/at least annually");
        controlRepository.save(control);

        String html = mockMvc.perform(get("/view-control/{id}", control.getId()).session(login(p.soqm.getMail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-stored-value=\"As-required/at least annually\"");
        // No option is selected, so view-control.js gives the stored value an option of its own
        String frequencySelect = html.substring(html.indexOf("name=\"controlFrequency\""));
        frequencySelect = frequencySelect.substring(0, frequencySelect.indexOf("</select>"));
        assertThat(frequencySelect).doesNotContain("selected");
    }

    @Test
    void soqm_savingAssignment_storesAndLogsTheServerSchedule_notTheOneSent() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-SCH-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);

        // Monthly from 31 January: the next date is the end of February. A browser that rolled the day
        // over would send 3 March; the page's values must not reach the database or the log.
        mockMvc.perform(post("/api/control-assignment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId()
                                + ",\"controlOperationDate\":\"2026-01-31\""
                                + ",\"controlOperationDeadline\":\"2026-02-09\""
                                + ",\"nextControlOperationDate\":\"2026-03-03\"}")
                        .session(login(p.soqm.getMail())))
                .andExpect(status().isOk());

        ControlAssignment stored = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(stored.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 2, 7));
        assertThat(stored.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        String loggedValues = auditLogRepository.findByControlIdOrderByCreatedAtDesc(control.getId())
                .get(0).getNewValues();
        assertThat(loggedValues)
                .contains("\"Control Operation Deadline\":\"2026-02-07\"")
                .contains("\"Next Control Operation Date\":\"2026-02-28\"")
                .doesNotContain("2026-02-09")
                .doesNotContain("2026-03-03");
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
    void assignedFacilitator_unchangedControlForm_savesNothing() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-NOOP-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        // "Annually" is shown as Annual and would be rewritten by a save, which also recalculates the schedule
        LocalDateTime updatedAt = LocalDateTime.of(2026, 1, 10, 9, 0);
        control.setControlFrequency("Annually");
        control.setUpdatedAt(updatedAt);
        controlRepository.save(control);
        LocalDate sentinelDeadline = LocalDate.of(2030, 12, 31);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlOperationDeadline(sentinelDeadline);
        assignmentRepository.save(assignment);

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlForm("Annual", "HR", "IN_PROGRESS"))
                        .session(login(p.facilitator.getMail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlFrequency").value("Annually"));

        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(stored.getControlFrequency()).isEqualTo("Annually");
        assertThat(stored.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(assignmentRepository.findByControlId(control.getId()).orElseThrow().getControlOperationDeadline())
                .isEqualTo(sentinelDeadline);
        assertThat(auditLogRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }

    @Test
    void assignedProcessOwner_changingOwnComment_isSaved() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-POC-" + suffix(), p.soqm, "PROCESS_OWNER_REVIEW");
        assign(control, p);

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processOwnerComments\":\"Checked by PO\"}")
                        .session(login(p.owner.getMail())))
                .andExpect(status().isOk());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getProcessOwnerComments())
                .isEqualTo("Checked by PO");
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
    void editButtonOnDraft_isRenderedForSoqmAndAdmin_notForFacilitator() throws Exception {
        Participants p = participants();
        User admin = saveUser("draft-admin-" + suffix(), "draft-admin-" + suffix() + "@example.test", "ADMIN");
        admin.setAdminAccess(true);
        userRepository.save(admin);
        Control control = createControl("CTRL-DRAFT-EDIT-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);

        for (User viewer : List.of(p.soqm, admin)) {
            mockMvc.perform(get("/view-control/{id}", control.getId()).session(login(viewer.getMail())))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"editBtn\"")))
                    .andExpect(content().string(containsString("id=\"canEditAll\" value=\"true\"")));
        }

        mockMvc.perform(get("/view-control/{id}", control.getId()).session(login(p.facilitator.getMail())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"editBtn\""))))
                .andExpect(content().string(containsString("id=\"canEditAll\" value=\"false\"")));
    }

    @Test
    void editControlUrl_redirectsToViewControl_sharedViewerGetsNoDraftMasterData() throws Exception {
        String s = suffix();
        User soqm = saveUser("ec-soqm-" + s, "ec-soqm-" + s + "@example.test", "SOQM_TEAM");
        User shared = saveUser("ec-shared-" + s, "ec-shared-" + s + "@example.test", "FACILITATOR");
        Control control = createControl("CTRL-EC-" + s, soqm, "DRAFT");
        control.setControlDescription("Draft description " + s);
        controlRepository.save(control);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlSharedWith(shared.getMail());
        assignmentRepository.save(assignment);
        String viewUrl = "/view-control/" + control.getId() + "#control";

        MockHttpSession sharedSession = login(shared.getMail());
        mockMvc.perform(get("/edit-control/{id}", control.getId()).session(sharedSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(viewUrl))
                .andExpect(content().string(not(containsString("Draft description"))));

        // The page the redirect leads to keeps a draft away from a viewer it is only shared with
        mockMvc.perform(get("/view-control/{id}", control.getId()).session(sharedSession))
                .andExpect(status().isOk())
                .andExpect(view().name("control-not-available"))
                .andExpect(content().string(not(containsString("Draft description"))))
                .andExpect(content().string(not(containsString("name=\"controlFrequency\""))));

        MockHttpSession soqmSession = login(soqm.getMail());
        mockMvc.perform(get("/edit-control/{id}", control.getId()).session(soqmSession))
                .andExpect(redirectedUrl(viewUrl));
        mockMvc.perform(get("/view-control/{id}", control.getId()).session(soqmSession))
                .andExpect(status().isOk())
                .andExpect(view().name("view-control"))
                .andExpect(content().string(containsString("Draft description " + s)));
    }

    @Test
    void initiatePage_isForSoqmOnDrafts_everyoneElseGetsViewControl() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-INIT-VC-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        String viewUrl = "/view-control/" + control.getId();
        String initiateUrl = "/initiate/" + control.getId();

        MockHttpSession soqmSession = login(p.soqm.getMail());
        // The former checklist URL leads to the Initiate page
        mockMvc.perform(get("/performance/{id}", control.getId()).session(soqmSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(initiateUrl));
        String html = mockMvc.perform(get(initiateUrl).session(soqmSession))
                .andExpect(status().isOk())
                .andExpect(view().name("initiate-control"))
                .andExpect(content().string(containsString("Everything is in place")))
                .andExpect(content().string(containsString(p.facilitator.getDisplayName())))
                .andExpect(content().string(containsString("15.01.2026")))
                .andExpect(content().string(not(containsString(">Missing<"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(initiateButtonTag(html))
                .contains("data-control-id=\"" + control.getId() + "\"")
                .doesNotContain("disabled");
        // View Control keeps only a link to it in the header
        mockMvc.perform(get(viewUrl).session(soqmSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + initiateUrl + "\"")))
                .andExpect(content().string(not(containsString("id=\"initiateBtn\""))))
                .andExpect(content().string(not(containsString("Initiation checklist"))));

        MockHttpSession facilitatorSession = login(p.facilitator.getMail());
        mockMvc.perform(get("/performance/{id}", control.getId()).session(facilitatorSession))
                .andExpect(redirectedUrl(initiateUrl));
        mockMvc.perform(get(initiateUrl).session(facilitatorSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(viewUrl));
        mockMvc.perform(get(viewUrl).session(facilitatorSession))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"" + initiateUrl + "\""))));
        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "1 OCT 2026 - 30 SEP 2027")
                        .session(facilitatorSession))
                .andExpect(status().isForbidden());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("DRAFT");

        // Once initiated the page is gone for SoQM too
        moveTo(control, "IN_PROGRESS");
        mockMvc.perform(get(initiateUrl).session(soqmSession))
                .andExpect(redirectedUrl(viewUrl));
        mockMvc.perform(get(viewUrl).session(soqmSession))
                .andExpect(content().string(not(containsString("href=\"" + initiateUrl + "\""))));
    }

    @Test
    void draftsOpenOnTheInitiatePage_fromTheControlsListAndTheDashboard() throws Exception {
        Participants p = participants();
        LocalDate today = DeadlineOverdue.today(Instant.now());
        Control draft = deadlineControl("CTRL-OPEN-DRAFT-" + suffix(), p.facilitator, "DRAFT", today.minusDays(1));
        Control running = deadlineControl("CTRL-OPEN-RUN-" + suffix(), p.facilitator, "IN_PROGRESS", today.minusDays(1));
        String draftPage = "/initiate/" + draft.getId();
        MockHttpSession soqmSession = login(p.soqm.getMail());

        mockMvc.perform(get("/controls").session(soqmSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + draftPage + "\"")))
                .andExpect(content().string(not(containsString("href=\"/view-control/" + draft.getId() + "\""))))
                .andExpect(content().string(containsString("href=\"/performance-cycle/" + running.getId() + "\"")));
        mockMvc.perform(get("/api/dashboard/deadline-countdown").param("limit", "50").session(soqmSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue[?(@.id == " + draft.getId() + ")].url").value(draftPage))
                .andExpect(jsonPath("$.overdue[?(@.id == " + running.getId() + ")].url")
                        .value("/view-control/" + running.getId()));
        mockMvc.perform(get("/api/dashboard/deadline-calendar")
                        .param("start", today.minusDays(7).toString())
                        .param("end", today.plusDays(7).toString())
                        .session(soqmSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == '" + draft.getControlId() + "')].url").value(draftPage));
    }

    @Test
    void initiatePage_namesMissingItems_andKeepsInitiateDisabled() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-INIT-MISS-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setProcessOwner(null);
        assignmentRepository.save(assignment);

        String html = mockMvc.perform(get("/initiate/{id}", control.getId()).session(login(p.soqm.getMail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("1 item missing").contains("Fill in the missing items first.");
        // Only the Process Owner line is missing, with a link to the View Control tab that holds it
        assertThat(html.split(">Missing<", -1)).hasSize(2);
        assertThat(html.indexOf(">Process Owner<")).isLessThan(html.indexOf(">Missing<"));
        assertThat(html.indexOf(">Control Operation Date<")).isGreaterThan(html.indexOf(">Missing<"));
        assertThat(html).contains("href=\"/view-control/" + control.getId() + "#assignment\"");
        assertThat(initiateButtonTag(html))
                .contains("disabled=\"disabled\"")
                .contains("aria-describedby=\"initiateNotReady\"");
    }

    /** The opening tag of the Initiate button; Thymeleaf decides the attribute order. */
    private String initiateButtonTag(String html) {
        int start = html.lastIndexOf("<button", html.indexOf("id=\"initiateBtn\""));
        return html.substring(start, html.indexOf('>', start));
    }

    @Test
    void completedControlExport_hasCreated_andNoActualOperationDate() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-EXPORT-" + suffix(), p.soqm, "COMPLETED");
        assign(control, p);

        byte[] xlsx = mockMvc.perform(get("/api/controls/{id}/export/completed", control.getId())
                        .session(login(p.soqm.getMail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<String> fields = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (Row row : workbook.getSheetAt(0)) {
                fields.add(row.getCell(0).getStringCellValue());
            }
        }
        assertThat(fields).contains("Created").doesNotContain("Created At", "Actual Operation Date");
    }

    @Test
    void initiatePage_offersSoqmYears_withTheCurrentOnePreselected() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-YEAR-PICK-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        LocalDate today = DeadlineOverdue.today(Instant.now());

        String html = mockMvc.perform(get("/initiate/{id}", control.getId()).session(login(p.soqm.getMail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String select = html.substring(html.indexOf("id=\"initiateSoqmYear\""), html.indexOf("</select>", html.indexOf("id=\"initiateSoqmYear\"")));
        for (String year : SoqmYear.options(today)) {
            assertThat(select).contains("value=\"" + year + "\"");
        }
        assertThat(select).contains("value=\"" + SoqmYear.current(today) + "\" selected=\"selected\"");
    }

    @Test
    void initiate_withSomethingThatIsNotASoqmYear_returns400() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-YEAR-BAD-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "2026-27")
                        .session(login(p.soqm.getMail())))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("SoQM Year must be a SoQM year")));

        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(stored.getPerformanceStatus()).isEqualTo("DRAFT");
        assertThat(stored.getSoqmYear()).isNull();
    }

    @Test
    void soqmYear_isChangedThroughEdit_onlyBySoqm_andAudited() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-YEAR-EDIT-" + suffix(), p.soqm, "IN_PROGRESS");
        control.setSoqmYear("1 OCT 2025 - 30 SEP 2026");
        controlRepository.save(control);
        assign(control, p);
        String newYear = "1 OCT 2026 - 30 SEP 2027";

        MockHttpSession facilitatorSession = login(p.facilitator.getMail());
        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlFormWithSoqmYear(newYear))
                        .session(facilitatorSession))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("SoQM Year can be changed only by SoQM Team")));
        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlFormWithSoqmYear("1 OCT 2025 - 30 SEP 2026"))
                        .session(facilitatorSession))
                .andExpect(status().isOk());

        MockHttpSession soqmSession = login(p.soqm.getMail());
        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlFormWithSoqmYear("2031"))
                        .session(soqmSession))
                .andExpect(status().isBadRequest());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getSoqmYear())
                .isEqualTo("1 OCT 2025 - 30 SEP 2026");

        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(controlFormWithSoqmYear(newYear))
                        .session(soqmSession))
                .andExpect(status().isOk());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getSoqmYear()).isEqualTo(newYear);
        assertThat(auditLogRepository.findByControlIdOrderByCreatedAtDesc(control.getId()))
                .anySatisfy(log -> {
                    assertThat(log.getChangedFields()).contains("soqm_year");
                    assertThat(log.getPreviousValues()).contains("1 OCT 2025 - 30 SEP 2026");
                    assertThat(log.getNewValues()).contains(newYear);
                    assertThat(log.getAdminEmail()).isEqualTo(p.soqm.getMail());
                });
    }

    @Test
    void checklistEndpoints_areNotExposed() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-PERF-API-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/performance/auto-save")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "1 OCT 2026 - 30 SEP 2027")
                        .session(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performance/{id}", control.getId()).session(session))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performance/performance-cycle/{id}", control.getId()).session(session))
                .andExpect(status().isNotFound());

        assertThat(controlRepository.findById(control.getId()).orElseThrow().getSoqmYear()).isNull();
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
    void unlistedSelectValues_areRenderedForTheSelect_andKeptWhenSaveLeavesThemOut() throws Exception {
        Participants p = participants();
        // createControl stores Control Type "Preventive" and Operated By "Finance", which no option carries;
        // older or imported rows hold such values too
        Control control = createControl("CTRL-UNL-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        control.setControlFrequency("Annual/Semi-annual");
        control.setHomogeneity("Homogeneous");
        controlRepository.save(control);
        var documents = documentsRepository.findByControlId(control.getId()).orElseThrow();
        documents.setSoqmDevelopmentMaterials("Partly available");
        documentsRepository.save(documents);

        MockHttpSession session = login(p.soqm.getMail());

        // view-control.js adds these values to their selects as options of their own
        mockMvc.perform(get("/view-control/{id}", control.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-stored-value=\"Annual/Semi-annual\"")))
                .andExpect(content().string(containsString("data-stored-value=\"Preventive\"")))
                .andExpect(content().string(containsString("data-stored-value=\"Finance\"")));

        // While those options stay selected Save sends the fields as null; other fields still change
        mockMvc.perform(put("/api/controls/{id}", control.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlFrequency\":null,\"controlCategory\":null,\"controlType\":null,"
                                + "\"component\":\"GOV\",\"operatedBy\":null,\"controlStatus\":null,\"priority\":\"Low\","
                                + "\"nonAuditServicesApplicability\":null,\"controlDescription\":\"\",\"prp\":\"\"}")
                        .session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/control-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"processName\":\"Payroll\",\"homogeneity\":null}")
                        .session(session))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/control-documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + control.getId() + ",\"soqmDevelopmentMaterials\":null}")
                        .session(session))
                .andExpect(status().isOk());

        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(stored.getControlFrequency()).isEqualTo("Annual/Semi-annual");
        assertThat(stored.getControlType()).isEqualTo("Preventive");
        assertThat(stored.getOperatedBy()).isEqualTo("Finance");
        assertThat(stored.getHomogeneity()).isEqualTo("Homogeneous");
        assertThat(stored.getComponent()).isEqualTo("GOV");
        assertThat(stored.getPriority()).isEqualTo("Low");
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getProcessName()).isEqualTo("Payroll");
        assertThat(documentsRepository.findByControlId(control.getId()).orElseThrow().getSoqmDevelopmentMaterials())
                .isEqualTo("Partly available");
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
        // perform-action has no Initiate either: it would skip the required fields and the workflow steps
        for (String action : List.of("INITIATE", "SUBMIT_FOR_REVIEW")) {
            mockMvc.perform(post("/api/workflow/perform-action")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"controlId\":" + control.getId() + ",\"action\":\"" + action + "\"}")
                            .session(session))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Unsupported workflow action")));
        }
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("DRAFT");

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "1 OCT 2026 - 30 SEP 2027")
                        .session(session))
                .andExpect(status().isOk());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getPerformanceStatus())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void initiate_withoutProcessOwnerOrSoqmYear_returns400_namingWhatIsMissing() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-INIT-REQ-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setProcessOwner(null);
        assignmentRepository.save(assignment);
        MockHttpSession session = login(p.soqm.getMail());

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "1 OCT 2026 - 30 SEP 2027")
                        .session(session))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Required fields are missing: Process Owner"));

        assignment.setProcessOwner(p.owner.getMail());
        assignmentRepository.save(assignment);
        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Required fields are missing: SoQM Year"));

        Control stored = controlRepository.findById(control.getId()).orElseThrow();
        assertThat(stored.getPerformanceStatus()).isEqualTo("DRAFT");
        assertThat(stored.getSoqmYear()).isNull();
        assertThat(workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }

    @Test
    void initiateBySoqm_recordsSoqmAsThePerformer_notTheFacilitator() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-INIT-WHO-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);

        mockMvc.perform(post("/api/performance/initiate")
                        .param("controlId", String.valueOf(control.getId()))
                        .param("soqmYear", "1 OCT 2026 - 30 SEP 2027")
                        .session(login(p.soqm.getMail())))
                .andExpect(status().isOk());

        List<WorkflowHistory> initiated = workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(control.getId())
                .stream()
                .filter(h -> h.getActionType() == WorkflowActionType.INITIATE)
                .toList();
        assertThat(initiated).singleElement().satisfies(h -> {
            assertThat(h.getPerformedByEmail()).isEqualTo(p.soqm.getMail());
            assertThat(h.getPerformedByName()).isEqualTo(p.soqm.getDisplayName());
            assertThat(h.getToStep()).isEqualTo("IN_PROGRESS");
            assertThat(h.getComments()).isNull();
        });
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
    void dashboard_hasNoNeedsAttentionBlock_forSoqmOrAdmin() throws Exception {
        String s = suffix();
        User soqm = saveUser("na-soqm-" + s, "na-soqm-" + s + "@example.test", "SOQM_TEAM");
        User admin = saveUser("na-admin-" + s, "na-admin-" + s + "@example.test", "ADMIN");
        admin.setAdminAccess(true);
        userRepository.save(admin);
        // Lacks CO, SoQM lead and PO: the removed block used to list it
        createControl("CTRL-NA-" + s, soqm, "REVIEW");

        for (User viewer : List.of(soqm, admin)) {
            mockMvc.perform(get("/").session(login(viewer.getMail())))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeDoesNotExist("needsAttention"))
                    .andExpect(content().string(not(containsString("Needs attention"))))
                    .andExpect(content().string(containsString("Awaiting my action")));
        }
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

    // ---- Reads of one control follow the View Control rule ----

    private static final List<String> TAB_READS =
            List.of("/api/control-details", "/api/control-assignment", "/api/control-documents");

    @Test
    void controlTabReads_participant200_stranger403_unknownControl404() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-TAB-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        MockHttpSession facilitator = login(p.facilitator.getMail());
        MockHttpSession stranger = login(saveUser("stranger-" + suffix(), "stranger-" + suffix() + "@example.test",
                "FACILITATOR").getMail());

        for (String path : TAB_READS) {
            mockMvc.perform(get(path).param("controlId", String.valueOf(control.getId()))
                            .with(ownAddress()).session(facilitator))
                    .andExpect(status().isOk());
            mockMvc.perform(get(path).param("controlId", String.valueOf(control.getId()))
                            .with(ownAddress()).session(stranger))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(get(path).param("controlId", String.valueOf(UNKNOWN_CONTROL_ID))
                            .with(ownAddress()).session(facilitator))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mockMvc.perform(get("/api/control-assignment").param("controlId", String.valueOf(control.getId()))
                        .with(ownAddress()).session(facilitator))
                .andExpect(jsonPath("$.processOwner[0]").value(p.owner.getMail()));
    }

    @Test
    void controlTabReads_sharedOnlyUser_403OnDraft_200AfterInitiation() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-TAB-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        User shared = shareWith(control, "shared-" + suffix() + "@example.test");
        MockHttpSession session = login(shared.getMail());

        for (String path : TAB_READS) {
            mockMvc.perform(get(path).param("controlId", String.valueOf(control.getId()))
                            .with(ownAddress()).session(session))
                    .andExpect(status().isForbidden());
        }

        moveTo(control, "IN_PROGRESS");

        for (String path : TAB_READS) {
            mockMvc.perform(get(path).param("controlId", String.valueOf(control.getId()))
                            .with(ownAddress()).session(session))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void attachmentInfoAndDownload_participant200_stranger403_unknownControl404() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-DL-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        MockHttpSession facilitator = login(p.facilitator.getMail());
        MockHttpSession stranger = login(saveUser("stranger-" + suffix(), "stranger-" + suffix() + "@example.test",
                "FACILITATOR").getMail());
        String stored = upload(control, facilitator, "evidence-" + suffix() + ".pdf");

        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).with(ownAddress()).session(facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentDetailsPath").value(stored));
        download(control.getId(), stored, facilitator)
                .andExpect(status().isOk())
                .andExpect(content().string("%PDF-1.4 test"));

        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).with(ownAddress()).session(stranger))
                .andExpect(status().isForbidden());
        download(control.getId(), stored, stranger).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/attachments/info/{id}", UNKNOWN_CONTROL_ID).with(ownAddress()).session(facilitator))
                .andExpect(status().isNotFound());
        download(UNKNOWN_CONTROL_ID, stored, facilitator).andExpect(status().isNotFound());
    }

    @Test
    void attachmentInfoAndDownload_sharedOnlyUser_403OnDraft_200AfterInitiation() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-DL-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        String stored = upload(control, login(p.soqm.getMail()), "draft-" + suffix() + ".pdf");
        MockHttpSession shared = login(shareWith(control, "shared-" + suffix() + "@example.test").getMail());

        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).with(ownAddress()).session(shared))
                .andExpect(status().isForbidden());
        download(control.getId(), stored, shared).andExpect(status().isForbidden());

        moveTo(control, "IN_PROGRESS");

        mockMvc.perform(get("/api/attachments/info/{id}", control.getId()).with(ownAddress()).session(shared))
                .andExpect(status().isOk());
        download(control.getId(), stored, shared).andExpect(status().isOk());
    }

    @Test
    void download_servesOnlyFilesTheControlLists_includingOldOnesInTheUploadRoot() throws Exception {
        Participants p = participants();
        Control mine = createControl("CTRL-DL-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(mine, p);
        Control other = createControl("CTRL-DL-" + suffix(), p.soqm, "IN_PROGRESS");
        MockHttpSession facilitator = login(p.facilitator.getMail());
        String othersFile = upload(other, login(p.soqm.getMail()), "other-" + suffix() + ".pdf");

        // Uploaded before control folders existed: in the upload root, listed on the control
        String oldFile = "20260115_" + suffix() + "_old.pdf";
        java.nio.file.Path root = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "it-uploads"));
        java.nio.file.Files.writeString(root.resolve(oldFile), "old file");
        Control stored = controlRepository.findById(mine.getId()).orElseThrow();
        stored.setAttachmentDocumentsPath(oldFile);
        controlRepository.save(stored);

        download(mine.getId(), oldFile, facilitator)
                .andExpect(status().isOk())
                .andExpect(content().string("old file"));
        // Another control's file, asked for through a control the user can read
        download(mine.getId(), othersFile, facilitator).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions download(Long controlId, String fileName,
                                                                       MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/api/attachments/download/{name}", fileName)
                .param("controlId", String.valueOf(controlId))
                .with(ownAddress())
                .session(session));
    }

    @Test
    void changelog_participant200_unknownControl404_sharedOnlyUser403OnDraft() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-LOG-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        MockHttpSession facilitator = login(p.facilitator.getMail());
        MockHttpSession shared = login(shareWith(control, "shared-" + suffix() + "@example.test").getMail());

        mockMvc.perform(get("/api/controls/{id}/changelog", control.getId()).with(ownAddress()).session(facilitator))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/controls/{id}/changelog", UNKNOWN_CONTROL_ID).with(ownAddress()).session(facilitator))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/controls/{id}/changelog", control.getId()).with(ownAddress()).session(shared))
                .andExpect(status().isForbidden());

        moveTo(control, "IN_PROGRESS");

        mockMvc.perform(get("/api/controls/{id}/changelog", control.getId()).with(ownAddress()).session(shared))
                .andExpect(status().isOk());
    }

    // ---- User lists: everyone for SoQM Team and admins, the control's people by name and e-mail for others ----

    @Test
    void usersAll_soqmGetsEveryone_othersOnlyThePeopleOnAControlTheyRead() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-USR-" + suffix(), p.soqm, "IN_PROGRESS");
        assign(control, p);
        User shared = shareWith(control, "shared-" + suffix() + "@example.test");
        User stranger = saveUser("stranger-" + suffix(), "stranger-" + suffix() + "@example.test", "FACILITATOR");

        String everyone = mockMvc.perform(get("/api/users/all").with(ownAddress()).session(login(p.soqm.getMail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(everyone).contains(stranger.getMail(), "\"role\":\"FACILITATOR\"");

        MockHttpSession facilitator = login(p.facilitator.getMail());
        mockMvc.perform(get("/api/users/all").param("controlId", String.valueOf(control.getId()))
                        .with(ownAddress()).session(facilitator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].mail").value(org.hamcrest.Matchers.containsInAnyOrder(
                        p.facilitator.getMail(), p.operator.getMail(), p.soqm.getMail(), p.owner.getMail(),
                        shared.getMail())))
                .andExpect(jsonPath("$[0].displayName").value(p.facilitator.getDisplayName()))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].role").doesNotExist())
                .andExpect(jsonPath("$[0].enabled").doesNotExist());

        mockMvc.perform(get("/api/users/all").with(ownAddress()).session(facilitator))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/all").param("controlId", String.valueOf(control.getId()))
                        .with(ownAddress()).session(login(stranger.getMail())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/all").param("controlId", String.valueOf(UNKNOWN_CONTROL_ID))
                        .with(ownAddress()).session(facilitator))
                .andExpect(status().isNotFound());
    }

    @Test
    void usersAll_sharedOnlyUser_403OnDraft() throws Exception {
        Participants p = participants();
        Control control = createControl("CTRL-USR-" + suffix(), p.soqm, "DRAFT");
        assign(control, p);
        MockHttpSession shared = login(shareWith(control, "shared-" + suffix() + "@example.test").getMail());

        mockMvc.perform(get("/api/users/all").param("controlId", String.valueOf(control.getId()))
                        .with(ownAddress()).session(shared))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersByRole_andUserList_onlySoqmAndAdmins() throws Exception {
        Participants p = participants();
        User admin = saveUser("admin-" + suffix(), "admin-" + suffix() + "@example.test", "PROCESS_OWNER");
        admin.setAdminAccess(true);
        userRepository.save(admin);
        MockHttpSession soqm = login(p.soqm.getMail());
        MockHttpSession adminSession = login(admin.getMail());
        MockHttpSession facilitator = login(p.facilitator.getMail());

        for (String path : List.of("/api/users/role/FACILITATOR", "/api/users")) {
            mockMvc.perform(get(path).with(ownAddress()).session(soqm)).andExpect(status().isOk());
            mockMvc.perform(get(path).with(ownAddress()).session(adminSession)).andExpect(status().isOk());
            mockMvc.perform(get(path).with(ownAddress()).session(facilitator)).andExpect(status().isForbidden());
        }
    }

    // ---- Admin Panel: stored roles the lists do not offer are shown as they are and never rewritten ----

    @Test
    void adminPage_unlistedStoredRoles_getTheirOwnSelectedOption() throws Exception {
        MockHttpSession adminSession = login(adminUser().getMail());
        User adminRole = saveUser("role-admin", "role-admin-" + suffix() + "@example.test", "ADMIN");
        User spelling = saveUser("role-spelling", "role-spelling-" + suffix() + "@example.test", "SoQM Team");
        User comma = saveUser("role-comma", "role-comma-" + suffix() + "@example.test", "FACILITATOR,PROCESS_OWNER");
        User listed = saveUser("role-listed", "role-listed-" + suffix() + "@example.test", "CONTROL_OPERATOR");
        listed.setSecondaryRole("Facilitator");
        userRepository.save(listed);

        String html = mockMvc.perform(get("/admin/users").session(adminSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(selectedOptions(html, adminRole, "js-role")).containsExactly("ADMIN (not in list)");
        assertThat(selectedOptions(html, spelling, "js-role")).containsExactly("SoQM Team (not in list)");
        assertThat(selectedOptions(html, comma, "js-role")).containsExactly("FACILITATOR,PROCESS_OWNER (not in list)");
        assertThat(selectedOptions(html, adminRole, "js-secondary-role")).containsExactly("None");
        assertThat(selectedOptions(html, listed, "js-role")).containsExactly("Control Operator");
        assertThat(selectedOptions(html, listed, "js-secondary-role")).containsExactly("Facilitator (not in list)");
        assertThat(roleSelect(html, adminRole, "js-role"))
                .contains("value=\"\"", "data-stored-value=\"true\"", "data-stored-text=\"ADMIN\"");
        assertThat(roleSelect(html, adminRole, "js-secondary-role")).contains("value=\"NONE\"");
    }

    @Test
    void accessUpdate_blankRoles_keepUnlistedStoredValues() throws Exception {
        MockHttpSession adminSession = login(adminUser().getMail());
        for (String storedRole : List.of("ADMIN", "SoQM Team", "FACILITATOR,PROCESS_OWNER")) {
            User target = saveUser("keep-role", "keep-role-" + suffix() + "@example.test", storedRole);
            target.setSecondaryRole("Facilitator");
            userRepository.save(target);

            mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(ownAddress()).session(adminSession)
                            .param("role", "")
                            .param("secondaryRole", "")
                            .param("adminAccess", "false")
                            .param("enabled", "false"))
                    .andExpect(status().isOk());

            User saved = userRepository.findById(target.getId()).orElseThrow();
            assertThat(saved.getRole()).isEqualTo(storedRole);
            assertThat(saved.getSecondaryRole()).isEqualTo("Facilitator");
            assertThat(saved.getEnabled()).isFalse();
        }
    }

    @Test
    void accessUpdate_noneClearsAdditionalRole_andAListedRoleReplacesTheStoredOne() throws Exception {
        MockHttpSession adminSession = login(adminUser().getMail());
        User target = saveUser("change-role", "change-role-" + suffix() + "@example.test", "ADMIN");
        target.setSecondaryRole("FACILITATOR");
        userRepository.save(target);

        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(ownAddress()).session(adminSession)
                        .param("role", "PROCESS_OWNER")
                        .param("secondaryRole", "NONE")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PROCESS_OWNER"))
                .andExpect(jsonPath("$.secondaryRole").doesNotExist());
    }

    @Test
    void adminPage_auditTrail_showsUserAccessFirst_otherTypesBehindTheFilter() throws Exception {
        User admin = adminUser();
        MockHttpSession adminSession = login(admin.getMail());
        User target = saveUser("Trail Target", "trail-target-" + suffix() + "@example.test", "FACILITATOR");
        mockMvc.perform(post("/api/users/" + target.getId() + "/access").with(ownAddress()).session(adminSession)
                        .param("role", "")
                        .param("secondaryRole", "")
                        .param("enabled", "false"))
                .andExpect(status().isOk());
        String otherType = "VIEW_" + suffix().toUpperCase();
        com.kpmg.qtracker.entity.AdminAuditLog other = new com.kpmg.qtracker.entity.AdminAuditLog();
        other.setAdminEmail(admin.getMail());
        other.setActionType(otherType);
        other.setActionDescription("Viewed " + otherType);
        auditLogRepository.save(other);

        String html = mockMvc.perform(get("/admin/users").session(adminSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<th scope=\"col\">Changed by</th>", "id=\"auditTargetHeader\">Target user</th>",
                "value=\"OTHER\"");
        assertThat(html).containsPattern("value=\"USER_ACCESS\"[^>]*selected=\"selected\"");
        int row = html.indexOf(target.getMail() + "</span>", html.indexOf("auditTargetHeader"));
        assertThat(row).as("audit row naming the target user").isPositive();
        String userRow = html.substring(html.lastIndexOf("<tr", row), html.indexOf("</tr>", row));
        assertThat(userRow).contains("data-group=\"USER_ACCESS\"", "Changed status from ACTIVE to INACTIVE</td>",
                "Trail Target").doesNotContain("d-none");
        int otherRow = html.indexOf("Viewed " + otherType);
        assertThat(html.substring(html.lastIndexOf("<tr", otherRow), otherRow))
                .contains("data-group=\"OTHER\"", "d-none");
    }

    @Test
    void formUpdateEndpoint_isGone_andChangesNothing() throws Exception {
        MockHttpSession adminSession = login(adminUser().getMail());
        User target = saveUser("form-target", "form-target-" + suffix() + "@example.test", "FACILITATOR");

        mockMvc.perform(post("/admin/users/" + target.getId() + "/update").with(csrf()).session(adminSession)
                        .param("role", "PROCESS_OWNER")
                        .param("enabled", "false"))
                .andExpect(status().isNotFound());

        User unchanged = userRepository.findById(target.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo("FACILITATOR");
        assertThat(unchanged.getEnabled()).isTrue();
    }

    private User adminUser() {
        User admin = saveUser("panel-admin", "panel-admin-" + suffix() + "@example.test", "PROCESS_OWNER");
        admin.setAdminAccess(true);
        return userRepository.save(admin);
    }

    /** The markup of one row's select (class js-role or js-secondary-role) on the Admin Panel. */
    private static String roleSelect(String html, User user, String selectClass) {
        int row = html.indexOf("data-user-id=\"" + user.getId() + "\"");
        assertThat(row).as("row of user %s", user.getId()).isPositive();
        int select = html.indexOf(selectClass, row);
        return html.substring(select, html.indexOf("</select>", select));
    }

    private static List<String> selectedOptions(String html, User user, String selectClass) {
        List<String> texts = new ArrayList<>();
        java.util.regex.Matcher option = java.util.regex.Pattern
                .compile("<option[^>]*\\sselected[^>]*>([^<]*)</option>")
                .matcher(roleSelect(html, user, selectClass));
        while (option.find()) {
            texts.add(org.springframework.web.util.HtmlUtils.htmlUnescape(option.group(1)).trim());
        }
        return texts;
    }

    @Test
    void markNotificationRead_ownOne_returnsTheNewUnreadCount() throws Exception {
        User owner = saveUser("notif-owner", "notif-owner-" + suffix() + "@example.test", "FACILITATOR");
        Control control = createControl("CTRL-NTF-" + suffix(), owner, "IN_PROGRESS");
        Notification first = saveNotification(owner, control, "STATUS_CHANGE");
        saveNotification(owner, control, "STATUS_CHANGE");
        // Hidden types are not counted on the page, so not in the answer either
        saveNotification(owner, control, "DRAFT_INITIATE_REMINDER");
        MockHttpSession session = login(owner.getMail());

        mockMvc.perform(post("/notifications/{id}/read", first.getId()).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));

        Notification stored = notificationRepository.findById(first.getId()).orElseThrow();
        assertThat(stored.getIsRead()).isTrue();
        assertThat(stored.getReadAt()).isNotNull();

        // Again: nothing changes, same count
        mockMvc.perform(post("/notifications/{id}/read", first.getId()).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void markNotificationRead_someoneElsesOrHiddenOrUnknown_returns404_andChangesNothing() throws Exception {
        User owner = saveUser("notif-owner", "notif-owner-" + suffix() + "@example.test", "FACILITATOR");
        User stranger = saveUser("notif-stranger", "notif-stranger-" + suffix() + "@example.test", "FACILITATOR");
        Control control = createControl("CTRL-NTF-" + suffix(), owner, "IN_PROGRESS");
        Notification ownersNotification = saveNotification(owner, control, "STATUS_CHANGE");
        Notification hidden = saveNotification(stranger, control, "DRAFT_INITIATE_REMINDER");
        MockHttpSession session = login(stranger.getMail());

        for (long id : List.of(ownersNotification.getId(), hidden.getId(), 987_654_321L)) {
            mockMvc.perform(post("/notifications/{id}/read", id).with(csrf()).session(session))
                    .andExpect(status().isNotFound());
        }

        assertThat(notificationRepository.findById(ownersNotification.getId()).orElseThrow().getIsRead()).isFalse();
        assertThat(notificationRepository.findById(hidden.getId()).orElseThrow().getIsRead()).isFalse();
    }

    @Test
    void markNotificationRead_withoutCsrfToken_returns403_withoutLogin_redirectsToLogin() throws Exception {
        User owner = saveUser("notif-owner", "notif-owner-" + suffix() + "@example.test", "FACILITATOR");
        Control control = createControl("CTRL-NTF-" + suffix(), owner, "IN_PROGRESS");
        Notification notification = saveNotification(owner, control, "STATUS_CHANGE");
        MockHttpSession session = login(owner.getMail());

        mockMvc.perform(post("/notifications/{id}/read", notification.getId()).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/notifications/{id}/read", notification.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getIsRead()).isFalse();
    }

    @Test
    void notificationsTab_hasCsrfToken_andMarkAsReadButtonOnlyOnUnreadRows() throws Exception {
        User owner = saveUser("notif-owner", "notif-owner-" + suffix() + "@example.test", "FACILITATOR");
        Control control = createControl("CTRL-NTF-" + suffix(), owner, "IN_PROGRESS");
        Notification unread = saveNotification(owner, control, "STATUS_CHANGE");
        Notification read = saveNotification(owner, control, "STATUS_CHANGE");
        read.setIsRead(true);
        notificationRepository.save(read);
        MockHttpSession session = login(owner.getMail());

        String html = mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<meta name=\"_csrf\"").contains("<meta name=\"_csrf_header\"")
                .contains("data-unread-count=\"1\"");
        assertThat(notificationRow(html, unread.getId())).contains("notif-item unread").contains("notif-mark-read");
        assertThat(notificationRow(html, read.getId())).doesNotContain("unread").doesNotContain("notif-mark-read");
    }

    /** The markup of one notification row on the dashboard, up to the next row. */
    private String notificationRow(String html, Long notificationId) {
        int idAt = html.indexOf("data-notif-id=\"" + notificationId + "\"");
        assertThat(idAt).isPositive();
        int nextIdAt = html.indexOf("data-notif-id=", idAt + 1);
        int end = nextIdAt < 0 ? html.indexOf("notifNoUnread", idAt) : html.lastIndexOf("<div", nextIdAt);
        return html.substring(html.lastIndexOf("<div", idAt), end);
    }

    private Notification saveNotification(User user, Control control, String type) {
        Notification notification = new Notification();
        notification.setUserId(user.getId());
        notification.setControlId(control.getId());
        notification.setType(type);
        notification.setTitle("Status changed");
        notification.setMessage("Control " + control.getControlId() + " moved on");
        Notification saved = notificationRepository.save(notification);
        createdNotificationIds.add(saved.getId());
        return saved;
    }

    private static final long UNKNOWN_CONTROL_ID = 987_654_321L;

    private static int requestAddressCount;

    /** Each request from its own address: RateLimitingFilter counts requests per address and path group. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor ownAddress() {
        String address = "10.1." + (requestAddressCount / 250) + "." + (1 + requestAddressCount++ % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    /** A new user the control is shared with, view only. */
    private User shareWith(Control control, String mail) {
        User user = saveUser(mail, mail, "CONTROL_OPERATOR");
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlSharedWith(mail);
        assignmentRepository.save(assignment);
        return user;
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

    private String controlFormWithSoqmYear(String soqmYear) {
        String form = controlForm("Monthly", "HR", "IN_PROGRESS");
        return form.substring(0, form.length() - 1) + ",\"soqmYear\":\"" + soqmYear + "\"}";
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
