package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.service.ControlScheduleCalculator;
import com.kpmg.qtracker.service.SoqmYear;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release test pass, stage C: whole workflow chains through the API, as the pages call it - creation by SoQM Team,
 * assignment, Initiate, the four steps, returns with their comment, SoQM acting for any role, returns from
 * COMPLETED and completing again, the completed-control edit, double clicks and parallel moves, moves that are not
 * allowed.
 */
class ReleaseScenarioWorkflowIT extends ReleaseScenarioSupport {

    // ---- C1: the chain ----

    @Test
    void wholeChain_differentPeople_fromCreationToCompleted_withHistoryAndNotifications() throws Exception {
        User soqm = soqm("wf-soqm");
        User fac = myEdit("wf-fac");
        User op = myEdit("wf-op");
        User po = myEdit("wf-po");
        Chain chain = new Chain(soqm, fac, op, po);
        Control control = chain.createAndAssign();
        assertThat(reload(control).getPerformanceStatus()).isEqualTo("DRAFT");
        // Nobody but SoQM sees work on a draft: the Facilitator cannot fill it yet
        assertThat(chain.saveSteps(fac, control, "Too early")).isEqualTo(403);

        chain.ok(soqm, initiate(control));
        assertStatus(control, "IN_PROGRESS");
        assertThat(newNotifications(fac, control)).as("Facilitator told to start").isNotEmpty();

        // Facilitator: steps field + evidence, then Submit for Review
        assertThat(chain.saveSteps(fac, control, "Facilitator steps")).isEqualTo(200);
        chain.ok(fac, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDetails", "evidence-fac.pdf", "application/pdf", pdf())));
        chain.ok(fac, post("/api/workflow/submit-to-control-operator").param("controlId", id(control)));
        assertStatus(control, "REVIEW");
        assertThat(newNotifications(op, control)).as("Control Operator told").isNotEmpty();

        // Control Operator completes the steps field, Submit to SoQM Team
        assertThat(chain.saveSteps(op, control, "Facilitator steps + operator check")).isEqualTo(200);
        chain.ok(op, post("/api/workflow/submit-to-soqm-lead").param("controlId", id(control)));
        assertStatus(control, "SOQM_HEAD_REVIEW");

        // SoQM review: their comments field is required for the step
        assertThat(chain.status(soqm, post("/api/workflow/submit-to-process-owner").param("controlId", id(control))))
                .isEqualTo(400);
        chain.okJson(soqm, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"soqmHeadComments\":\"SoQM reviewed\"}");
        chain.ok(soqm, post("/api/workflow/submit-to-process-owner").param("controlId", id(control)));
        assertStatus(control, "PROCESS_OWNER_REVIEW");
        assertThat(newNotifications(po, control)).as("Process Owner told").isNotEmpty();

        // Process Owner: comments required, then Complete
        assertThat(chain.status(po, post("/api/workflow/complete-control").param("controlId", id(control))))
                .isEqualTo(400);
        chain.okJson(po, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"processOwnerComments\":\"Approved\"}");
        chain.ok(po, post("/api/workflow/complete-control").param("controlId", id(control)));
        assertStatus(control, "COMPLETED");

        // History: every move once, in order, by whom, nobody on behalf
        List<WorkflowHistory> history = history(control);
        assertThat(history).extracting(WorkflowHistory::getToStep)
                .containsExactly("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");
        assertThat(history).extracting(WorkflowHistory::getPerformedByEmail)
                .containsExactly(soqm.getMail(), fac.getMail(), op.getMail(), soqm.getMail(), po.getMail());
        assertThat(history).noneMatch(WorkflowHistory::isOnBehalf);
        // Fields survive the whole chain
        assertThat(detailsRepository.findByControlId(control.getId()).orElseThrow().getControlStepsPerformed())
                .isEqualTo("Facilitator steps + operator check");
        assertThat(reload(control).getAttachmentDetailsPath()).contains("evidence-fac.pdf");
        // Completed: locked for the participants, in the list as completed, not late
        assertThat(chain.saveSteps(fac, control, "After completion")).isEqualTo(403);
        com.kpmg.qtracker.service.ComponentControlsList.Row row = row(chain.session(soqm), control);
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.closedLate()).isFalse();
        // Changelog lists the moves
        JsonNode changelog = json(call(chain.session(soqm), get("/api/controls/{id}/changelog", control.getId())));
        assertThat(changelog.toString()).contains("Completed").contains(po.getMail());
    }

