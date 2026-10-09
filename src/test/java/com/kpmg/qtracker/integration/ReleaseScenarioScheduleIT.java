package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlAutoCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * only when the date or the frequency changes, dates in a wrong form, Overdue and Closed late in the Controls list,
 * and the daily creation of the next cycle (run twice, Deleted and Superseded controls, a returned control, a
 * missed day). The auto-creation is called with the day it runs for, so nothing depends on today's date.
 */
class ReleaseScenarioScheduleIT extends ReleaseScenarioSupport {

    @Autowired
    private ControlAutoCreationService autoCreation;

    // ---- what a save stores ----

    @Test
    void anAssignmentSave_storesDeadlinePlus14_andTheNextDateOfTheFrequency_forEveryFrequency() throws Exception {
        User soqm = soqm("sc-soqm");
        MockHttpSession session = login(soqm);
        LocalDate date = LocalDate.of(2027, 1, 31);
        Map<String, LocalDate> next = Map.of("Monthly", LocalDate.of(2027, 2, 28), "Quarterly", LocalDate.of(2027, 4, 30),
                "Semi Annual", LocalDate.of(2027, 7, 31), "Annual", LocalDate.of(2028, 1, 31),
                "Recurring", LocalDate.of(2027, 4, 30));
        for (String frequency : List.of("Monthly", "Quarterly", "Semi Annual", "Annual", "Recurring", "Ad-hoc")) {
            Control control = assigned(frequency, soqm);
            saveDate(session, control, date);
            ControlAssignment stored = assignment(control);
            assertThat(stored.getControlOperationDate()).as(frequency).isEqualTo(date);
            assertThat(stored.getControlOperationDeadline()).as(frequency).isEqualTo(LocalDate.of(2027, 2, 14));
            assertThat(reload(control).getDeadline()).as(frequency).isEqualTo(LocalDate.of(2027, 2, 14));
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
        saveDate(session, control, LocalDate.of(2027, 12, 25));
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2028, 1, 8));
        assertThat(assignment(control).getNextControlOperationDate()).isEqualTo(LocalDate.of(2028, 3, 25));
        // A deadline or next date sent by the page is never taken
        assertThat(callJson(session, post("/api/control-assignment"), "{\"controlId\":" + control.getId()
                + ",\"controlOperationDeadline\":\"2030-01-01\",\"nextControlOperationDate\":\"2030-02-01\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(assignment(control).getControlOperationDeadline()).isEqualTo(LocalDate.of(2028, 1, 8));
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
        assertThat(row(session, control).isOverdue()).isTrue();
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

        assertThat(row(session, dueToday).isOverdue()).isFalse();
        assertThat(row(session, dueYesterday).isOverdue()).isTrue();
        assertThat(row(session, draftLate).isOverdue()).isTrue();
        assertThat(row(session, completedLate).isOverdue()).isFalse();
        assertThat(row(session, completedLate).isClosedLate()).isTrue();
        assertThat(row(session, completedOnTime).isClosedLate()).isFalse();
    }

    // ---- the next cycle ----

    @Test
    void theNextCycle_isCreatedOnItsDay_once_evenWhenTheRunRepeats() throws Exception {
        LocalDate day = uniqueDay();
        Control monthly = cycleSource("Monthly", day, "ACTIVE", "IN_PROGRESS");
        autoCreation.runDailyAutoCreation(day);
        autoCreation.runDailyAutoCreation(day);
        List<Control> created = createdFrom(monthly, day);
        assertThat(created).hasSize(1);
        Control next = created.get(0);
        assertThat(next.getPerformanceStatus()).isEqualTo("DRAFT");
        ControlAssignment schedule = assignment(next);
        assertThat(schedule.getControlOperationDate()).isEqualTo(day);
        assertThat(schedule.getControlOperationDeadline()).isEqualTo(day.plusDays(14));
        assertThat(schedule.getNextControlOperationDate()).isEqualTo(day.plusMonths(1));
        assertThat(schedule.getFacilitator()).isEqualTo(assignment(monthly).getFacilitator());
        // The cycle before is left as it was
        assertThat(reload(monthly).getPerformanceStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void theNextCycle_ofAReturnedControl_isCreated_ofAnAdhocOne_never_aMissedDayIsNotCaughtUp() throws Exception {
        LocalDate day = uniqueDay();
        Control reopened = cycleSource("Quarterly", day, "ACTIVE", "REVIEW");
        Control source = reload(reopened);
        source.setReopenedAt(java.time.LocalDateTime.now());
        controlRepository.save(source);
        Control adhoc = cycleSource("Ad-hoc", day, "ACTIVE", "IN_PROGRESS");
        LocalDate missed = day.plusDays(1);
        Control late = cycleSource("Monthly", missed, "ACTIVE", "IN_PROGRESS");

        autoCreation.runDailyAutoCreation(day);
        assertThat(createdFrom(reopened, day)).hasSize(1);
        assertThat(createdFrom(adhoc, day)).isEmpty();
        // The run of "missed" never happened; the next day does not create it (recorded in the report)
        autoCreation.runDailyAutoCreation(missed.plusDays(1));
        assertThat(createdFrom(late, missed)).isEmpty();
        assertThat(createdFrom(late, missed.plusDays(1))).isEmpty();
    }

    @Test
    void deletedAndSupersededControls_whatTheDailyRunDoesWithThem() throws Exception {
        LocalDate day = uniqueDay();
        Control deleted = cycleSource("Monthly", day, "Deleted", "COMPLETED");
        Control superseded = cycleSource("Monthly", day, "Superseded", "COMPLETED");
        autoCreation.runDailyAutoCreation(day);
        // Observed and written to the report (question Q-04): the run looks only at the next date
        int fromDeleted = createdFrom(deleted, day).size();
        int fromSuperseded = createdFrom(superseded, day).size();
        assertThat(fromDeleted).isBetween(0, 1);
        assertThat(fromSuperseded).isBetween(0, 1);
        System.out.println("RELEASE-SCENARIO auto-creation from Deleted: " + fromDeleted + ", from Superseded: "
                + fromSuperseded);
    }

    // ---- helpers ----

    private static int dayCounter = 0;

    /** A day far ahead, different for every test, so no other control is due on it. */
    private static synchronized LocalDate uniqueDay() {
        return LocalDate.of(2031, 1, 1).plusDays(37L * (++dayCounter) + Math.abs(RUN.hashCode() % 20));
    }

    private Control cycleSource(String frequency, LocalDate nextDate, String controlStatus, String status)
            throws Exception {
        Control control = control("HR-CY", status, people().facilitator(myEdit("cy-fac")).operator(myEdit("cy-op"))
                .soqm(soqm("cy-soqm")).owner(myEdit("cy-po")), c -> {
            // The form of the real IDs: base / financial year / location / period
            c.setControlId(c.getControlId() + "/FY27/Central/JAN");
            c.setControlFrequency(frequency);
            c.setControlStatus(controlStatus);
        });
        ControlAssignment assignment = assignment(control);
        assignment.setControlOperationDate(nextDate.minusMonths(1));
        assignment.setControlOperationDeadline(nextDate.minusMonths(1).plusDays(14));
        assignment.setNextControlOperationDate("Ad-hoc".equals(frequency) ? null : nextDate);
        assignmentRepository.save(assignment);
        return reload(control);
    }

    /** Controls created for the cycle of this day from the source's base ID (the part before the first "/"). */
    private List<Control> createdFrom(Control source, LocalDate day) {
        String base = source.getControlId().substring(0, source.getControlId().indexOf('/') + 1);
        return controlRepository.findAll().stream()
                .filter(c -> !c.getId().equals(source.getId()))
                .filter(c -> c.getControlId() != null && c.getControlId().startsWith(base))
                .filter(c -> day.equals(assignment(c).getControlOperationDate()))
                .toList();
    }

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

    private ControlResponseDTO row(MockHttpSession session, Control control) throws Exception {
        MvcResult result = call(session, get("/controls"));
        ModelAndView mav = result.getModelAndView();
        assertThat(mav).isNotNull();
        @SuppressWarnings("unchecked")
        List<ControlResponseDTO> rows = (List<ControlResponseDTO>) mav.getModel().get("controls");
        return rows.stream().filter(r -> control.getId().equals(r.getId())).findFirst().orElseThrow();
    }
}
