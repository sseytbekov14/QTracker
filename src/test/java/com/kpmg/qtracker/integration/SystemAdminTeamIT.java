package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.SystemAdmins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * System Admin Team (business decision of 2026-11-09): e-mails in SYSTEM_ADMIN_EMAILS on the server. They see what SoQM
 * Admin Team sees - every control, the Admin Panel and its Audit Trail, the Excel exports - and change nothing; nobody
 * changes, deactivates or deletes their account in the application while they are listed; they are never put on a
 * control. Their account here says SoQM Admin Team, to show the server list wins.
 */
class SystemAdminTeamIT extends ReleaseScenarioSupport {

    @AfterEach
    void clearList() {
        SystemAdmins.replace(List.of());
    }

    private User systemAdmin(String tag) {
        User user = person(tag, AccessLevel.SOQM, AccessScope.ALL);
        SystemAdmins.replace(List.of(user.getMail()));
        return user;
    }

    @Test
    void aSystemAdmin_seesEverythingSoqmSees_andChangesNothing() throws Exception {
        User soqm = soqm("sa1-soqm");
        User fac = myEdit("sa1-fac");
        User admin = systemAdmin("sa1-admin");
        Control control = control("HR-SA1", "IN_PROGRESS", people().facilitator(fac).soqm(soqm));
        Control completed = control("HR-SA1", "COMPLETED", people().facilitator(fac).soqm(soqm));
        MockHttpSession session = login(admin);

        // The Admin Panel and its Audit Trail, with nothing to change
        MvcResult panel = call(session, get("/admin/users"));
        assertThat(panel.getResponse().getStatus()).isEqualTo(200);
        assertThat(panel.getModelAndView().getViewName()).isEqualTo("admin-users");
        assertThat(body(panel)).contains("Audit Trail", "data-email=\"" + fac.getMail() + "\"", "System Admin Team")
                // No Edit on any row, no Add user
                .contains("id=\"exportUsersBtn\"")
                .doesNotContain("aria-label=\"Edit ").doesNotContain("id=\"openAddUserBtn\"");
        // Every control, read only; the Excel exports
        MvcResult page = call(session, get("/view-control/{id}", control.getId()));
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getModelAndView().getModel().get("readOnly")).isEqualTo(true);
        assertThat(page.getModelAndView().getModel().get("accessNotice")).isEqualTo("READ_ONLY");
        assertThat(body(page)).doesNotContain("id=\"editBtn\"").doesNotContain("deactivateControlBtn");
        assertThat(status(session, get("/component/ALL/export"))).isEqualTo(200);
        assertThat(status(session, get("/api/controls/{id}/export/completed", completed.getId()))).isEqualTo(200);