    @Test
    void wholeChain_onePersonInEveryStepField_movesItAlone() throws Exception {
        User soqm = soqm("wf1-soqm");
        User all = myEdit("wf1-all");
        Chain chain = new Chain(soqm, all, all, all);
        Control control = chain.createAndAssign();
        chain.ok(soqm, initiate(control));
        assertThat(chain.saveSteps(all, control, "Done by one person")).isEqualTo(200);
        chain.ok(all, post("/api/workflow/submit-to-control-operator").param("controlId", id(control)));
        chain.ok(all, post("/api/workflow/submit-to-soqm-lead").param("controlId", id(control)));
        chain.okJson(soqm, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"soqmHeadComments\":\"Reviewed\"}");
        chain.ok(soqm, post("/api/workflow/submit-to-process-owner").param("controlId", id(control)));
        chain.okJson(all, post("/api/control-details"),
                "{\"controlId\":" + control.getId() + ",\"processOwnerComments\":\"Approved by the same person\"}");
        chain.ok(all, post("/api/workflow/complete-control").param("controlId", id(control)));
        assertStatus(control, "COMPLETED");
        assertThat(history(control)).hasSize(5).noneMatch(WorkflowHistory::isOnBehalf);
    }

    @Test
    void theOperationDate_savedOnTheAssignmentTab_isStored_newOrChanged_withItsDeadline() throws Exception {
        User soqm = soqm("od-soqm");
        Chain chain = new Chain(soqm, myEdit("od-fac"), myEdit("od-op"), myEdit("od-po"));
        Control control = chain.createAndAssign();
        ControlAssignment first = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(first.getControlOperationDate()).isEqualTo(today().plusDays(5));
        assertThat(first.getControlOperationDeadline()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today().plusDays(5)));

