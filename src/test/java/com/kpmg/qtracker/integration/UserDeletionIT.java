package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.DeletedUserRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.ComponentControlsList;
import com.kpmg.qtracker.service.UserDeletion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Item 8 (business decision of 2026-10-13): SoQM Admin Team deletes a user from the Admin Panel in two steps, both
 * checked by the server. The users row goes (with the password, the Entra id and the person's own notifications);
 * history, logs, uploads and the people of the controls stay, with the name and e-mail; the pages that show the
 * person keep working and say "(deleted user)".
 */
class UserDeletionIT extends ReleaseScenarioSupport {

    @Autowired
    private DeletedUserRepository deletedUserRepository;

    @Test
    void soqmAdminTeam_deletesAnUnusedUser_inOneConfirmation_andASecondTryIs404() throws Exception {
        User soqm = soqm("del-soqm");
        User unused = myEdit("del-unused");
        notice(unused, control("HR-DEL-N", "IN_PROGRESS", people().soqm(soqm)));
        MockHttpSession session = login(soqm);

        JsonNode check = json(call(session, post("/api/users/{id}/delete-check", unused.getId())));
        assertThat(check.path("secondConfirmation").asBoolean()).isFalse();
        assertThat(check.path("total").asInt()).isZero();
        assertThat(check.path("token").asText()).isNotBlank();

        MvcResult deleted = delete(session, unused, unused.getMail().toUpperCase(Locale.ROOT), "Left the firm",
                check.path("token").asText(), false);
        assertThat(deleted.getResponse().getStatus()).as(body(deleted)).isEqualTo(200);
        assertThat(json(deleted).path("audit").path("action").asText()).isEqualTo("Deleted user");
        assertThat(userRepository.findById(unused.getId())).isEmpty();
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(unused.getId())).isEmpty();
        assertThat(deletedUserRepository.findByMailLowerIn(List.of(unused.getMail()))).singleElement()
                .satisfies(gone -> {
                    assertThat(gone.getUserId()).isEqualTo(unused.getId());
                    assertThat(gone.getDeletedByEmail()).isEqualTo(soqm.getMail());
                });
        AdminAuditLog entry = auditRepository.findTop100ByActionTypeStartingWithOrderByCreatedAtDesc(UserDeletion.AUDIT_ACTION)
                .stream().filter(log -> log.getActionDescription().endsWith(unused.getMail())).findFirst().orElseThrow();
        assertThat(entry.getAdminEmail()).isEqualTo(soqm.getMail());
        assertThat(entry.getPreviousValues()).contains("\"id\":" + unused.getId(), unused.getMail(),
                unused.getDisplayName(), "\"role\":\"User");
        assertThat(entry.getNewValues()).contains("\"Reason\":\"Left the firm\"", "\"Unfinished controls\":0",
                "\"Second confirmation\":false");

        // Again: the user is gone
        assertThat(call(session, post("/api/users/{id}/delete-check", unused.getId())).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(delete(session, unused, unused.getMail(), "Again", check.path("token").asText(), false)
                .getResponse().getStatus()).isEqualTo(404);
        // And the same address can be given to a new user
        MvcResult again = call(session, post("/api/users").param("email", unused.getMail()).param("displayName", "Back")
                .param("role", "USER").param("visibility", "MY").param("access", "EDIT").param("enabled", "true"));
        assertThat(again.getResponse().getStatus()).as(body(again)).isEqualTo(200);
        assertThat(userRepository.findByMail(unused.getMail())).isPresent();
    }

    @Test
    void everyoneButSoqmAdminTeam_is403_andWithoutCsrfNothingHappens() throws Exception {
        User target = myEdit("del-target");
        for (User other : List.of(myEdit("del-my"), allEdit("del-all"), myRead("del-ro"), allRead("del-allro"),
                kdn("del-kdn"))) {
            MockHttpSession session = login(other);
            assertThat(call(session, post("/api/users/{id}/delete-check", target.getId())).getResponse().getStatus())
                    .as(other.getMail()).isEqualTo(403);
            assertThat(delete(session, target, target.getMail(), "Why", "token", true).getResponse().getStatus())
                    .as(other.getMail()).isEqualTo(403);
        }
        MockHttpSession soqmSession = login(soqm("del-csrf"));
        MvcResult noCsrf = mockMvc.perform(post("/api/users/{id}/delete-check", target.getId()).session(soqmSession)
                .with(ownAddress())).andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(userRepository.findById(target.getId())).isPresent();
    }

    @Test
    void oneself_isRefused_andTheChecksOfStepTwo() throws Exception {
        User soqm = soqm("del-self");
        User target = myEdit("del-steps");
        MockHttpSession session = login(soqm);
        MvcResult self = call(session, post("/api/users/{id}/delete-check", soqm.getId()));
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(self)).isEqualTo(AccessPolicy.DELETE_SELF);

        String token = json(call(session, post("/api/users/{id}/delete-check", target.getId()))).path("token").asText();
        assertThat(body(delete(session, target, "someone@else.test", "Why", token, false))).isEqualTo(UserDeletion.EMAIL_MISMATCH);
        assertThat(body(delete(session, target, target.getMail(), "  ", token, false))).isEqualTo(UserDeletion.REASON_REQUIRED);
        assertThat(body(delete(session, target, target.getMail(), "Why", "made-up", false))).isEqualTo(UserDeletion.TOKEN_INVALID);
        // A token is for one target and one admin
        User other = myEdit("del-other");
        assertThat(delete(session, other, other.getMail(), "Why", token, false).getResponse().getStatus()).isEqualTo(409);
        assertThat(delete(login(soqm("del-other-admin")), target, target.getMail(), "Why", token, false)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(userRepository.findById(target.getId())).isPresent();
    }

    @Test
    void someoneOnControls_needsTheSecondConfirmation_andEverythingTheyDidStays() throws Exception {
        User soqm = soqm("del-lead");
        User person = myEdit("del-person");
        User colleague = myEdit("del-colleague");
        // Facilitator here, Shared With there, creator of a third; a completed one is not listed
        Control asFacilitator = control("HR-DEL", "IN_PROGRESS", people().facilitator(person).operator(colleague)
                .soqm(soqm).owner(colleague));
        Control asShared = control("HR-DEL", "REVIEW", people().facilitator(colleague).shared(person).soqm(soqm));
        Control asCreator = control("HR-DEL", "DRAFT", people().facilitator(colleague).soqm(soqm).creator(person));
        Control completed = control("HR-DEL", "COMPLETED", people().facilitator(person).soqm(soqm));
        Control without = control("HR-DEL", "IN_PROGRESS", people().facilitator(colleague).operator(colleague)
                .soqm(soqm).owner(colleague));
        WorkflowHistory step = history(asFacilitator, person);
        ControlAttachment upload = upload(asFacilitator, person);
        AdminAuditLog theirAudit = audit(person);
        MockHttpSession session = login(soqm);

        JsonNode check = json(call(session, post("/api/users/{id}/delete-check", person.getId())));
        assertThat(check.path("secondConfirmation").asBoolean()).isTrue();
        assertThat(check.path("total").asInt()).isEqualTo(3);
        assertThat(check.path("controls").toString()).contains(asFacilitator.getControlId(), asShared.getControlId(),
                asCreator.getControlId(), "\"Facilitator\"", "\"Shared With\"", "\"Creator\"")
                .doesNotContain(completed.getControlId());

        // Without the acknowledgement: refused, nothing changes
        MvcResult refused = delete(session, person, person.getMail(), "Moved abroad", check.path("token").asText(), false);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused)).isEqualTo(UserDeletion.ACKNOWLEDGEMENT_REQUIRED);
        assertThat(userRepository.findById(person.getId())).isPresent();

        MvcResult done = delete(session, person, person.getMail(), "Moved abroad", check.path("token").asText(), true);
        assertThat(done.getResponse().getStatus()).as(body(done)).isEqualTo(200);
        assertThat(userRepository.findById(person.getId())).isEmpty();

        // Kept: the people of the controls, the history, the uploads, the audit, the creator as text
        ControlAssignment assignment = assignmentRepository.findByControlId(asFacilitator.getId()).orElseThrow();
        assertThat(assignment.getFacilitator()).isEqualTo(person.getMail());
        assertThat(assignmentRepository.findByControlId(asShared.getId()).orElseThrow().getControlSharedWith())
                .isEqualTo(person.getMail());
        assertThat(historyRepository.findById(step.getId()).orElseThrow().getPerformedByName()).isEqualTo(person.getDisplayName());
        assertThat(attachmentRepository.findById(upload.getId()).orElseThrow().getUploadedByEmail()).isEqualTo(person.getMail());
        assertThat(auditRepository.findById(theirAudit.getId()).orElseThrow().getAdminEmail()).isEqualTo(person.getMail());
        Control created = reload(asCreator);
        assertThat(created.getCreatedBy()).isNull();
        assertThat(created.getCreatedByEmail()).isEqualTo(person.getMail());
        assertThat(created.creatorName()).isEqualTo(person.getDisplayName() + " (deleted user)");
        AdminAuditLog entry = auditRepository.findTop100ByActionTypeStartingWithOrderByCreatedAtDesc(UserDeletion.AUDIT_ACTION)
                .stream().filter(log -> log.getActionDescription().endsWith(person.getMail())).findFirst().orElseThrow();
        assertThat(entry.getNewValues()).contains("\"Unfinished controls\":3", asCreator.getControlId(),
                "\"Second confirmation\":true");

        // The pages that show the person keep working and say "(deleted user)"
        for (Control control : List.of(asFacilitator, asShared, asCreator, completed)) {
            assertThat(call(session, get("/view-control/{id}", control.getId())).getResponse().getStatus())
                    .as(control.getControlId()).isEqualTo(200);
        }
        assertThat(body(call(session, get("/view-control/{id}", asShared.getId()))))
                .contains(person.getMail() + " (deleted user)", "Deleted user");
        JsonNode changelog = json(call(session, get("/api/controls/{id}/changelog", asCreator.getId())));
        assertThat(changelog.toString()).contains(person.getDisplayName() + " (deleted user)");
        JsonNode read = json(call(session, get("/api/control-assignment").param("controlId", String.valueOf(asFacilitator.getId()))));
        assertThat(read.path("deletedPeople").toString()).contains(person.getMail().toLowerCase(Locale.ROOT));
        ComponentControlsList.Row row = listRows(session, "/component/ALL?q=" + asFacilitator.getControlId()).get(0);
        assertThat(row.facilitators()).isEqualTo(person.getMail() + " (deleted user)");
        assertThat(call(session, get("/component/ALL/export").param("q", "HR-DEL")).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(session, get("/api/controls/{id}/export/completed", completed.getId())).getResponse().getStatus())
                .isEqualTo(200);

        // A save that keeps them where they were is not refused; putting them somewhere new is
        MvcResult kept = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + asFacilitator.getId()
                + ",\"facilitator\":[\"" + person.getMail() + "\"],\"controlOperationDate\":\"" + today().plusDays(4) + "\"}");
        assertThat(kept.getResponse().getStatus()).as(body(kept)).isEqualTo(200);
        MvcResult added = callJson(session, post("/api/control-assignment"), "{\"controlId\":" + without.getId()
                + ",\"processOwner\":[\"" + person.getMail() + "\"]}");
        assertThat(added.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(added)).contains("is not a QTracker user");
    }

    @Test
    void theDeletedPersonsSession_ends() throws Exception {
        User soqm = soqm("del-session-admin");
        User person = myEdit("del-session");
        MockHttpSession theirs = login(person);
        assertThat(call(theirs, get("/")).getResponse().getStatus()).isEqualTo(200);

        MockHttpSession session = login(soqm);
        String token = json(call(session, post("/api/users/{id}/delete-check", person.getId()))).path("token").asText();
        assertThat(delete(session, person, person.getMail(), "Gone", token, false).getResponse().getStatus()).isEqualTo(200);

        assertThat(outcome(call(theirs, get("/")))).isIn("login", "403");
        // Created again with the same address: the old session does not become the new user's
        User again = myEdit("del-session-again");
        again.setMail(person.getMail());
        userRepository.save(again);
        assertThat(outcome(call(theirs, get("/")))).isIn("login", "403");
    }

    // ---- helpers ----

    private MvcResult delete(MockHttpSession session, User target, String email, String reason, String token,
                             boolean acknowledged) throws Exception {
        return callJson(session, post("/api/users/{id}/delete", target.getId()),
                objectMapper.writeValueAsString(java.util.Map.of("email", email, "reason", reason, "token", token,
                        "acknowledged", acknowledged)));
    }

    private void notice(User user, Control control) {
        Notification notification = new Notification();
        notification.setUserId(user.getId());
        notification.setControlId(control.getId());
        notification.setType("WORKFLOW_STEP");
        notification.setTitle("For the person");
        notification.setMessage("Message");
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    private WorkflowHistory history(Control control, User person) {
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(control.getId());
        history.setActionType(WorkflowActionType.SUBMIT_TO_OPERATOR);
        history.setFromStep("IN_PROGRESS");
        history.setToStep("REVIEW");
        history.setPerformedByEmail(person.getMail());
        history.setPerformedByName(person.getDisplayName());
        history.setCreatedAt(LocalDateTime.now().minusDays(1));
        return historyRepository.save(history);
    }

    private ControlAttachment upload(Control control, User person) {
        ControlAttachment attachment = new ControlAttachment();
        attachment.setControlId(control.getId());
        attachment.setTab("DETAILS");
        attachment.setFileName("person-upload.pdf");
        attachment.setUploadedByEmail(person.getMail());
        attachment.setUploadedStage("IN_PROGRESS");
        attachment.setUploadedAt(LocalDateTime.now());
        return attachmentRepository.save(attachment);
    }

    private AdminAuditLog audit(User person) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAdminEmail(person.getMail());
        log.setAdminName(person.getDisplayName());
        log.setActionType("EDIT");
        log.setActionDescription("Edit Control");
        return auditRepository.save(log);
    }
}
