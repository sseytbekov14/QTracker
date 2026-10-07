package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.ControlRenameService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A KDN control is one whose Control ID starts with "KDN", and KDN users see every KDN control. Renaming the
 * ID so that the prefix appears or goes changes who sees the control: such a rename needs a comment, and its
 * audit entry names the KDN users who gain or lose access (all of them, on the control or not). Other renames
 * need no comment and are audited as well. Other tests may leave KDN users in the database, so the lists are
 * checked to contain this test's users.
 */
@SpringBootTest(properties = "file.upload.dir=target/it-uploads-kdn-rename")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class KdnRenameIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ControlRepository controlRepository;
    @Autowired
    private ControlAssignmentRepository assignmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminAuditLogRepository auditRepository;
    @Autowired
    private IControlService controlService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<User> users = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private String s;
    private User soqm;
    private User participant;
    private User kdnFacilitator;
    private User kdnShared;
    private User kdnCreator;
    private User kdnOther;

    @BeforeEach
    void setUp() {
        s = UUID.randomUUID().toString().substring(0, 8);
        soqm = saveUser("kr-soqm-" + s, AccessLevel.SOQM, AccessScope.ALL);
        participant = saveUser("kr-part-" + s, AccessLevel.PARTICIPANT, AccessScope.OWN);
        kdnFacilitator = saveUser("kr-kdn-fac-" + s, AccessLevel.READ_ONLY, AccessScope.KDN);
        kdnShared = saveUser("kr-kdn-shared-" + s, AccessLevel.READ_ONLY, AccessScope.KDN);
        kdnCreator = saveUser("kr-kdn-creator-" + s, AccessLevel.READ_ONLY, AccessScope.KDN);
        kdnOther = saveUser("kr-kdn-other-" + s, AccessLevel.READ_ONLY, AccessScope.KDN);
    }

    @AfterEach
    void tearDown() {
        for (Control control : controls) {
            auditRepository.deleteAll(auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
            assignmentRepository.findByControlId(control.getId()).ifPresent(assignmentRepository::delete);
            controlRepository.deleteById(control.getId());
        }
        users.forEach(u -> userRepository.findById(u.getId()).ifPresent(userRepository::delete));
    }

    @Test
    void kdnUsersSeeEveryControlWhoseIdStartsWithKdn_onItOrNot_andNoOther() throws Exception {
        List<Control> kdn = new ArrayList<>();
        for (String form : List.of("KDN-001-", "KDN001-", "kdn-5-", "  Kdn-7-")) {
            kdn.add(control(form + s));
        }
        List<Control> others = List.of(control("X-KDN-12-" + s), control("HR-001-" + s), control("KD-N-1-" + s));

        for (Control control : kdn) {
            for (User reader : List.of(kdnFacilitator, kdnShared, kdnCreator, kdnOther)) {
                readAs(reader, control).andExpect(status().isOk());
            }
        }
        for (Control control : others) {
            for (User reader : List.of(kdnFacilitator, kdnShared, kdnCreator, kdnOther)) {
                readAs(reader, control).andExpect(status().isForbidden());
            }
            readAs(participant, control).andExpect(status().isOk());
        }
        assertThat(controlService.findVisibleControlsForUser(kdnOther)).extracting(Control::getId)
                .containsAll(kdn.stream().map(Control::getId).toList())
                .doesNotContainAnyElementsOf(others.stream().map(Control::getId).toList());
    }

    @Test
    void renameThatRemovesKdn_needsAComment_thenTheKdnUsersLoseTheControl() throws Exception {
        Control control = control("KDN-77-" + s);
        String newId = "HR-77-" + s;

        rename(control, newId, null)
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("A comment is required")))
                .andExpect(content().string(containsString("no longer a KDN control")))
                .andExpect(content().string(containsString(kdnFacilitator.getMail())))
                .andExpect(content().string(containsString(kdnCreator.getMail())))
                .andExpect(content().string(containsString(kdnOther.getMail())));
        rename(control, newId, "   ").andExpect(status().isBadRequest());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getControlId()).isEqualTo("KDN-77-" + s);
        assertThat(audits(control)).isEmpty();
        readAs(kdnFacilitator, control).andExpect(status().isOk());

        rename(control, newId, "Moved to the local HR controls")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlId").value(newId));

        readAs(kdnFacilitator, control).andExpect(status().isForbidden());
        readAs(kdnShared, control).andExpect(status().isForbidden());
        readAs(kdnCreator, control).andExpect(status().isForbidden());
        readAs(kdnOther, control).andExpect(status().isForbidden());
        readAs(participant, control).andExpect(status().isOk());

        AdminAuditLog audit = single(audits(control));
        assertThat(audit.getActionType()).isEqualTo(ControlRenameService.AUDIT_ACTION);
        assertThat(audit.getAdminEmail()).isEqualTo(soqm.getMail());
        assertThat(audit.getControlControlId()).isEqualTo(newId);
        assertThat(audit.getActionDescription()).contains("KDN-77-" + s + " -> " + newId, "no longer a KDN control");
        assertThat(objectMapper.readValue(audit.getChangedFields(), List.class))
                .containsExactly("Control ID", "KDN control", "Comment");
        Map<?, ?> previous = objectMapper.readValue(audit.getPreviousValues(), Map.class);
        Map<?, ?> next = objectMapper.readValue(audit.getNewValues(), Map.class);
        assertThat(previous.get("Control ID")).isEqualTo("KDN-77-" + s);
        assertThat(previous.get("KDN control")).isEqualTo("Yes");
        assertThat(next.get("KDN control")).isEqualTo("No");
        assertThat(next.get("Comment")).isEqualTo("Moved to the local HR controls");
        assertThat((List<Object>) next.get("KDN users losing access"))
                .contains(kdnFacilitator.getMail(), kdnShared.getMail(), kdnCreator.getMail(), kdnOther.getMail())
                .doesNotContain(participant.getMail(), soqm.getMail());
        assertThat((List<Object>) next.get("KDN users gaining access")).isEmpty();

        mockMvc.perform(as(soqm, get("/api/controls/{id}/changelog", control.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rename Control ID")));
    }

    @Test
    void renameThatAddsKdn_needsAComment_thenEveryKdnUserSeesIt() throws Exception {
        Control control = control("HR-88-" + s);
        String newId = "kdn-hr-88-" + s;
        readAs(kdnFacilitator, control).andExpect(status().isForbidden());
        readAs(kdnOther, control).andExpect(status().isForbidden());

        rename(control, newId, "").andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("makes this a KDN control")));

        rename(control, newId, "Belongs to the KDN set").andExpect(status().isOk());

        readAs(kdnFacilitator, control).andExpect(status().isOk());
        readAs(kdnCreator, control).andExpect(status().isOk());
        readAs(kdnOther, control).andExpect(status().isOk());
        Map<?, ?> next = objectMapper.readValue(single(audits(control)).getNewValues(), Map.class);
        assertThat((List<Object>) next.get("KDN users gaining access"))
                .contains(kdnFacilitator.getMail(), kdnShared.getMail(), kdnCreator.getMail(), kdnOther.getMail());
        assertThat((List<Object>) next.get("KDN users losing access")).isEmpty();
    }

    @Test
    void renameThatKeepsTheKdnMark_needsNoComment_andIsAudited() throws Exception {
        Control kdn = control("KDN-99-" + s);
        rename(kdn, "  kdn99-" + s + "  ", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlId").value("kdn99-" + s));
        readAs(kdnOther, kdn).andExpect(status().isOk());
        AdminAuditLog audit = single(audits(kdn));
        assertThat(objectMapper.readValue(audit.getChangedFields(), List.class)).containsExactly("Control ID");

        // "KDN" further on in the ID does not make a KDN control: no change, no comment needed
        Control hr = control("HR-99-" + s);
        rename(hr, "HR-KDN-99-" + s, null).andExpect(status().isOk());
        readAs(kdnFacilitator, hr).andExpect(status().isForbidden());
        assertThat(objectMapper.readValue(single(audits(hr)).getChangedFields(), List.class))
                .containsExactly("Control ID");

        Control other = control("HR-98-" + s);
        rename(other, "HR-100-" + s, "Typo").andExpect(status().isOk());
        assertThat(objectMapper.readValue(single(audits(other)).getChangedFields(), List.class))
                .containsExactly("Control ID", "Comment");
    }

    @Test
    void ownCreatedControlsApi_givesAKdnUserOnlyTheKdnOnes() throws Exception {
        // Old data: a KDN user as the creator of a non-KDN control
        Control kdn = control("KDN-66-" + s);
        Control hr = control("HR-66-" + s);
        mockMvc.perform(as(kdnCreator, get("/api/controls/user/{email}", kdnCreator.getMail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(kdn.getId().intValue())))
                .andExpect(jsonPath("$[*].id", not(hasItem(hr.getId().intValue()))));
    }

    @Test
    void onlySoqmRenames() throws Exception {
        Control control = control("KDN-55-" + s);
        mockMvc.perform(as(participant, post("/api/controls/{id}/rename-id", control.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newControlId\":\"HR-55-" + s + "\",\"comment\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(controlRepository.findById(control.getId()).orElseThrow().getControlId()).isEqualTo("KDN-55-" + s);
    }

    // ------------------------------------------------------------------ helpers

    /** The control with the participant as Control Operator, one KDN user as Facilitator, one shared, one creator. */
    private Control control(String controlId) {
        Control control = new Control();
        control.setControlId(controlId);
        control.setControlFrequency("Monthly");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus("IN_PROGRESS");
        control.setCreatedBy(kdnCreator);
        control.setCreatedAt(LocalDateTime.now());
        control = controlRepository.save(control);
        controls.add(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseGet(ControlAssignment::new);
        assignment.setControlId(control.getId());
        assignment.setFacilitator(kdnFacilitator.getMail());
        assignment.setControlOperator(participant.getMail());
        assignment.setSoqmLead(soqm.getMail());
        assignment.setControlSharedWith(kdnShared.getMail());
        assignmentRepository.save(assignment);
        return control;
    }

    private org.springframework.test.web.servlet.ResultActions rename(Control control, String newId, String comment)
            throws Exception {
        String body = objectMapper.writeValueAsString(comment == null
                ? Map.of("newControlId", newId)
                : Map.of("newControlId", newId, "comment", comment));
        return mockMvc.perform(as(soqm, post("/api/controls/{id}/rename-id", control.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions readAs(User reader, Control control) throws Exception {
        return mockMvc.perform(as(reader, get("/api/control-details").param("controlId", String.valueOf(control.getId()))));
    }

    private List<AdminAuditLog> audits(Control control) {
        return auditRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .filter(log -> ControlRenameService.AUDIT_ACTION.equals(log.getActionType()))
                .toList();
    }

    private static AdminAuditLog single(List<AdminAuditLog> logs) {
        assertThat(logs).hasSize(1);
        return logs.get(0);
    }

    private MockHttpServletRequestBuilder as(User actor, MockHttpServletRequestBuilder request) {
        return request.sessionAttr("currentUser", actor)
                .with(user(actor.getMail()).roles(String.valueOf(actor.getAccessLevel())))
                .with(csrf());
    }

    private User saveUser(String name, AccessLevel level, AccessScope scope) {
        User user = userRepository.save(TestUsers.user(name + "@example.test", level, scope, false));
        users.add(user);
        return user;
    }
}