        // Nothing changes: a control, a new control, a step, a user, a deactivation
        assertThat(status(session, put("/api/controls/{id}", control.getId())
                .contentType("application/json").content("{\"controlDescription\":\"Changed\"}"))).isEqualTo(403);
        assertThat(status(session, post("/api/controls").contentType("application/json")
                .content("{\"controlId\":\"HR-SA-NEW\",\"controlFrequency\":\"Monthly\",\"controlType\":\"Preventive\","
                        + "\"component\":\"HR\",\"operatedBy\":\"Finance\",\"priority\":\"High\","
                        + "\"nonAuditServicesApplicability\":\"No\"}"))).isEqualTo(403);
        assertThat(status(session, post("/api/workflow/submit-to-control-operator")
                .param("controlId", String.valueOf(control.getId())))).isEqualTo(403);
        assertThat(status(session, post("/api/users/{id}/access", fac.getId()).param("role", "USER")
                .param("visibility", "ALL").param("access", "EDIT").param("enabled", "true"))).isEqualTo(403);
        assertThat(status(session, post("/api/users/{id}/delete-check", fac.getId()))).isEqualTo(403);
        assertThat(status(session, post("/api/controls/{id}/deactivate", control.getId())
                .contentType("application/json").content("{\"confirmControlId\":\"" + control.getControlId() + "\"}")))
                .isEqualTo(403);
        assertThat(reload(control).getControlDescription()).isEqualTo("Release test control");
        assertThat(controlRepository.findById(control.getId())).isPresent();
        assertThat(userRepository.findById(fac.getId()).orElseThrow().getAccessScope()).isEqualTo(AccessScope.OWN);
    }

    @Test
    void soqm_cannotChangeDeactivateOrDeleteTheAccount_untilItLeavesTheServerList() throws Exception {
        User soqm = soqm("sa2-soqm");
        User admin = systemAdmin("sa2-admin");
        MockHttpSession session = login(soqm);

        // The row: the role System Admin Team, no Edit
        String panel = body(call(session, get("/admin/users")));
        int at = panel.indexOf("data-email=\"" + admin.getMail() + "\"");
        assertThat(at).isPositive();
        String row = panel.substring(panel.lastIndexOf("<tr", at), panel.indexOf("</tr>", at));
        assertThat(row).contains("data-role=\"SYSTEM_ADMIN\"", ">System Admin Team<", "row-locked",
                "Admin Panel · View only").doesNotContain("aria-label=\"Edit ");

        for (String enabled : List.of("true", "false")) {
            MvcResult refused = call(session, post("/api/users/{id}/access", admin.getId()).param("role", "USER")
                    .param("visibility", "MY").param("access", "READ_ONLY").param("enabled", enabled));
            assertThat(refused.getResponse().getStatus()).as("enabled=" + enabled).isEqualTo(403);
            assertThat(body(refused)).contains(AccessPolicy.SYSTEM_ADMIN_PROTECTED);
        }
        MvcResult deletion = call(session, post("/api/users/{id}/delete-check", admin.getId()));
        assertThat(deletion.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(deletion)).contains(AccessPolicy.SYSTEM_ADMIN_PROTECTED);
        User stored = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(stored.getEnabled()).isTrue();
        assertThat(stored.getAccessLevel()).isEqualTo(AccessLevel.SOQM);

        // Off the list (and the application restarted): an ordinary account again
        SystemAdmins.replace(List.of());
        assertThat(status(session, post("/api/users/{id}/access", admin.getId()).param("role", "USER")
                .param("visibility", "MY").param("access", "READ_ONLY").param("enabled", "true"))).isEqualTo(200);
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void aSystemAdmin_isNeverPutOnAControl_andIsNotOfferedInThePickers() throws Exception {
        User soqm = soqm("sa3-soqm");
        User admin = systemAdmin("sa3-admin");
        User fac = myEdit("sa3-fac");
        User op = myEdit("sa3-op");
        User po = myEdit("sa3-po");
        Control control = control("HR-SA3", "IN_PROGRESS", people().facilitator(fac).operator(op).soqm(soqm).owner(po));
        MockHttpSession session = login(soqm);

        for (String field : List.of("facilitator", "controlOperator", "processOwner", "controlSharedWith")) {
            java.util.Map<String, Object> people = new java.util.LinkedHashMap<>();
            people.put("controlId", control.getId());
            people.put("facilitator", List.of(fac.getMail()));
            people.put("controlOperator", List.of(op.getMail()));
            people.put("soqmLead", List.of(soqm.getMail()));
            people.put("processOwner", List.of(po.getMail()));
            people.put("controlOperationDate", today().plusDays(3).toString());
            people.put(field, List.of(admin.getMail()));
            MvcResult refused = callJson(session, post("/api/control-assignment"), objectMapper.writeValueAsString(people));
            assertThat(refused.getResponse().getStatus()).as(field).isEqualTo(400);
            assertThat(body(refused)).as(field).contains(AccessPolicy.SYSTEM_ADMIN_NOT_ASSIGNED);
        }
        for (String role : List.of("FACILITATOR", "CONTROL_OPERATOR", "SOQM_TEAM", "PROCESS_OWNER")) {
            assertThat(body(call(session, get("/api/users/role/" + role)))).as(role).doesNotContain(admin.getMail());
        }
    }

    @Test
    void aSystemAdminAccountSayingSoqm_doesNotCountAsTheLastActiveSoqmAdminTeamMember() {
        long before = userRepository.countActiveSoqmAdminTeam();
        User admin = person("sa4-admin", AccessLevel.SOQM, AccessScope.ALL);
        assertThat(userRepository.countActiveSoqmAdminTeam()).isEqualTo(before + 1);

        SystemAdmins.replace(List.of(admin.getMail()));

        assertThat(userRepository.countActiveSoqmAdminTeam()).isEqualTo(before);
    }
}