        chain.okJson(soqm, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlOperationDate\":\"" + today().plusDays(30) + "\"}");
        ControlAssignment changed = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(changed.getControlOperationDate()).isEqualTo(today().plusDays(30));
        assertThat(changed.getControlOperationDeadline()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today().plusDays(30)));
        assertThat(reload(control).getDeadline()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(today().plusDays(30)));
        // The page reads it back
        JsonNode read = json(call(chain.session(soqm), get("/api/control-assignment")
                .param("controlId", String.valueOf(control.getId()))));
        assertThat(read.path("controlOperationDate").asText()).isEqualTo(today().plusDays(30).toString());
    }

    // ---- C2: returns ----

    @Test
    void returns_operatorToFacilitator_soqmToOperator_ownerToOperator_needAComment_keepTheFields_andNotify()
            throws Exception {
        User soqm = soqm("rt-soqm");
        User fac = myEdit("rt-fac");
        User op = myEdit("rt-op");
        User po = myEdit("rt-po");
        Chain chain = new Chain(soqm, fac, op, po);

        // Control Operator -> Facilitator
        Control review = control("RS-RET", "REVIEW", chain.people());
        for (String blank : new String[]{null, "", "   "}) {
            var request = post("/api/workflow/return-to-facilitator").param("controlId", id(review));
            if (blank != null) {
                request.param("comments", blank);
            }
            assertThat(chain.status(op, request)).as("comment '%s'", blank).isEqualTo(400);
        }
        assertStatus(review, "REVIEW");
        chain.ok(op, post("/api/workflow/return-to-facilitator").param("controlId", id(review))
                .param("comments", "Evidence is missing"));
        assertReturned(review, "REVIEW", "IN_PROGRESS", op, "Evidence is missing", fac, chain);

        // SoQM -> Control Operator
        Control soqmReview = control("RS-RET", "SOQM_HEAD_REVIEW", chain.people());
        assertThat(chain.status(soqm, post("/api/workflow/return-to-operator").param("controlId", id(soqmReview))))
                .isEqualTo(400);
        chain.ok(soqm, post("/api/workflow/return-to-operator").param("controlId", id(soqmReview))
                .param("comments", "Wrong period"));
        assertReturned(soqmReview, "SOQM_HEAD_REVIEW", "REVIEW", soqm, "Wrong period", op, chain);

        // Process Owner -> Control Operator
        Control ownerReview = control("RS-RET", "PROCESS_OWNER_REVIEW", chain.people());
        assertThat(chain.status(po, post("/api/workflow/return-to-operator").param("controlId", id(ownerReview))))
                .isEqualTo(400);
        chain.ok(po, post("/api/workflow/return-to-operator").param("controlId", id(ownerReview))
                .param("comments", "Please explain the variance"));
        assertReturned(ownerReview, "PROCESS_OWNER_REVIEW", "REVIEW", po, "Please explain the variance", op, chain);
    }

    private void assertReturned(Control control, String from, String to, User actor, String comment, User told,
                                Chain chain) throws Exception {
        assertStatus(control, to);
        WorkflowHistory last = history(control).get(history(control).size() - 1);
        assertThat(last.getFromStep()).isEqualTo(from);
        assertThat(last.getToStep()).isEqualTo(to);
        assertThat(last.getPerformedByEmail()).isEqualTo(actor.getMail());
        assertThat(last.getComments()).isEqualTo(comment);
        assertThat(last.getCreatedAt()).isNotNull();
        // Fields stay as they were
        var details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        assertThat(details.getControlStepsPerformed()).isEqualTo("Steps performed");
        assertThat(details.getProcessOwnerComments()).isEqualTo("Owner comments");
        assertThat(details.getSoqmHeadComments()).isEqualTo("SoQM comments");
        assertThat(reload(control).getAttachmentDetailsPath()).isNotBlank();
        // The person of the target step is told, with the reason
        List<Notification> notes = newNotifications(told, control);
        assertThat(notes).as("notification to %s", told.getMail()).isNotEmpty();
        assertThat(notes).anyMatch(n -> String.valueOf(n.getMessage()).contains(comment)
                || String.valueOf(n.getTitle()).toLowerCase().contains("return"));
        // The reason is in the Changelog
        assertThat(body(call(chain.session(chain.soqm), get("/api/controls/{id}/changelog", control.getId()))))
                .contains(comment);
    }

    // ---- C3: SoQM on behalf ----

    @Test
    void soqm_actsForEveryRole_forwardAndBack_withAComment_recordedOnBehalf() throws Exception {
        User soqm = soqm("ob-soqm");
        User fac = myEdit("ob-fac");
        User op = myEdit("ob-op");
        User po = myEdit("ob-po");
        Chain chain = new Chain(soqm, fac, op, po);
        Control control = control("RS-OB", "IN_PROGRESS", chain.people());

        // Without a comment SoQM cannot act for another role
        assertThat(chain.status(soqm, post("/api/workflow/submit-to-control-operator").param("controlId", id(control))))
                .isEqualTo(400);
        chain.ok(soqm, post("/api/workflow/submit-to-control-operator").param("controlId", id(control))
                .param("comments", "Facilitator is on leave"));
        chain.ok(soqm, post("/api/workflow/return-to-facilitator").param("controlId", id(control))
                .param("comments", "Back for the Facilitator"));
        chain.ok(soqm, post("/api/workflow/submit-to-control-operator").param("controlId", id(control))
                .param("comments", "Again for the Facilitator"));
        chain.ok(soqm, post("/api/workflow/submit-to-soqm-lead").param("controlId", id(control))
                .param("comments", "For the Control Operator"));
        chain.ok(soqm, post("/api/workflow/submit-to-process-owner").param("controlId", id(control)));
        chain.ok(soqm, post("/api/workflow/return-to-operator").param("controlId", id(control))
                .param("comments", "Process Owner asked by phone"));
        chain.ok(soqm, post("/api/workflow/submit-to-soqm-lead").param("controlId", id(control))
                .param("comments", "Operator again"));
        chain.ok(soqm, post("/api/workflow/submit-to-process-owner").param("controlId", id(control)));
        chain.ok(soqm, post("/api/workflow/complete-control").param("controlId", id(control))
                .param("comments", "Process Owner approved by e-mail"));
        assertStatus(control, "COMPLETED");

        List<WorkflowHistory> history = history(control);
        assertThat(history).extracting(WorkflowHistory::getToStep).containsExactly("REVIEW", "IN_PROGRESS", "REVIEW",
                "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW",
                "COMPLETED");
        assertThat(history).allMatch(h -> soqm.getMail().equals(h.getPerformedByEmail()));
        // On behalf for the participants' steps; SoQM's own step (SoQM review -> PO) is not on behalf
        assertThat(history).extracting(WorkflowHistory::isOnBehalf)
                .containsExactly(true, true, true, true, false, true, true, false, true);
        assertThat(history.get(0).getAssignedPerformer()).contains(fac.getMail());
        assertThat(history.get(8).getAssignedPerformer()).contains(po.getMail());
        // The Changelog marks them (the page writes "On behalf of" from the flag)
        assertThat(body(call(chain.session(soqm), get("/api/controls/{id}/changelog", control.getId()))))
                .contains("\"onBehalf\":true").contains("Process Owner approved by e-mail");
        // The assigned people get an in-app copy
        assertThat(newNotifications(fac, control)).isNotEmpty();
        assertThat(newNotifications(po, control)).isNotEmpty();
    }

    // ---- C4: back from COMPLETED ----

    @Test
    void fromCompleted_soqmReturnsToEachEarlierStatus_andCompletesAgain_reopenedAndClosedLateRecalculated()
            throws Exception {
        User soqm = soqm("rc-soqm");
        User fac = myEdit("rc-fac");
        User op = myEdit("rc-op");
        User po = myEdit("rc-po");
        Chain chain = new Chain(soqm, fac, op, po);
        for (String target : List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW")) {
            // Deadline 10 days ago, first completed 12 days ago: on time
            Control control = control("RS-RC", "COMPLETED", chain.people());
            ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
            assignment.setControlOperationDate(today().minusDays(24));
            assignment.setControlOperationDeadline(today().minusDays(10));
            assignmentRepository.save(assignment);
            completedOn(control, po, LocalDateTime.now().minusDays(12));
            assertThat(row(chain.session(soqm), control).closedLate()).isFalse();

            // Not without a comment; not by the Process Owner; not to draft
            assertThat(chain.status(soqm, move(control, target, null))).isEqualTo(400);
            assertThat(chain.status(po, move(control, target, "PO tries"))).isIn(403, 409);
            assertThat(chain.status(soqm, move(control, "DRAFT", "To draft"))).isIn(403, 409);
            chain.ok(soqm, move(control, target, "Reopened for a correction"));
            assertStatus(control, target);
            assertThat(reload(control).getReopenedAt()).as("reopened_at after the return to %s", target).isNotNull();
            assertThat(row(chain.session(soqm), control).overdue()).as("reopened past deadline shows Overdue").isTrue();

            // ... and completed again (SoQM moves it on, the last step for the Process Owner)
            String status = target;
            while (!"PROCESS_OWNER_REVIEW".equals(status)) {
                chain.ok(soqm, move(control, next(status), "Again"));
                status = next(status);
            }
            chain.ok(po, post("/api/workflow/complete-control").param("controlId", id(control)));
            assertStatus(control, "COMPLETED");
            assertThat(reload(control).getReopenedAt()).isNull();
            com.kpmg.qtracker.service.ComponentControlsList.Row row = row(chain.session(soqm), control);
            assertThat(row.closedLate()).as("completed again after the deadline: Closed late").isTrue();
            assertThat(row.overdue()).isFalse();
        }
    }

    private static String next(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "REVIEW";
            case "REVIEW" -> "SOQM_HEAD_REVIEW";
            default -> "PROCESS_OWNER_REVIEW";
        };
    }

    // ---- C5: completed control edited by SoQM Team ----

    @Test
    void completedControl_soqmEditsWithAReason_fixedFieldsStay_everyoneElseIsRefused() throws Exception {
        User soqm = soqm("ce-soqm");
        User fac = myEdit("ce-fac");
        User op = myEdit("ce-op");
        User po = myEdit("ce-po");
        User kdnUser = kdn("ce-kdn");
        Chain chain = new Chain(soqm, fac, op, po);
        Control control = control("RS-CE", "COMPLETED", chain.people().shared(kdnUser));
        completedOn(control, po, LocalDateTime.now().minusDays(1));

        assertThat(chain.statusJson(soqm, put("/api/controls/{id}", control.getId()),
                "{\"controlDescription\":\"Corrected\"}")).isEqualTo(400);
        assertThat(chain.statusJson(soqm, put("/api/controls/{id}", control.getId()),
                "{\"controlDescription\":\"Corrected\",\"editReason\":\"Typo\"}")).isEqualTo(200);
        assertThat(chain.statusJson(soqm, put("/api/controls/{id}", control.getId()),
                "{\"controlFrequency\":\"Quarterly\",\"editReason\":\"Wrong frequency\"}")).isEqualTo(403);
        assertThat(chain.statusJson(soqm, post("/api/control-assignment"),
                "{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"" + today().plusDays(40)
                        + "\",\"editReason\":\"Move it\"}")).isEqualTo(403);
        for (User user : List.of(fac, op, po)) {
            assertThat(chain.statusJson(user, put("/api/controls/{id}", control.getId()),
                    "{\"controlDescription\":\"By " + user.getDisplayName() + "\",\"editReason\":\"x\"}"))
                    .as(user.getMail()).isEqualTo(403);
            assertThat(chain.statusJson(user, post("/api/control-details"), "{\"controlId\":" + control.getId()
                    + ",\"processOwnerComments\":\"x\",\"editReason\":\"x\"}")).as(user.getMail()).isEqualTo(403);
        }
        Control after = reload(control);
        assertThat(after.getControlDescription()).isEqualTo("Corrected");
        assertThat(after.getControlFrequency()).isEqualTo("Monthly");
        assertThat(after.getPerformanceStatus()).isEqualTo("COMPLETED");
        assertThat(body(call(chain.session(soqm), get("/api/controls/{id}/changelog", control.getId()))))
                .contains("\"editedAfterCompletion\":true").contains("\"reason\":\"Typo\"");
    }

    // ---- C6: double clicks, parallel moves, a stale page ----

    @Test
    void doubleClickOnASubmit_movesTheControlOnce_oneHistoryEntry_oneNotificationPerPerson() throws Exception {
        User soqm = soqm("dc-soqm");
        User fac = myEdit("dc-fac");
        User op = myEdit("dc-op");
        User po = myEdit("dc-po");
        Chain chain = new Chain(soqm, fac, op, po);
        chain.session(fac);
        List<String> observed = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            Control control = control("RS-DC", "IN_PROGRESS", chain.people());
            int notesBefore = notifications(op, control).size();
            List<Integer> answers = parallel(2, () -> chain.status(fac,
                    post("/api/workflow/submit-to-control-operator").param("controlId", id(control))));
            long moves = history(control).stream().filter(h -> "REVIEW".equals(h.getToStep())).count();
            int notes = notifications(op, control).size() - notesBefore;
            observed.add("answers " + answers + ", history " + moves + ", notifications to CO " + notes);
            assertStatus(control, "REVIEW");
            assertThat(answers).as("round %d: %s", round, observed).containsOnlyOnce(200);
            assertThat(moves).as("round %d: %s", round, observed).isEqualTo(1);
            assertThat(notes).as("round %d: %s", round, observed).isEqualTo(1);
        }
    }

    @Test
    void doubleClickOnInitiate_initiatesOnce() throws Exception {
        User soqm = soqm("di-soqm");
        Chain chain = new Chain(soqm, myEdit("di-fac"), myEdit("di-op"), myEdit("di-po"));
        chain.session(soqm);
        for (int round = 0; round < 3; round++) {
            Control control = control("RS-DI", "DRAFT", chain.people());
            List<Integer> answers = parallel(2, () -> chain.status(soqm, initiate(control)));
            assertStatus(control, "IN_PROGRESS");
            assertThat(answers).as("round %d", round).containsOnlyOnce(200);
            assertThat(history(control)).as("round %d answers %s", round, answers).hasSize(1);
        }
    }

    @Test
    void twoPeopleMovingAtTheSameTime_onlyOneMoveHappens_theOtherIsRefused() throws Exception {
        User soqm = soqm("pm-soqm");
        User fac = myEdit("pm-fac");
        User op = myEdit("pm-op");
        User po = myEdit("pm-po");
        Chain chain = new Chain(soqm, fac, op, po);
        chain.session(op);
        chain.session(soqm);
        for (int round = 0; round < 5; round++) {
            Control control = control("RS-PM", "REVIEW", chain.people());
            // The Control Operator submits while SoQM returns it to the Facilitator
            List<Integer> answers = parallel2(
                    () -> chain.status(op, post("/api/workflow/submit-to-soqm-lead").param("controlId", id(control))),
                    () -> chain.status(soqm, post("/api/workflow/return-to-facilitator").param("controlId", id(control))
                            .param("comments", "SoQM pulls it back")));
            List<WorkflowHistory> history = history(control);
            assertThat(history).as("round %d answers %s history %s", round, answers,
                    history.stream().map(h -> h.getFromStep() + "->" + h.getToStep()).toList()).hasSize(1);
            assertThat(answers).as("round %d", round).containsOnlyOnce(200);
            assertThat(reload(control).getPerformanceStatus()).isEqualTo(history.get(0).getToStep());
        }
    }

    @Test
    void aStalePage_afterSomeoneElseMovedTheControl_cannotMoveItAgain() throws Exception {
        User soqm = soqm("st-soqm");
        User fac = myEdit("st-fac");
        User op = myEdit("st-op");
        User po = myEdit("st-po");
        Chain chain = new Chain(soqm, fac, op, po);
        Control control = control("RS-ST", "REVIEW", chain.people());
        // The Control Operator's page still says Review; SoQM returns it meanwhile
        chain.ok(soqm, post("/api/workflow/return-to-facilitator").param("controlId", id(control))
                .param("comments", "Back to the Facilitator"));
        assertThat(chain.status(op, post("/api/workflow/submit-to-soqm-lead").param("controlId", id(control))))
                .isIn(403, 409);
        assertThat(chain.saveSteps(op, control, "From the stale page")).isEqualTo(403);
        assertStatus(control, "IN_PROGRESS");
        assertThat(history(control)).hasSize(1);
    }

    // ---- C7: moves that are not allowed ----

    @Test
    void movesThatSkipAStepOrComeFromTheWrongPerson_areRefused() throws Exception {
        User soqm = soqm("bad-soqm");
        User fac = myEdit("bad-fac");
        User op = myEdit("bad-op");
        User po = myEdit("bad-po");
        Chain chain = new Chain(soqm, fac, op, po);
        Control control = control("RS-BAD", "IN_PROGRESS", chain.people());
        String id = id(control);
        // Skipping a step
        assertThat(chain.status(soqm, move(control, "SOQM_HEAD_REVIEW", "Skip"))).isEqualTo(409);
        assertThat(chain.status(soqm, move(control, "COMPLETED", "Skip"))).isEqualTo(409);
        assertThat(chain.status(fac, move(control, "PROCESS_OWNER_REVIEW", "Skip"))).isIn(403, 409);
        assertThat(chain.status(fac, post("/api/workflow/complete-control").param("controlId", id))).isIn(403, 409);
        assertThat(chain.status(soqm, post("/api/workflow/submit-to-process-owner").param("controlId", id))).isEqualTo(409);
        // Not this person's step
        assertThat(chain.status(op, post("/api/workflow/submit-to-control-operator").param("controlId", id))).isEqualTo(403);
        assertThat(chain.status(po, post("/api/workflow/submit-to-control-operator").param("controlId", id))).isEqualTo(403);
        // Unknown target, unknown action, unknown control
        assertThat(chain.status(soqm, move(control, "NOT_A_STATUS", "x"))).isIn(400, 409);
        assertThat(chain.statusJson(soqm, post("/api/workflow/perform-action"),
                "{\"controlId\":" + id + ",\"action\":\"TELEPORT\",\"comment\":\"x\"}")).isEqualTo(400);
        assertThat(chain.status(soqm, post("/api/workflow/submit-to-control-operator").param("controlId", "987654321")))
                .isEqualTo(404);
        assertStatus(control, "IN_PROGRESS");
        assertThat(history(control)).isEmpty();
    }

    // ---- helpers ----

    /** The people of a chain, signed in once each. */
    private final class Chain {
        final User soqm;
        final User fac;
        final User op;
        final User po;
        private final java.util.Map<Long, MockHttpSession> sessions = new java.util.HashMap<>();

        Chain(User soqm, User fac, User op, User po) {
            this.soqm = soqm;
            this.fac = fac;
            this.op = op;
            this.po = po;
        }

        People people() {
            return ReleaseScenarioSupport.people().facilitator(fac).operator(op).soqm(soqm).owner(po);
        }

        MockHttpSession session(User user) throws Exception {
            MockHttpSession session = sessions.get(user.getId());
            if (session == null) {
                session = login(user);
                sessions.put(user.getId(), session);
            }
            return session;
        }

        /** As SoQM Team on the pages: a new control, then its Assignment tab. */
        Control createAndAssign() throws Exception {
            String controlId = "HR-CTRL-RS-" + next() + "/FY27/" + RUN;
            okJson(soqm, post("/api/controls"), "{\"controlId\":\"" + controlId + "\",\"controlFrequency\":\"Monthly\","
                    + "\"controlCategory\":\"Manual\",\"controlType\":\"Preventive\",\"component\":\"HR\","
                    + "\"operatedBy\":\"Finance\",\"priority\":\"High\",\"nonAuditServicesApplicability\":\"No\","
                    + "\"controlDescription\":\"Created in the chain\",\"prp\":\"PRP\",\"soqmYear\":\""
                    + SoqmYear.current(today()) + "\"}");
            Control control = controlRepository.findByControlId(controlId).orElseThrow();
            okJson(soqm, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                    + ",\"facilitator\":[\"" + fac.getMail() + "\"],\"controlOperator\":[\"" + op.getMail() + "\"]"
                    + ",\"soqmLead\":[\"" + soqm.getMail() + "\"],\"processOwner\":[\"" + po.getMail() + "\"]"
                    + ",\"controlOperationDate\":\"" + today().plusDays(5) + "\"}");
            okJson(soqm, post("/api/control-details"), "{\"controlId\":" + control.getId()
                    + ",\"processName\":\"Payroll\",\"processActivities\":\"Payroll run\"}");
            return control;
        }

        int saveSteps(User user, Control control, String text) throws Exception {
            return statusJson(user, post("/api/control-details"),
                    "{\"controlId\":" + control.getId() + ",\"controlStepsPerformed\":\"" + text + "\"}");
        }

        void ok(User user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
                throws Exception {
            MvcResult result = call(session(user), request);
            assertThat(result.getResponse().getStatus()).as("%s %s: %s", user.getMail(),
                    result.getRequest().getRequestURI(), body(result)).isEqualTo(200);
        }

        void okJson(User user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                    String json) throws Exception {
            MvcResult result = callJson(session(user), request, json);
            assertThat(result.getResponse().getStatus()).as("%s %s: %s", user.getMail(),
                    result.getRequest().getRequestURI(), body(result)).isEqualTo(200);
        }

        int status(User user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
                throws Exception {
            return call(session(user), request).getResponse().getStatus();
        }

        int statusJson(User user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                       String json) throws Exception {
            return callJson(session(user), request, json).getResponse().getStatus();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder initiate(Control control) {
        return post("/api/performance/initiate").param("controlId", id(control))
                .param("soqmYear", SoqmYear.current(today()));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder move(
            Control control, String target, String comment) {
        var request = post("/api/workflow/move").param("controlId", id(control)).param("targetStatus", target);
        return comment == null ? request : request.param("comments", comment);
    }

    private void completedOn(Control control, User po, LocalDateTime when) {
        WorkflowHistory done = new WorkflowHistory();
        done.setControlId(control.getId());
        done.setActionType(com.kpmg.qtracker.enums.WorkflowActionType.APPROVE);
        done.setFromStep("PROCESS_OWNER_REVIEW");
        done.setToStep("COMPLETED");
        done.setPerformedByEmail(po.getMail());
        done.setPerformedByName(po.getDisplayName());
        done.setCreatedAt(when);
        historyRepository.save(done);
    }

    private void assertStatus(Control control, String status) {
        assertThat(reload(control).getPerformanceStatus()).isEqualTo(status);
    }

    private List<WorkflowHistory> history(Control control) {
        List<WorkflowHistory> list = new ArrayList<>(historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId()));
        list.sort(Comparator.comparing(WorkflowHistory::getId));
        return list;
    }

    private List<Notification> notifications(User user, Control control) {
        return notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .filter(n -> user.getId().equals(n.getUserId()))
                .toList();
    }

    private List<Notification> newNotifications(User user, Control control) {
        return notifications(user, control).stream().filter(n -> !Boolean.TRUE.equals(n.getIsRead())).toList();
    }

    /** The control's row in the All components list (the Controls page is gone), found by its Control ID. */
    private com.kpmg.qtracker.service.ComponentControlsList.Row row(MockHttpSession session, Control control)
            throws Exception {
        String q = java.net.URLEncoder.encode(reload(control).getControlId(), java.nio.charset.StandardCharsets.UTF_8);
        return listRows(session, "/component/ALL?q=" + q).stream()
                .filter(r -> control.getId().equals(r.id())).findFirst().orElseThrow();
    }

    private static String id(Control control) {
        return String.valueOf(control.getId());
    }

    private static byte[] pdf() {
        return "%PDF-1.4 evidence".getBytes(StandardCharsets.UTF_8);
    }

    /** The same request n times at once (a double click). */
    private static List<Integer> parallel(int n, Callable<Integer> request) throws Exception {
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            requests.add(request);
        }
        return runTogether(requests);
    }

    private static List<Integer> parallel2(Callable<Integer> first, Callable<Integer> second) throws Exception {
        return runTogether(List.of(first, second));
    }

    private static List<Integer> runTogether(List<Callable<Integer>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<Integer> answers = new ArrayList<>();
            for (Future<Integer> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
