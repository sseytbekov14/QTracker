package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The access rules of QTracker in one place, as pure functions: who sees a control, who may change it,
 * who performs a workflow step and who may be assigned. Callers describe the user ({@link Subject}) and
 * the control as that user stands on it ({@link ControlFacts}); nothing here reads the database.
 * <ul>
 *   <li>Level: SOQM creates and edits every control, assigns people and performs the SoQM steps (any SoQM
 *   user, assigned or not); PARTICIPANT performs the Facilitator, Control Operator and Process Owner steps
 *   and edits only where assigned; READ_ONLY never writes.</li>
 *   <li>Scope: OWN = assigned or shared; ALL = every control; KDN = KDN controls the user is assigned to
 *   or shared with. SoQM always sees every control.</li>
 *   <li>admin_access: Admin Panel, audit and viewing every control; it grants no edit, assignment,
 *   workflow step or creation.</li>
 *   <li>Shared With only views (spec 5.6): no edit, upload or workflow step, also on completed controls.</li>
 * </ul>
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    /** The user as the rules see them; a missing level or scope counts as the least access. */
    public record Subject(AccessLevel level, AccessScope scope, boolean admin, boolean enabled) {

        public Subject {
            level = level != null ? level : AccessLevel.READ_ONLY;
            scope = scope != null ? scope : AccessScope.OWN;
        }

        /** Null for no user. */
        public static Subject of(User user) {
            if (user == null) {
                return null;
            }
            return new Subject(user.getAccessLevel(), user.getAccessScope(),
                    Boolean.TRUE.equals(user.getAdminAccess()), Boolean.TRUE.equals(user.getEnabled()));
        }
    }

    /**
     * A control as one user stands on it: its workflow status, whether it is a KDN control, and in which
     * assignment fields the user is listed. A blank status is a draft, as everywhere else.
     */
    public record ControlFacts(String status,
                               boolean kdn,
                               boolean facilitator,
                               boolean controlOperator,
                               boolean soqmLead,
                               boolean processOwner,
                               boolean shared) {

        public ControlFacts {
            status = status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
        }

        /** Listed in one of the four workflow fields (Shared With is not an assignment). */
        public boolean assigned() {
            return facilitator || controlOperator || soqmLead || processOwner;
        }

        public boolean draft() {
            return "DRAFT".equals(status);
        }

        /** Only in Shared With. */
        public boolean sharedOnly() {
            return shared && !assigned();
        }
    }

    public enum ReadAccess {
        ALLOWED,
        DENIED,
        /** A draft the user is only shared with: it opens for them once it is initiated. */
        DRAFT_NOT_INITIATED
    }

    /** The assignment fields of a control, for the assignment check. */
    public enum Slot {
        FACILITATOR("Facilitator"),
        CONTROL_OPERATOR("Control Operator"),
        SOQM_LEAD("SoQM Team / Delegate"),
        PROCESS_OWNER("Process Owner"),
        SHARED_WITH("Control Shared With");

        private final String label;

        Slot(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** A KDN control: its Control ID starts with "KDN" (any case). */
    public static boolean isKdnControl(String controlId) {
        return controlId != null && controlId.trim().toUpperCase(Locale.ROOT).startsWith("KDN");
    }

    // ---------------------------------------------------------------- the user, without a control

    private static boolean active(Subject subject) {
        return subject != null && subject.enabled();
    }

    /** Whether the user may write anything at all: false for READ_ONLY (the general server check). */
    public static boolean mayWrite(Subject subject) {
        return active(subject) && subject.level() != AccessLevel.READ_ONLY;
    }

    public static boolean isSoqm(Subject subject) {
        return active(subject) && subject.level() == AccessLevel.SOQM;
    }

    /** Every control, drafts included: SoQM, admins and scope ALL. */
    public static boolean seesAllControls(Subject subject) {
        return active(subject)
                && (subject.level() == AccessLevel.SOQM || subject.admin() || subject.scope() == AccessScope.ALL);
    }

    public static boolean canOpenAdminPanel(Subject subject) {
        return active(subject) && subject.admin();
    }

    /** Changing users in the Admin Panel; a read-only admin only looks. */
    public static boolean canManageUsers(Subject subject) {
        return canOpenAdminPanel(subject) && mayWrite(subject);
    }

    public static boolean canCreateControls(Subject subject) {
        return isSoqm(subject);
    }

    /** The Excel export of every control (Controls page button and the server export). */
    public static boolean canExportAllControls(Subject subject) {
        return isSoqm(subject);
    }

    /** The organisation-wide dashboard charts; everyone else gets charts of their own controls. */
    public static boolean seesOrganisationCharts(Subject subject) {
        return isSoqm(subject);
    }

    /** The full user list (assignment pickers, Admin Panel). */
    public static boolean canListAllUsers(Subject subject) {
        return isSoqm(subject) || canOpenAdminPanel(subject);
    }

    // ---------------------------------------------------------------- one control

    /**
     * Whether the user sees the control, in lists and on its pages. This is also the one rule for drafts:
     * SoQM, admins and scope ALL see every draft (TODO: BUSINESS CONFIRMATION: whether READ_ONLY with scope
     * ALL should see drafts); scope OWN and KDN see a draft only when assigned or shared.
     */
    public static boolean canView(Subject subject, ControlFacts control) {
        if (!active(subject) || control == null) {
            return false;
        }
        return subject.level() == AccessLevel.SOQM || subject.admin() || inScope(subject, control);
    }

    /** The control is within the user's scope; the admin flag does not count here. */
    private static boolean inScope(Subject subject, ControlFacts control) {
        boolean own = control.assigned() || control.shared();
        return switch (subject.scope()) {
            case ALL -> true;
            case KDN -> control.kdn() && own;
            case OWN -> own;
        };
    }

    /**
     * Opening the control: as {@link #canView}, except that a draft stays closed to a user it is only
     * shared with (the "Not available yet" page) unless they see every control.
     */
    public static ReadAccess readAccess(Subject subject, ControlFacts control) {
        if (!canView(subject, control)) {
            return ReadAccess.DENIED;
        }
        if (control.draft() && control.sharedOnly() && !seesAllControls(subject)) {
            return ReadAccess.DRAFT_NOT_INITIATED;
        }
        return ReadAccess.ALLOWED;
    }

    /**
     * A participant acts in a field they are listed in, on a control within their scope (KDN scope: KDN
     * controls only); seeing it through the admin flag is not enough.
     */
    private static boolean actsAsParticipant(Subject subject, ControlFacts control, boolean listed) {
        return listed && active(subject) && subject.level() == AccessLevel.PARTICIPANT && inScope(subject, control);
    }

    /**
     * Fields a participant may change: Control Steps Performed while the step is theirs (Facilitator in
     * In Progress, Control Operator in Review), Process Owner Comments in Process Owner Review.
     */
    public static Set<String> participantFields(Subject subject, ControlFacts control) {
        Set<String> fields = new LinkedHashSet<>();
        if (control == null || !mayWrite(subject)) {
            return fields;
        }
        if (("IN_PROGRESS".equals(control.status()) && actsAsParticipant(subject, control, control.facilitator()))
                || ("REVIEW".equals(control.status()) && actsAsParticipant(subject, control, control.controlOperator()))) {
            fields.add(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }
        if ("PROCESS_OWNER_REVIEW".equals(control.status()) && actsAsParticipant(subject, control, control.processOwner())) {
            fields.add(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS);
        }
        return fields;
    }

    /** Everything the user may do on the control. */
    public static ControlPermission resolve(Subject subject, ControlFacts control) {
        boolean canView = canView(subject, control);
        if (!canView) {
            return ControlPermission.denied();
        }
        boolean writer = mayWrite(subject);
        boolean canEditAll = isSoqm(subject);
        Set<String> fields = participantFields(subject, control);
        return new ControlPermission(
                true,
                canEditAll || !fields.isEmpty(),
                fields,
                writer,
                canEditAll,
                control.shared(),
                actsAsParticipant(subject, control, control.facilitator()),
                actsAsParticipant(subject, control, control.controlOperator()),
                canEditAll,
                actsAsParticipant(subject, control, control.processOwner()));
    }

    /**
     * Whether a resolved permission covers the participant a workflow transition belongs to. The SoQM steps
     * and Initiate are any SoQM user's; Shared With performs no step.
     */
    public static boolean isActor(WorkflowTransition.Actor actor, ControlPermission permission) {
        if (actor == null || permission == null || !permission.canView() || !permission.canUseWorkflowActions()) {
            return false;
        }
        return switch (actor) {
            case FACILITATOR -> permission.isFacilitator();
            case CONTROL_OPERATOR -> permission.isControlOperator();
            case SOQM_TEAM, COORDINATOR -> permission.canEditAll();
            case PROCESS_OWNER -> permission.isProcessOwner();
            case SHARED_VIEWER -> false;
        };
    }

    /** "Your turn": the current step is in a field the user acts in. */
    public static boolean isMyTurn(Subject subject, ControlFacts control) {
        if (control == null || !mayWrite(subject) || !canView(subject, control)) {
            return false;
        }
        return switch (control.status()) {
            case "IN_PROGRESS" -> actsAsParticipant(subject, control, control.facilitator());
            case "REVIEW" -> actsAsParticipant(subject, control, control.controlOperator());
            case "SOQM_HEAD_REVIEW" -> control.soqmLead() && isSoqm(subject);
            case "PROCESS_OWNER_REVIEW" -> actsAsParticipant(subject, control, control.processOwner());
            default -> false;
        };
    }

    /** The one-control Excel export of a completed control: SoQM, or a user it is shared with. */
    public static boolean canExportCompletedControl(Subject subject, ControlFacts control) {
        return canView(subject, control) && (isSoqm(subject) || control.shared());
    }

    // ---------------------------------------------------------------- assignment

    /**
     * Why a person cannot be put in an assignment field, or empty when they can. Facilitator, Control
     * Operator and Process Owner take participants, SoQM Team / Delegate takes SoQM users, nobody read-only
     * is assigned (Shared With takes anyone), and a KDN-scope user goes only on KDN controls.
     * One person may hold several fields of the same control.
     *
     * @param candidate null when there is no QTracker user with that e-mail
     */
    public static Optional<String> assignmentRefusal(Subject candidate, Slot slot, boolean kdnControl) {
        if (candidate == null) {
            return Optional.of("is not a QTracker user");
        }
        if (candidate.scope() == AccessScope.KDN && !kdnControl) {
            return Optional.of("sees only KDN controls and cannot be added to this control");
        }
        return switch (slot) {
            case SHARED_WITH -> Optional.empty();
            case SOQM_LEAD -> candidate.level() == AccessLevel.SOQM
                    ? Optional.empty()
                    : Optional.of("is not a SoQM user");
            case FACILITATOR, CONTROL_OPERATOR, PROCESS_OWNER -> switch (candidate.level()) {
                case PARTICIPANT -> Optional.empty();
                case SOQM -> Optional.of("is a SoQM user; " + slot.getLabel() + " must be a participant");
                case READ_ONLY -> Optional.of("has read-only access and cannot be assigned");
            };
        };
    }

    /** Whether a user is offered in the picker of an assignment field (disabled users are not). */
    public static boolean isOfferedFor(Subject candidate, Slot slot, boolean kdnControl) {
        return candidate != null && candidate.enabled() && assignmentRefusal(candidate, slot, kdnControl).isEmpty();
    }
}
