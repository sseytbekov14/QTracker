package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.userimport.UserImport;
import com.kpmg.qtracker.security.LoginNameResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release test pass, stage H: the Admin Panel and the people - creating a user, the user dialog (role, Visibility,
 * Access, KDN, status), the guards on oneself, the audit entry with the values before and after, the Audit Trail of
 * the page, signing in with the new login, and the user import (dry run, apply, again; refused outside dev / test).
 */
class ReleaseScenarioAdminIT extends ReleaseScenarioSupport {

    @Autowired
    private AdminAuditService adminAuditService;
    @Autowired
    private LoginNameResolver loginNameResolver;
    @Autowired
    private UserImport userImport;

    @Test
    void creatingAUser_defaultsAndRoles_refusals_audit_andTheNewLoginSignsIn() throws Exception {
        User admin = soqm("ad-soqm");
        MockHttpSession session = login(admin);
        String mail = ("ad-new-" + RUN + "@qtracker.local").toLowerCase(Locale.ROOT);

        // The role is required (none: 400); a User without Visibility or Access gets the defaults
        assertThat(status(session, post("/api/users").param("email", "ad-norole-" + RUN + "@qtracker.local")))
                .isEqualTo(400);
        MvcResult created = call(session, post("/api/users").param("email", mail).param("displayName", "Ad New")
                .param("role", "USER"));
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
        User stored = userRepository.findByMail(mail).orElseThrow();
        // A User without Visibility or Access starts at My controls and Read Only
        assertThat(stored.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(stored.getAccessScope()).isEqualTo(AccessScope.OWN);
        assertThat(stored.getEnabled()).isTrue();
        assertThat(lastAudit(mail).getActionType()).isEqualTo("USER_CREATE");

        // The temporary password of the dev profile (until SSO), signing in with the login
        String login = mail.substring(0, mail.indexOf('@'));
        MvcResult signedIn = mockMvc.perform(post("/login").with(ownAddress())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("username", login).param("password", "aaa")).andReturn();
        assertThat(signedIn.getResponse().getRedirectedUrl()).isEqualTo("/");

        // The same address again (any case), a wrong address, a role that does not exist, KDN with Edit
        assertThat(status(session, post("/api/users").param("email", mail.toUpperCase(Locale.ROOT)).param("role", "USER")))
                .isEqualTo(400);
        assertThat(status(session, post("/api/users").param("email", "not-an-address").param("role", "USER")))
                .isEqualTo(400);
        assertThat(status(session, post("/api/users").param("email", "ad-x-" + RUN + "@qtracker.local")
                .param("role", "ADMIN"))).isEqualTo(400);
        assertThat(status(session, post("/api/users").param("email", "ad-k-" + RUN + "@qtracker.local")
                .param("role", "KDN").param("access", "EDIT"))).isEqualTo(400);
        // KDN, SoQM Team and User with All / Edit
        assertThat(status(session, post("/api/users").param("email", "ad-kdn-" + RUN + "@qtracker.local")
                .param("role", "KDN"))).isEqualTo(200);
        User kdnUser = userRepository.findByMail("ad-kdn-" + RUN.toLowerCase(Locale.ROOT) + "@qtracker.local").orElseThrow();
        assertThat(kdnUser.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(kdnUser.getAccessScope()).isEqualTo(AccessScope.KDN);
        assertThat(status(session, post("/api/users").param("email", "ad-all-" + RUN + "@qtracker.local")
                .param("role", "USER").param("visibility", "ALL").param("access", "EDIT"))).isEqualTo(200);
    }

    @Test
    void theUserDialog_changesRoleVisibilityAccessAndStatus_withTheValuesBeforeAndAfter_effectiveAtOnce() throws Exception {
        User admin = soqm("ud-soqm");
        User person = myEdit("ud-person");
        Control control = control("RS-UD", "IN_PROGRESS", people().facilitator(person).soqm(admin));
        MockHttpSession adminSession = login(admin);
        MockHttpSession personSession = login(person);
        assertThat(saveSteps(personSession, control)).isEqualTo(200);

        // User · My controls · Edit -> Read Only: the next request of the person already reads only
        MvcResult changed = call(adminSession, post("/api/users/{id}/access", person.getId())
                .param("role", "USER").param("visibility", "MY").param("access", "READ_ONLY").param("enabled", "true"));
        assertThat(changed.getResponse().getStatus()).as(body(changed)).isEqualTo(200);
        JsonNode payload = json(changed);
        assertThat(payload.path("changed").asBoolean()).isTrue();
        AdminAuditLog audit = lastAudit(person.getMail());
        assertThat(audit.getActionType()).isEqualTo("USER_ACCESS_UPDATE");
        assertThat(audit.getAdminEmail()).isEqualTo(admin.getMail());
        assertThat(audit.getPreviousValues()).containsIgnoringCase("Edit");
        assertThat(audit.getNewValues()).containsIgnoringCase("Read Only");
        assertThat(saveSteps(personSession, control)).isEqualTo(403);

        // To SoQM Team and back to User · All controls · Edit
        assertThat(status(adminSession, post("/api/users/{id}/access", person.getId()).param("role", "SOQM_TEAM")
                .param("enabled", "true"))).isEqualTo(200);
        assertThat(status(personSession, get("/admin/users"))).isEqualTo(200);
        assertThat(status(adminSession, post("/api/users/{id}/access", person.getId()).param("role", "USER")
                .param("visibility", "ALL").param("access", "EDIT").param("enabled", "true"))).isEqualTo(200);
        assertThat(call(personSession, get("/admin/users")).getResponse().getRedirectedUrl()).isEqualTo("/");
        // KDN is always Read Only
        assertThat(status(adminSession, post("/api/users/{id}/access", person.getId()).param("role", "KDN")
                .param("access", "EDIT").param("enabled", "true"))).isEqualTo(400);
        // Disabled: signed out on the next request
        assertThat(status(adminSession, post("/api/users/{id}/access", person.getId()).param("role", "USER")
                .param("visibility", "ALL").param("access", "EDIT").param("enabled", "false"))).isEqualTo(200);
        assertThat(outcome(call(personSession, get("/")))).isEqualTo("login");
        // Saving without a change writes no audit entry
        long entries = auditCount(person.getMail());
        MvcResult same = call(adminSession, post("/api/users/{id}/access", person.getId()).param("role", "USER")
                .param("visibility", "ALL").param("access", "EDIT").param("enabled", "false"));
        assertThat(json(same).path("changed").asBoolean()).isFalse();
        assertThat(auditCount(person.getMail())).isEqualTo(entries);
    }

    @Test
    void soqmTeam_cannotChangeTheirOwnRole_norDisableThemselves_andThePageShowsTheAuditTrail() throws Exception {
        User admin = soqm("self-soqm");
        User other = soqm("self-other");
        MockHttpSession session = login(admin);
        MvcResult ownRole = call(session, post("/api/users/{id}/access", admin.getId()).param("role", "USER")
                .param("visibility", "ALL").param("access", "EDIT").param("enabled", "true"));
        assertThat(ownRole.getResponse().getStatus()).isEqualTo(400);
        MvcResult ownStatus = call(session, post("/api/users/{id}/access", admin.getId()).param("role", "SOQM_TEAM")
                .param("enabled", "false"));
        assertThat(ownStatus.getResponse().getStatus()).isEqualTo(400);
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getAccessLevel()).isEqualTo(AccessLevel.SOQM);
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getEnabled()).isTrue();
        // Another SoQM Team member can be changed
        assertThat(status(session, post("/api/users/{id}/access", other.getId()).param("role", "USER")
                .param("visibility", "MY").param("access", "READ_ONLY").param("enabled", "true"))).isEqualTo(200);

        Map<String, Object> page = call(session, get("/admin/users")).getModelAndView().getModel();
        assertThat(page.get("rows").toString()).contains(other.getMail());
        assertThat(page.get("auditTrail").toString()).contains(other.getMail());
    }

