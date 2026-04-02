package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class ApiSecurityMockMvcIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ControlRepository controlRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        controlRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void apiWithoutLogin_returns401() throws Exception {
        mockMvc.perform(post("/api/controls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":\"CTRL-NOLOGIN\",\"controlFrequency\":\"Monthly\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithDbUser_thenOwnControls_returns200() throws Exception {
        String qaEmail = "qa-user-" + suffix() + "@example.test";
        String qaPassword = "Test#123";
        User qaUser = saveUser("qa-user", qaEmail, "FACILITATOR", qaPassword);
        createControl("CTRL-DB-" + UUID.randomUUID().toString().substring(0, 6), qaUser, "DRAFT");

        MockHttpSession session = loginAndAttachCurrentUser(qaEmail, qaPassword, qaUser);

        mockMvc.perform(get("/api/controls/user/{email}", qaEmail)
                        .session(session))
                .andExpect(status().isOk());
    }

    @Test
    void readForeignControl_returns403() throws Exception {
        User owner = saveUser("owner-" + suffix(), "owner-" + suffix() + "@example.test", "PROCESS_OWNER", "Test#123");
        Control foreignControl = createControl("CTRL-FGN-" + suffix(), owner, "IN_PROGRESS");

        String facEmail = "fac-" + suffix() + "@example.test";
        User facilitator = saveUser(facEmail, facEmail, "FACILITATOR", "Test#123");
        MockHttpSession session = loginAndAttachCurrentUser(facEmail, "Test#123", facilitator);

        mockMvc.perform(get("/api/controls/{id}/changelog", foreignControl.getId())
                        .session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void forbiddenWorkflowTransition_returns403() throws Exception {
        String ownerEmail = "owner2-" + suffix() + "@example.test";
        User processOwner = saveUser(ownerEmail, ownerEmail, "PROCESS_OWNER", "Test#123");
        Control control = createControl("CTRL-WF-" + suffix(), processOwner, "IN_PROGRESS");

        MockHttpSession session = loginAndAttachCurrentUser(ownerEmail, "Test#123", processOwner);

        mockMvc.perform(post("/api/workflow/submit-to-control-operator")
                        .param("controlId", String.valueOf(control.getId()))
                        .session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void soqmLead_read_modify_workflow_are200() throws Exception {
        User soqmLead = saveUser("soqm-" + suffix(), "soqm-" + suffix() + "@example.test", "SOQM_LEAD", "Test#123");
        Control control = createControl("CTRL-SOQM-" + suffix(), soqmLead, "SOQM_HEAD_REVIEW");

        MockHttpSession session = loginAndAttachCurrentUser(soqmLead.getMail(), "Test#123", soqmLead);

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

    private User saveUser(String username, String mail, String role, String rawPassword) {
        User user = new User();
        user.setUsername(username);
        user.setMail(mail);
        user.setRole(role);
        user.setDisplayName(username);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(rawPassword));
        return userRepository.save(user);
    }

    private Control createControl(String controlId, User createdBy, String performanceStatus) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("HR");
        control.setControlStatus(performanceStatus);
        control.setPerformanceStatus(performanceStatus);
        control.setCreatedBy(createdBy);
        return controlRepository.save(control);
    }

    private MockHttpSession loginAndAttachCurrentUser(String username, String password, User user) throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        if (session == null) {
            throw new IllegalStateException("Login did not create session");
        }
        session.setAttribute("currentUser", user);
        session.setAttribute("userRole", user.getRole());
        return session;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
