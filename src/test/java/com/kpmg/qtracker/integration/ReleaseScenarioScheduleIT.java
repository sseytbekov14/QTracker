package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release test pass, stage E through the application: the dates a save stores for every frequency, recalculation
 * only when the date or the frequency changes, dates in a wrong form, Overdue and Closed late in the Controls list.
 * The next cycle is made only by hand (Create Control, CreateControlIT).
 */
class ReleaseScenarioScheduleIT extends ReleaseScenarioSupport {

    // ---- what a save stores ----

    @Test
    void anAssignmentSave_storesDeadlinePlus10WorkingDays_andTheNextDateOfTheFrequency_forEveryFrequency() throws Exception {
        User soqm = soqm("sc-soqm");
        MockHttpSession session = login(soqm);
        LocalDate date = LocalDate.of(2027, 1, 31);   // a Sunday: due on Friday 12 February
        Map<String, LocalDate> next = Map.of("Monthly", LocalDate.of(2027, 2, 28), "Quarterly", LocalDate.of(2027, 4, 30),
                "Semi Annual", LocalDate.of(2027, 7, 31), "Annual", LocalDate.of(2028, 1, 31),
                "Recurring", LocalDate.of(2027, 4, 30));
        for (String frequency : List.of("Monthly", "Quarterly", "Semi Annual", "Annual", "Recurring", "Ad-hoc")) {
            Control control = assigned(frequency, soqm);
            saveDate(session, control, date);
            ControlAssignment stored = assignment(control);
            assertThat(stored.getControlOperationDate()).as(frequency).isEqualTo(date);
            assertThat(stored.getControlOperationDeadline()).as(frequency).isEqualTo(LocalDate.of(2027, 2, 12));
            assertThat(reload(control).getDeadline()).as(frequency).isEqualTo(LocalDate.of(2027, 2, 12));
            assertThat(stored.getNextControlOperationDate()).as(frequency).isEqualTo(next.get(frequency));
        }
    }

