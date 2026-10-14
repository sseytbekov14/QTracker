package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.ControlFrequency;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The next cycle of a Monthly or Quarterly control that SoQM Admin Team creates with Create Control on the Assignment
 * tab (business decision of 2026-10-14): its dates and what it takes from the source.
 */
public final class ControlNextCycle {

    /**
     * How often the business says the cycles come: every 30 days for Monthly and every 90 for Quarterly. The dates are
     * NOT counted with these numbers: the next Control Operation Date stays one calendar month (three for Quarterly)
     * after the operation date, as ControlScheduleCalculator counts it for every control (31.01 -> 28.02, not 02.03).
     * TODO: BUSINESS CONFIRMATION: a calendar month and quarter, or exactly 30 and 90 days.
     */
    public static final Map<ControlFrequency, Integer> BUSINESS_PERIOD_DAYS =
            Map.of(ControlFrequency.MONTHLY, 30, ControlFrequency.QUARTERLY, 90);

    /**
     * What the new cycle does NOT take from its source: these stay empty and start again. Every other field of the
     * Control, Details and Assignment tabs is copied as it is, the people included (Facilitator, Control Operator,
     * Process Owner, SoQM Team / Delegate, Shared With); the dates and the SoQM Year are worked out again
     * ({@link #dates}). The confirmation of Create Control lists these.
     */
    public static final List<String> NOT_COPIED = List.of(
            "Process Activities",
            ControlStepsFields.OPERATOR_PROGRAM_LABEL,
            ControlStepsFields.STEPS_LABEL,
            "SoQM Head/Team Comments",
            "Process Owner Comments",
            "Attachments (Details and Documents)",
            "History and Changelog",
            "Workflow statuses, return comments and dates",
            "Notifications");

    private ControlNextCycle() {
    }

    /**
     * The dates of the new cycle: its Control Operation Date is the source's Next Control Operation Date; the deadline
     * (10 working days), its own next date and the SoQM Year (the one the operation date falls in) are worked out
     * again by the rules every control follows.
     */
    public record Dates(LocalDate operationDate, LocalDate deadline, LocalDate nextOperationDate, String soqmYear) {
    }

    /** Null when the source has no Next Control Operation Date. */
    public static Dates dates(ControlScheduleCalculator calculator, ControlFrequency frequency, LocalDate sourceNext) {
        if (sourceNext == null || frequency == null) {
            return null;
        }
        return new Dates(sourceNext,
                calculator.calculateDeadline(frequency, sourceNext),
                calculator.calculateNextDate(frequency, sourceNext),
                SoqmYear.current(sourceNext));
    }

    /**
     * The control row of the new cycle, before it is initiated: the Control tab as the source has it, its own ID, the
     * deadline and SoQM Year of the new dates, created now by the person who pressed Create Control. Comments,
     * attachments and the workflow (status Draft until Initiate, no return comments, not reopened) start empty.
     */
    public static Control control(Control source, String newControlId, Dates dates, User creator, LocalDateTime now) {
        Control control = new Control();
        control.setControlId(newControlId);
        control.setControlFrequency(source.getControlFrequency());
        control.setControlCategory(source.getControlCategory());
        control.setControlType(source.getControlType());
        control.setComponent(source.getComponent());
        control.setOperatedBy(source.getOperatedBy());
        control.setReferencesToControl(source.getReferencesToControl());
        control.setPriority(source.getPriority());
        control.setNonAuditServicesApplicability(source.getNonAuditServicesApplicability());
        control.setHomogeneity(source.getHomogeneity());
        control.setControlStatus(source.getControlStatus());
        control.setControlDescription(source.getControlDescription());
        control.setPrp(source.getPrp());
        control.setSoqmYear(dates.soqmYear());
        control.setDeadline(dates.deadline());
        control.setPerformanceStatus("DRAFT");
        control.setCreatedBy(creator);
        if (creator != null) {
            control.setCreatedByEmail(creator.getMail());
            control.setCreatedByName(creator.getDisplayName());
        }
        control.setCreatedAt(now);
        control.setUpdatedAt(now);
        return control;
    }

    /** The Details tab of the new cycle: the description fields copied, what each cycle fills in left empty. */
    public static ControlDetails details(ControlDetails source, Long newId) {
        ControlDetails details = new ControlDetails();
        details.setControlId(newId);
        if (source != null) {
            details.setProcessName(source.getProcessName());
            details.setHomogeneity(source.getHomogeneity());
            details.setReferencesToControl(source.getReferencesToControl());
            details.setDepartment(source.getDepartment());
            details.setOtherRelatedControls(source.getOtherRelatedControls());
            details.setItApplications(source.getItApplications());
        }
        return details;
    }

    /**
     * The Assignment tab of the new cycle: the same people, Shared With as given (the source's, less anyone who can no
     * longer be put there), and the new dates.
     */
    public static ControlAssignment assignment(ControlAssignment source, Long newId, Dates dates, String sharedWith) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(newId);
        assignment.setFacilitator(source.getFacilitator());
        assignment.setControlOperator(source.getControlOperator());
        assignment.setSoqmLead(source.getSoqmLead());
        assignment.setProcessOwner(source.getProcessOwner());
        assignment.setControlSharedWith(sharedWith);
        assignment.setControlOperationDate(dates.operationDate());
        assignment.setControlOperationDeadline(dates.deadline());
        assignment.setNextControlOperationDate(dates.nextOperationDate());
        return assignment;
    }
}
