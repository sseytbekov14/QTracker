package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.config.DevUserSeeder;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        "reminders.enabled=false"
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

    @MockitoBean
    private DevUserSeeder devUserSeeder;

    @MockitoSpyBean
    private WorkflowHistoryRepository workflowHistoryRepository;

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
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
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
