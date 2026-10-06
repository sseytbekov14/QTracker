package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The access rules of QTracker in one place, as pure functions: who sees a control, who may change it,
 * who performs a workflow step and who may be assigned. Callers describe the user ({@link Subject}) and
 * the control as that user stands on it ({@link ControlFacts}); nothing here reads the database.
 * <ul>
 *   <li>Level: SOQM creates and edits every control, assigns people, performs the SoQM steps (any SoQM
 *   user, assigned or not) and moves a control on or back for any other role ({@link #move}); PARTICIPANT
 *   performs the Facilitator, Control Operator and Process Owner steps and edits only where assigned;
 *   READ_ONLY never writes.</li>
 *   <li>Scope: OWN = assigned or shared; ALL = every control; KDN = KDN controls the user is assigned to
 *   or shared with. SoQM always sees every control; KDN users are always READ_ONLY ({@link #levelScopeRefusal}).</li>
 *   <li>admin_access: Admin Panel, audit and viewing every control; it grants no edit, assignment,
 *   workflow step or creation.</li>
 *   <li>Shared With only views (spec 5.6): no edit, upload or workflow step, also on completed controls.</li>
 *   <li>A completed control is locked for everyone, SoQM included (spec 9.5, {@link #isLocked}): SoQM returns it
 *   to an earlier status first (business decision 4). Renaming its Control ID stays SoQM's.</li>
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
     * A control as one user stands on it: its workflow status, whether it is a KDN control, in which
     * assignment fields the user is listed, and whether Control Steps Performed is split in two because the
     * Facilitator and the Control Operator are different people ({@link ControlStepsFields}). A blank status
     * is a draft, as everywhere else.
     */
    public record ControlFacts(String status,
                               boolean kdn,
                               boolean facilitator,
                               boolean controlOperator,
                               boolean soqmLead,
                               boolean processOwner,
                               boolean shared,
                               boolean stepsSplit) {

        public ControlFacts {
            status = status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
        }

        /** Where the steps fields do not matter (visibility, "your turn"): one steps field. */
        public ControlFacts(String status, boolean kdn, boolean facilitator, boolean controlOperator,
                            boolean soqmLead, boolean processOwner, boolean shared) {
            this(status, kdn, facilitator, controlOperator, soqmLead, processOwner, shared, false);
        }

        /** Listed in one of the four workflow fields (Shared With is not an assignment). */
        public boolean assigned() {
            return facilitator || controlOperator || soqmLead || processOwner;
        }

        public boolean draft() {
            return "DRAFT".equals(status);
        }

        public boolean completed() {
            return "COMPLETED".equals(status);
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

        /** The field a picker asks for ("FACILITATOR", "SOQM_TEAM", "SHARED_WITH", ...). */
        public static Optional<Slot> forPicker(String key) {
            if (key == null) {
                return Optional.empty();
            }
            return switch (key.trim().toUpperCase(Locale.ROOT)) {
                case "FACILITATOR" -> Optional.of(FACILITATOR);
                case "CONTROL_OPERATOR" -> Optional.of(CONTROL_OPERATOR);
                case "SOQM_TEAM", "SOQM_LEAD" -> Optional.of(SOQM_LEAD);
                case "PROCESS_OWNER" -> Optional.of(PROCESS_OWNER);
                case "SHARED_WITH" -> Optional.of(SHARED_WITH);
                default -> Optional.empty();
            };
        }
    }

    /** A KDN control: its Control ID starts with "KDN-" (any case), as IDs of component KDN do. */
    public static boolean isKdnControl(String controlId) {
        return controlId != null && controlId.trim().toUpperCase(Locale.ROOT).startsWith("KDN-");
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

    /**
     * Why a level and a scope cannot go together, or empty when they can: SoQM sees every control (scope
     * ALL), and KDN users (staff of other countries) only watch their KDN controls, so scope KDN is READ_ONLY.
     */
    public static Optional<String> levelScopeRefusal(AccessLevel level, AccessScope scope) {
        if (level == AccessLevel.SOQM && scope != AccessScope.ALL) {
            return Optional.of("SoQM always sees all controls: scope must be ALL");
        }
        if (scope == AccessScope.KDN && level != AccessLevel.READ_ONLY) {
            return Optional.of("KDN users only view their KDN controls: level must be Read only");
        }
        return Optional.empty();
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
     * Fields a participant may change while the step is theirs: the Facilitator Control Steps Performed in
     * In Progress; the Control Operator in Review the same field, or, when the Facilitator and the Operator
     * are different people, only Control Operator Review and Results; the Process Owner Process Owner
     * Comments in Process Owner Review.
     */
    public static Set<String> participantFields(Subject subject, ControlFacts control) {
        Set<String> fields = new LinkedHashSet<>();
        if (control == null || !mayWrite(subject)) {
            return fields;
        }
        if ("IN_PROGRESS".equals(control.status()) && actsAsParticipant(subject, control, control.facilitator())) {
            fields.add(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }
        if ("REVIEW".equals(control.status()) && actsAsParticipant(subject, control, control.controlOperator())) {
            fields.add(control.stepsSplit()
                    ? ControlPermission.FIELD_CONTROL_OPERATOR_REVIEW
                    : ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }
        if ("PROCESS_OWNER_REVIEW".equals(control.status()) && actsAsParticipant(subject, control, control.processOwner())) {
            fields.add(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS);
        }
        return fields;
    }

    /**
     * The one rule for completed controls (spec 9.5, business decision 4): nobody edits one, SoQM included -
     * no field, assignment, document or attachment change. SoQM returns it to an earlier status first
     * ({@link #soqmTargets}); renaming its Control ID is not an edit of its content and stays allowed.
     */
    public static boolean isLocked(ControlFacts control) {
        return control != null && control.completed();
    }

    public static final String LOCKED_MESSAGE =
            "A completed control cannot be changed: SoQM returns it to an earlier step first";

    /** Everything the user may do on the control. */
    public static ControlPermission resolve(Subject subject, ControlFacts control) {
        boolean canView = canView(subject, control);
        if (!canView) {
            return ControlPermission.denied();
        }
        boolean writer = mayWrite(subject);
        boolean soqm = isSoqm(subject);
        boolean locked = isLocked(control);
        boolean canEditAll = soqm && !locked;
        Set<String> fields = locked ? Set.of() : participantFields(subject, control);
        return new ControlPermission(
                true,
                canEditAll || !fields.isEmpty(),
                fields,
                writer,
                canEditAll,
                control.shared(),
                actsAsParticipant(subject, control, control.facilitator()),
                actsAsParticipant(subject, control, control.controlOperator()),
                soqm,
                actsAsParticipant(subject, control, control.processOwner()),
                control.stepsSplit(),
                locked);
    }

    /** Renaming the Control ID: SoQM, on any control it sees, a completed one included (not an edit of it). */
    public static boolean canRenameId(ControlPermission permission) {
        return permission != null && permission.canView() && permission.canUseWorkflowActions()
                && permission.isSoqmLead();
    }

    /**
     * Whether a resolved permission covers the participant a workflow transition belongs to, as that
     * participant (SoQM acting for one is {@link #actsOnBehalf}). The SoQM steps and Initiate are any SoQM
     * user's; Shared With performs no step.
     */
    public static boolean isActor(WorkflowTransition.Actor actor, ControlPermission permission) {
        if (actor == null || permission == null || !permission.canView() || !permission.canUseWorkflowActions()) {
            return false;
        }
        return switch (actor) {
            case FACILITATOR -> permission.isFacilitator();
            case CONTROL_OPERATOR -> permission.isControlOperator();
            case SOQM_TEAM, COORDINATOR -> permission.isSoqmLead();
            case PROCESS_OWNER -> permission.isProcessOwner();
        };
    }

    // ---------------------------------------------------------------- moving a control on or back

    /** The working statuses in workflow order; a move to an earlier one is a return. Draft comes before them. */
    private static final List<String> WORKFLOW_ORDER =
            List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");

    /** The steps of the Facilitator, the Control Operator and the Process Owner. */
    private static final Set<WorkflowTransition.Actor> PARTICIPANT_STEPS = EnumSet.of(
            WorkflowTransition.Actor.FACILITATOR,
            WorkflowTransition.Actor.CONTROL_OPERATOR,
            WorkflowTransition.Actor.PROCESS_OWNER);

    /** Whether a status is one of the workflow's (Draft included). */
    public static boolean isWorkflowStatus(String status) {
        String normalized = normalizeStatus(status);
        return "DRAFT".equals(normalized) || WORKFLOW_ORDER.contains(normalized);
    }

    /** A move back to an earlier working status. */
    public static boolean isReturn(String from, String to) {
        int fromIndex = WORKFLOW_ORDER.indexOf(normalizeStatus(from));
        int toIndex = WORKFLOW_ORDER.indexOf(normalizeStatus(to));
        return toIndex >= 0 && fromIndex > toIndex;
    }

    /**
     * Whose step a control in this status is at: the role that normally moves it on (In Progress the
     * Facilitator, Review the Control Operator, SoQM review SoQM, Process Owner Review the Process Owner).
     * A completed control and a draft are SoQM's.
     */
    public static WorkflowTransition.Actor stepOwner(String status) {
        return switch (normalizeStatus(status)) {
            case "IN_PROGRESS" -> WorkflowTransition.Actor.FACILITATOR;
            case "REVIEW" -> WorkflowTransition.Actor.CONTROL_OPERATOR;
            case "PROCESS_OWNER_REVIEW" -> WorkflowTransition.Actor.PROCESS_OWNER;
            case "DRAFT" -> WorkflowTransition.Actor.COORDINATOR;
            default -> WorkflowTransition.Actor.SOQM_TEAM;
        };
    }

    /**
     * SoQM performs the step of a Facilitator, Control Operator or Process Owner in their place (business
     * decision 3: SoQM Head, Team and Delegate move a control on or back for any role). An admin without level
     * SOQM, a read-only user and Shared With do not.
     */
    public static boolean actsOnBehalf(WorkflowTransition.Actor actor, ControlPermission permission) {
        return actor != null && PARTICIPANT_STEPS.contains(actor)
                && permission != null && permission.canView() && permission.canUseWorkflowActions()
                && permission.isSoqmLead();
    }

    /**
     * Where SoQM may move a control in this status: on to the next status, or back to any earlier working
     * status (In Progress at the earliest; Draft is never a target). A draft moves on only through Initiate,
     * which has its own page; a completed control only goes back (business decision 4), with its values kept.
     */
    public static List<String> soqmTargets(String status) {
        String from = normalizeStatus(status);
        int index = WORKFLOW_ORDER.indexOf(from);
        if (index < 0) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        if (index + 1 < WORKFLOW_ORDER.size()) {
            targets.add(WORKFLOW_ORDER.get(index + 1));
        }
        targets.addAll(WORKFLOW_ORDER.subList(0, index));
        return targets;
    }

    /**
     * A return needs a comment (spec 9.4), and so does every move SoQM makes for a participant
     * (TODO: BUSINESS CONFIRMATION: the comment when SoQM acts for another role).
     */
    public static boolean moveNeedsComment(boolean isReturn, boolean onBehalf) {
        return isReturn || onBehalf;
    }

    /**
     * The move the user may make on the control from its status to {@code target}, or empty: a standard step
     * of their own (the Facilitator's submit, the Control Operator's return, ...), or as SoQM any step of
     * {@link #soqmTargets}, made for the participant whose step the control is at ({@link WorkflowMove#onBehalf}).
     */
    public static Optional<WorkflowMove> move(ControlPermission permission, String status, String target) {
        if (permission == null || !permission.canView() || !permission.canUseWorkflowActions()) {
            return Optional.empty();
        }
        String from = normalizeStatus(status);
        String to = normalizeStatus(target);
        Optional<WorkflowTransition> standard = standardTransition(from, to);
        if (standard.isPresent() && isActor(standard.get().getActor(), permission)) {
            return Optional.of(WorkflowMove.of(standard.get(), false));
        }
        if (permission.isSoqmLead() && soqmTargets(from).contains(to)) {
            WorkflowTransition.Actor owner = stepOwner(from);
            return Optional.of(new WorkflowMove(from, to, owner, PARTICIPANT_STEPS.contains(owner),
                    standard.orElse(null)));
        }
        return Optional.empty();
    }

    /** The named transition from one status to another (not Initiate, which has its own page). */
    private static Optional<WorkflowTransition> standardTransition(String from, String to) {
        for (WorkflowTransition transition : WorkflowTransition.values()) {
            if (transition != WorkflowTransition.INITIATE
                    && transition.getFromStatus().equals(from)
                    && transition.getTargetStatus().equals(to)) {
                return Optional.of(transition);
            }
        }
        return Optional.empty();
    }

    private static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
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
     * Operator and Process Owner take participants, SoQM Team / Delegate takes SoQM users, Shared With takes
     * anyone, and a KDN-scope user goes only on KDN controls. Read-only users are not assigned, except a KDN
     * user in the Facilitator, Control Operator or Process Owner field of a KDN control: there they see the
     * control and get its notifications, but perform no step (only participants act, {@link #resolve}), so
     * SoQM moves such a control on for them (TODO: BUSINESS CONFIRMATION: who performs the steps of KDN
     * controls, presumably SoQM Team). One person may hold several fields of the same control.
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
                case READ_ONLY -> candidate.scope() == AccessScope.KDN && kdnControl
                        ? Optional.empty()
                        : Optional.of("has read-only access and cannot be assigned");
            };
        };
    }

    /** Whether a user is offered in the picker of an assignment field (disabled users are not). */
    public static boolean isOfferedFor(Subject candidate, Slot slot, boolean kdnControl) {
        return candidate != null && candidate.enabled() && assignmentRefusal(candidate, slot, kdnControl).isEmpty();
    }
}
