package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.util.EmailList;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The next cycle of a Monthly or Quarterly control that SoQM Admin Team creates with Create Control on View Control
 * (business decision of 2026-10-14): its dates and what it takes from the source.
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
            RelatedControls.LABEL,
            ItApplications.LABEL,
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
     * deadline and SoQM Year of the new dates, created now by the person who pressed Create Control, linked to the
     * source (createdFromControlId). Comments,
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
        control.setCreatedFromControlId(source.getId());
        return control;
    }

    /**
     * The Details tab of the new cycle: the description fields copied (Process Activities and Control Operator's
     * Program too, business decision of 2026-10-19), what each cycle fills in and Other Related Controls and IT
     * Applications (NOT_COPIED) left empty.
     */
    public static ControlDetails details(ControlDetails source, Long newId) {
        ControlDetails details = new ControlDetails();
        details.setControlId(newId);
        if (source != null) {
            details.setProcessName(source.getProcessName());
            details.setHomogeneity(source.getHomogeneity());
            details.setReferencesToControl(source.getReferencesToControl());
            details.setDepartment(source.getDepartment());
            details.setProcessActivities(source.getProcessActivities());
            details.setControlOperatorReview(source.getControlOperatorReview());
        }
        return details;
    }

    /** The four fields whose people the new cycle needs, in the order the business names them. */
    public static final List<AccessPolicy.Slot> REQUIRED_PEOPLE = List.of(AccessPolicy.Slot.FACILITATOR,
            AccessPolicy.Slot.CONTROL_OPERATOR, AccessPolicy.Slot.PROCESS_OWNER, AccessPolicy.Slot.SOQM_LEAD);

    /**
     * The people of the source as the new cycle would take them: what stops the creation (problems, to be fixed on the
     * Assignment tab of the source), Shared With as it is copied, and who is left out of it.
     *
     * @param problems         one line per empty field or person who cannot hold their field: disabled, deleted from
     *                         QTracker, not a QTracker user, or no longer allowed there (AccessPolicy.assignmentRefusal)
     * @param sharedWith       the source's Shared With less those who cannot be put there (a disabled person stays:
     *                         they see nothing while disabled, as on the source)
     * @param sharedWithLeftOut those left out, "e-mail (reason)"
     */
    public record People(List<String> problems, String sharedWith, List<String> sharedWithLeftOut) {
    }

    /**
     * @param users     the QTracker user of an address, if there is one
     * @param deleted   whether an address belongs to a person deleted from QTracker
     * @param kdnControl whether the new cycle is a KDN control (who may be put on it)
     */
    public static People people(ControlAssignment source, boolean kdnControl,
                                Function<String, Optional<User>> users, Predicate<String> deleted) {
        List<String> problems = new ArrayList<>();
        for (AccessPolicy.Slot slot : REQUIRED_PEOPLE) {
            List<String> mails = EmailList.parse(stored(source, slot));
            if (mails.isEmpty()) {
                problems.add(slot.getLabel() + ": nobody is assigned");
                continue;
            }
            for (String mail : mails) {
                Optional<User> user = users.apply(mail);
                String refusal;
                if (user.isEmpty()) {
                    refusal = deleted.test(mail) ? "was deleted from QTracker" : "is not a QTracker user";
                } else if (!Boolean.TRUE.equals(user.get().getEnabled())) {
                    refusal = "is disabled";
                } else {
                    refusal = AccessPolicy.assignmentRefusal(AccessPolicy.Subject.of(user.get()), slot, kdnControl)
                            .orElse(null);
                }
                if (refusal != null) {
                    problems.add(slot.getLabel() + ": " + mail + " " + refusal);
                }
            }
        }
        List<String> kept = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        for (String mail : EmailList.parse(source == null ? null : source.getControlSharedWith())) {
            Optional<User> user = users.apply(mail);
            Optional<String> refusal = user.isEmpty()
                    ? Optional.of(deleted.test(mail) ? "deleted from QTracker" : "not a QTracker user")
                    : AccessPolicy.assignmentRefusal(AccessPolicy.Subject.of(user.get()), AccessPolicy.Slot.SHARED_WITH,
                    kdnControl);
            if (refusal.isPresent()) {
                leftOut.add(mail + " (" + refusal.get() + ")");
            } else {
                kept.add(mail);
            }
        }
        return new People(List.copyOf(problems), String.join(",", kept), List.copyOf(leftOut));
    }

    private static String stored(ControlAssignment source, AccessPolicy.Slot slot) {
        if (source == null) {
            return null;
        }
        return switch (slot) {
            case FACILITATOR -> source.getFacilitator();
            case CONTROL_OPERATOR -> source.getControlOperator();
            case PROCESS_OWNER -> source.getProcessOwner();
            case SOQM_LEAD -> source.getSoqmLead();
            case SHARED_WITH -> source.getControlSharedWith();
        };
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