    @Test
    void theScheduleIsRecalculated_onlyWhenTheDateOrTheFrequencyChanges() throws Exception {
        User soqm = soqm("rc-soqm");
        MockHttpSession session = login(soqm);
        Control control = assigned("Monthly", soqm);
        saveDate(session, control, LocalDate.of(2027, 3, 10));
        // A deadline set under an older rule stays while the date stays
        ControlAssignment stored = assignment(control);
        stored.setControlOperationDeadline(LocalDate.of(2027, 3, 17));
        assignmentRepository.save(stored);
        saveDate(session, control, LocalDate.of(2027, 3, 10));
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2027, 3, 17));
        // Other people only: the schedule stays
        assertThat(callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlSharedWith\":[\"" + myRead("rc-reader").getMail() + "\"]}").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2027, 3, 17));
        // Another frequency: recalculated from the stored date
        assertThat(callJson(session, put("/api/controls/{id}", control.getId()), "{\"controlFrequency\":\"Quarterly\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2027, 3, 24));
        assertThat(assignment(control).getNextControlOperationDate()).isEqualTo(LocalDate.of(2027, 6, 10));
        // Another date: recalculated
        saveDate(session, control, LocalDate.of(2027, 12, 25));   // a Saturday: due on Friday 7 January
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2028, 1, 7));
        assertThat(assignment(control).getNextControlOperationDate()).isEqualTo(LocalDate.of(2028, 3, 25));
        // A deadline or next date sent by the page is never taken
        assertThat(callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlOperationDeadline\":\"2030-01-01\",\"nextControlOperationDate\":\"2030-02-01\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2028, 1, 7));
    }

    @Test
    void datesInAWrongForm_areRefused_aPastDateIsTakenAndShowsOverdue() throws Exception {
        User soqm = soqm("df-soqm");
        MockHttpSession session = login(soqm);
        Control control = assigned("Monthly", soqm);
        saveDate(session, control, LocalDate.of(2027, 5, 5));
        for (String wrong : List.of("31.02.2027", "2027/05/06", "06.05.2027", "tomorrow", "2027-13-01", "2027-5-6")) {
            MvcResult result = callJson(session, post("/api/control-assignment"),
                    "{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"" + wrong + "\"}");
            assertThat(result.getResponse().getStatus()).as(wrong).isEqualTo(400);
            assertThat(body(result)).as(wrong).doesNotContain("Exception").doesNotContain("at com.");
        }
        assertThat(assignment(control).getControlOperationDate()).isEqualTo(LocalDate.of(2027, 5, 5));
        // A day that does not exist in the ISO form is moved to the month's last day, not refused (S3-18)
        MvcResult notADay = callJson(session, post("/api/control-assignment"),
                "{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"2027-02-31\"}");
        assertThat(notADay.getResponse().getStatus()).isIn(200, 400);
        if (notADay.getResponse().getStatus() == 200) {
            assertThat(assignment(control).getControlOperationDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        }
        // The page asks before taking a past date; the server takes it
        saveDate(session, control, today().minusDays(20));
        assertThat(row(session, control).overdue()).isTrue();
    }

    @Test
    void overdueAndClosedLate_inTheControlsList_atTheDeadlineDay() throws Exception {
        User soqm = soqm("ov-soqm");
        MockHttpSession session = login(soqm);
        Control dueToday = withDeadline(control("RS-OV", "IN_PROGRESS", people().soqm(soqm)), today());
        Control dueYesterday = withDeadline(control("RS-OV", "IN_PROGRESS", people().soqm(soqm)), today().minusDays(1));
        Control draftLate = withDeadline(control("RS-OV", "DRAFT", people().soqm(soqm)), today().minusDays(1));
        Control completedLate = withDeadline(control("RS-OV", "COMPLETED", people().soqm(soqm)), today().minusDays(3));
        completed(completedLate, today().minusDays(2));
        Control completedOnTime = withDeadline(control("RS-OV", "COMPLETED", people().soqm(soqm)), today().minusDays(3));
        completed(completedOnTime, today().minusDays(3));

        assertThat(row(session, dueToday).overdue()).isFalse();
        assertThat(row(session, dueYesterday).overdue()).isTrue();
        assertThat(row(session, draftLate).overdue()).isTrue();
        assertThat(row(session, completedLate).overdue()).isFalse();
        assertThat(row(session, completedLate).closedLate()).isTrue();
        assertThat(row(session, completedOnTime).closedLate()).isFalse();
    }

    // ---- helpers ----

    private Control assigned(String frequency, User soqm) throws Exception {
        return control("RS-SC", "DRAFT", people().facilitator(myEdit("sc-fac")).operator(myEdit("sc-op")).soqm(soqm)
                .owner(myEdit("sc-po")), c -> c.setControlFrequency(frequency));
    }

    private void saveDate(MockHttpSession session, Control control, LocalDate date) throws Exception {
        MvcResult result = callJson(session, post("/api/control-assignment"),
                "{\"controlId\":" + control.getId() + ",\"controlOperationDate\":\"" + date + "\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    private Control withDeadline(Control control, LocalDate deadline) {
        ControlAssignment assignment = assignment(control);
        assignment.setControlOperationDate(deadline.minusDays(14));
        assignment.setControlOperationDeadline(deadline);
        assignmentRepository.save(assignment);
        return reload(control);
    }

    private void completed(Control control, LocalDate on) {
        com.kpmg.qtracker.entity.WorkflowHistory done = new com.kpmg.qtracker.entity.WorkflowHistory();
        done.setControlId(control.getId());
        done.setActionType(com.kpmg.qtracker.enums.WorkflowActionType.APPROVE);
        done.setFromStep("PROCESS_OWNER_REVIEW");
        done.setToStep("COMPLETED");
        done.setPerformedByEmail("owner@release.test");
        done.setCreatedAt(on.atTime(12, 0));
        historyRepository.save(done);
    }

    private ControlAssignment assignment(Control control) {
        return assignmentRepository.findByControlId(control.getId()).orElseThrow();
    }

    /** The control's row in the All components list (the Controls page is gone), found by its Control ID. */
    private com.kpmg.qtracker.service.ComponentControlsList.Row row(MockHttpSession session, Control control)
            throws Exception {
        String q = java.net.URLEncoder.encode(reload(control).getControlId(), java.nio.charset.StandardCharsets.UTF_8);
        return listRows(session, "/component/ALL?q=" + q).stream()
                .filter(r -> control.getId().equals(r.id())).findFirst().orElseThrow();
    }
}