    // ---- the user import ----

    @Test
    void userImport_dryRunChangesNothing_applyCreates_againChangesNothing_otherProfilesAreRefused() throws Exception {
        String tag = letters(RUN);
        Path dir = Path.of("target", "release-scenario", "import-" + RUN);
        Files.createDirectories(dir);
        Path csv = dir.resolve("people.csv");
        String content = "Name,User,Role,Access,Status\r\n"
                + "Aida,\"Tagone" + tag + ", Aida\",SoQM Team,SoQM Team,Active\r\n"
                + "Bek,\"Tagtwo" + tag + ", Bek\",SoQM Team,SoQM Team,Active\r\n"
                + "Dana,\"Tagthree" + tag + ", Dana\",User,\"My Controls, Edit\",Active\r\n"
                + "Erik,\"Tagfour" + tag + ", Erik\",KDN,\"All KDN Controls, Read only\",Active\r\n"
                + "Fara,\"Tagfive" + tag + ", Fara\",User,\"All Controls, Read Only\",Inactive\r\n";
        Files.write(csv, ("﻿" + content).getBytes(StandardCharsets.UTF_8));
        long before = userRepository.count();

        UserImport.Report dry = userImport.run(csv, false, "release-test", LocalDate.of(2026, 10, 9));
        assertThat(dry.refused()).isFalse();
        assertThat(dry.lines()).extracting(UserImport.Line::outcome).containsOnly(UserImport.Outcome.CREATE);
        assertThat(userRepository.count()).isEqualTo(before);

        UserImport.Report applied = userImport.run(csv, true, "release-test", LocalDate.of(2026, 10, 9));
        assertThat(applied.applied()).isTrue();
        assertThat(userRepository.count()).isEqualTo(before + 5);
        User kdnPerson = userRepository.findByMail("etagfour" + tag + "@qtracker.local").orElseThrow();
        assertThat(kdnPerson.getAccessScope()).isEqualTo(AccessScope.KDN);
        assertThat(userRepository.findByMail("ftagfive" + tag + "@qtracker.local").orElseThrow().getEnabled()).isFalse();

        UserImport.Report again = userImport.run(csv, true, "release-test", LocalDate.of(2026, 10, 9));
        assertThat(again.lines()).extracting(UserImport.Line::outcome).containsOnly(UserImport.Outcome.UNCHANGED);
        assertThat(userRepository.count()).isEqualTo(before + 5);

        // Outside dev and test the import (and its temporary password) is refused
        for (String profile : List.of("stage", "prod")) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            UserImport elsewhere = new UserImport(userRepository, passwordEncoder, adminAuditService, loginNameResolver,
                    environment);
            UserImport.Report refused = elsewhere.run(csv, true, "release-test", LocalDate.of(2026, 10, 9));
            assertThat(refused.refused()).as(profile).isTrue();
            assertThat(refused.refusal()).contains("dev").contains("test");
        }
        assertThat(userRepository.count()).isEqualTo(before + 5);
    }

    // ---- helpers ----

    private int saveSteps(MockHttpSession session, Control control) throws Exception {
        return callJson(session, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"Steps " + next() + "\"}")
                .getResponse().getStatus();
    }

    private AdminAuditLog lastAudit(String mail) {
        return auditRepository.findAll().stream()
                .filter(a -> a.getActionDescription() != null && a.getActionDescription().contains(mail))
                .max(java.util.Comparator.comparing(AdminAuditLog::getId))
                .orElseThrow();
    }

    private long auditCount(String mail) {
        return auditRepository.findAll().stream()
                .filter(a -> a.getActionDescription() != null && a.getActionDescription().contains(mail)).count();
    }

    /** The run tag as letters only (logins are made of letters). */
    private static String letters(String run) {
        StringBuilder out = new StringBuilder();
        for (char c : run.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(Character.isDigit(c) ? (char) ('a' + (c - '0')) : c);
        }
        return out.toString();
    }
}
