package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;

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
 *   <li>Scope: OWN = assigned, shared or created; ALL = every control; KDN = every KDN control and no other
 *   ({@link #kdnSees}). SoQM always sees every control; KDN users are always READ_ONLY
 *   ({@link #levelScopeRefusal}).</li>
 *   <li>People see this as a role ({@link Profile}): SoQM Team = SOQM / ALL; User = Visibility (My controls =
 *   OWN, All controls = ALL) and Access (Edit = PARTICIPANT, Read Only = READ_ONLY), every combination
 *   allowed; KDN = READ_ONLY / KDN.</li>
 *   <li>The Admin Panel (users and audit) is SoQM Team's and only theirs ({@link #hasAdminAccess}); the
 *   stored admin_access flag follows the level and decides nothing.</li>
 *   <li>Shared With only views (spec 5.6): no edit, upload or workflow step, also on completed controls.</li>
 *   <li>A completed control is locked for everyone but SoQM Team ({@link #isLocked}), who change it in place with a
 *   reason ({@link #editsAfterCompletion}, business decision of 2026-10-09) or return it to an earlier status
 *   (business decision 4); its deadline and next date are always calculated ({@link #COMPLETED_FIXED_FIELDS}).
 *   Renaming its Control ID stays SoQM's.</li>
 * </ul>
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    /** The user as the rules see them; a missing level or scope counts as the least access. */
    public record Subject(AccessLevel level, AccessScope scope, boolean enabled) {

        public Subject {
            level = level != null ? level : AccessLevel.READ_ONLY;
            scope = scope != null ? scope : AccessScope.OWN;
        }

        /** Null for no user. */
        public static Subject of(User user) {
            if (user == null) {
                return null;
            }
            return new Subject(user.getAccessLevel(), user.getAccessScope(), Boolean.TRUE.equals(user.getEnabled()));
        }
    }

    /**
     * A user's access as people see and set it: the role, and for the role User their Visibility and Access
     * (null for SoQM Team and KDN). The one mapping between the roles and the stored level and scope.
     */
    public record Profile(UserRole role, Visibility visibility, AccessRight access) {

        public Profile {
            if (role == null) {
                throw new IllegalArgumentException("Role is required");
            }
            if (role != UserRole.USER) {
                visibility = null;
                access = null;
            } else {
                // A new User starts with the least access: My controls, Read Only
                visibility = visibility != null ? visibility : Visibility.MY;
                access = access != null ? access : AccessRight.READ_ONLY;
            }
        }

        public static Profile soqmTeam() {
            return new Profile(UserRole.SOQM_TEAM, null, null);
        }

        public static Profile kdn() {
            return new Profile(UserRole.KDN, null, null);
        }

        public static Profile user(Visibility visibility, AccessRight access) {
            return new Profile(UserRole.USER, visibility, access);
        }

        /** The profile of a stored level and scope; a missing one counts as the least access. */
        public static Profile of(AccessLevel level, AccessScope scope) {
            AccessLevel shownLevel = level != null ? level : AccessLevel.READ_ONLY;
            AccessScope shownScope = scope != null ? scope : AccessScope.OWN;
            if (shownLevel == AccessLevel.SOQM) {
                return soqmTeam();
            }
            if (shownScope == AccessScope.KDN) {
                return kdn();
            }
            return user(shownScope == AccessScope.ALL ? Visibility.ALL : Visibility.MY,
                    shownLevel == AccessLevel.PARTICIPANT ? AccessRight.EDIT : AccessRight.READ_ONLY);
        }

        public static Profile of(User user) {
            return user == null ? of((AccessLevel) null, null) : of(user.getAccessLevel(), user.getAccessScope());
        }

        public static Profile of(Subject subject) {
            return subject == null ? of((AccessLevel) null, null) : of(subject.level(), subject.scope());
        }

        public AccessLevel level() {
            return switch (role) {
                case SOQM_TEAM -> AccessLevel.SOQM;
                case KDN -> AccessLevel.READ_ONLY;
                case USER -> access == AccessRight.EDIT ? AccessLevel.PARTICIPANT : AccessLevel.READ_ONLY;
            };
        }

        public AccessScope scope() {
            return switch (role) {
                case SOQM_TEAM -> AccessScope.ALL;
                case KDN -> AccessScope.KDN;
                case USER -> visibility == Visibility.ALL ? AccessScope.ALL : AccessScope.OWN;
            };
        }

        /** The stored admin_access flag: SoQM Team, and only SoQM Team ({@link #hasAdminAccess}). */
        public boolean adminAccess() {
            return role == UserRole.SOQM_TEAM;
        }
    }

    /**
     * A control as one user stands on it: its workflow status, whether it is a KDN control, in which
     * assignment fields the user is listed, and whether the user created it (it sees the control, but acts only
     * where assigned). A blank status is a draft, as everywhere else.
     */
    public record ControlFacts(String status,
                               boolean kdn,
                               boolean facilitator,
                               boolean controlOperator,
                               boolean soqmLead,
                               boolean processOwner,
                               boolean shared,
                               boolean creator) {

        public ControlFacts {
            status = status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
        }

        /** Not the creator, or where that does not matter. */
        public ControlFacts(String status, boolean kdn, boolean facilitator, boolean controlOperator,
                            boolean soqmLead, boolean processOwner, boolean shared) {
            this(status, kdn, facilitator, controlOperator, soqmLead, processOwner, shared, false);
        }

        /** The same control with another KDN mark (its Control ID renamed). */
        public ControlFacts withKdn(boolean kdnControl) {
            return new ControlFacts(status, kdnControl, facilitator, controlOperator, soqmLead, processOwner, shared,
                    creator);
        }

        /** The same control with the user in Shared With or not (before it is saved). */
        public ControlFacts withShared(boolean inSharedWith) {
            return new ControlFacts(status, kdn, facilitator, controlOperator, soqmLead, processOwner, inSharedWith,
                    creator);
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

    /** The start of the Control ID that makes a KDN control. */
    public static final String KDN_PREFIX = "KDN";

    /**
     * A KDN control: its Control ID starts with "KDN" after trimming, in any case (KDN-001, KDN001, kdn-5);
     * "KDN" further on (X-KDN-12) does not count (business decision, 2026-10-07). SoQM Team gives such a
     * control its ID by hand. The only place this rule lives.
     */
    public static boolean isKdnControl(String controlId) {
        return controlId != null && controlId.trim().toUpperCase(Locale.ROOT).startsWith(KDN_PREFIX);
    }

    /**
     * Whether the user is shown which controls are KDN controls (the "KDN control" mark in lists and on the
     * control): SoQM Team, who give the IDs. KDN users see only KDN controls, the others need not know.
     */
    public static boolean seesKdnMark(Subject subject) {
        return isSoqm(subject);
    }

    /**
     * Other Related Controls and IT Applications on the Details tab (business decision of 2026-10-16): everyone who
     * sees the control sees them, only SoQM Admin Team change them (the Details save takes them with canEditAll only)
     * and only they get the list of controls to choose from.
     */
    public static boolean choosesRelatedControls(Subject subject) {
        return isSoqm(subject);
    }

    /** Renaming the Control ID from one to the other makes the control a KDN control or stops it being one. */
    public static boolean renameChangesKdn(String oldControlId, String newControlId) {
        return isKdnControl(oldControlId) != isKdnControl(newControlId);
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

    /** Every control, drafts included: SoQM Team and All controls. */
    public static boolean seesAllControls(Subject subject) {
        return active(subject) && (subject.level() == AccessLevel.SOQM || subject.scope() == AccessScope.ALL);
    }

    /**
     * Sees a whole set of controls whether or not they are on them: SoQM Team and All controls (every control)
     * and KDN (every KDN control). For them "Active" means not completed, not "my turn", and a draft they are
     * only shared with opens like any other.
     */
    public static boolean seesWithoutBeingOn(Subject subject) {
        return seesAllControls(subject) || (active(subject) && subject.scope() == AccessScope.KDN);
    }

    /**
     * The dashboard charts of a user without the organisation-wide ones cover every control they see: KDN, who
     * see every KDN control and are on few of them. Everyone else's charts cover the controls they are on.
     */
    public static boolean chartsCoverEveryVisibleControl(Subject subject) {
        return isKdnUser(subject);
    }

    /** The role KDN: watches every KDN control, Read Only, and has no workflow step and nothing to act on. */
    public static boolean isKdnUser(Subject subject) {
        return active(subject) && subject.scope() == AccessScope.KDN && !isSoqm(subject);
    }

    /** "Awaiting my action" and the other to-do blocks: everyone but KDN, who never act on a control. */
    public static boolean seesActionQueue(Subject subject) {
        return active(subject) && !isKdnUser(subject);
    }

    /**
     * The KDN page (/kdn) and its item in the menu (business decision of 2026-10-13, it took the place of the Action
     * Centre's "KDN controls" card): the role KDN, SoQM Admin Team and a User with All controls always, even before
     * there is a KDN control; a User with My controls only when some of the controls they see are KDN controls
     * ({@link #isKdnControl}). Which controls it lists is {@link #canView}'s.
     */
    public static boolean seesKdnPage(Subject subject, long visibleKdnControls) {
        return active(subject) && (visibleKdnControls > 0 || seesWithoutBeingOn(subject));
    }

    /**
     * Whether the role KDN has the Dashboard and the Action Centre (their pages and menu items). Off (business
     * decision of 2026-10-13): a KDN user starts on /kdn, the menu has only KDN ({@link #hasNotifications}), and the dashboard,
     * the Action Centre and the component lists lead to /kdn. Set to true to give the dashboard back.
     */
    public static final boolean KDN_SEES_DASHBOARD = false;

    /** The Dashboard, the Action Centre and the component lists: everyone but the role KDN ({@link #KDN_SEES_DASHBOARD}). */
    public static boolean seesDashboard(Subject subject) {
        return !isKdnUser(subject) || KDN_SEES_DASHBOARD;
    }

    /**
     * Whether the role KDN has notifications. Off (business decision of 2026-10-14): no Notifications item, bell or
     * counter in the menu, /notifications leads to /kdn, and no notification is made for a KDN user, in the
     * application or by e-mail, also where they are assigned to a KDN control. Set to true to give them back.
     */
    public static final boolean KDN_HAS_NOTIFICATIONS = false;

    /**
     * Notifications - receiving them in the application and by e-mail, the menu item and its counter, the pages:
     * everyone but the role KDN ({@link #KDN_HAS_NOTIFICATIONS}). A User with Edit assigned to a KDN control gets them
     * like on any other control. The one place this rule lives; null (no user) has none.
     */
    public static boolean hasNotifications(Subject subject) {
        return subject != null
                && (KDN_HAS_NOTIFICATIONS || subject.scope() != AccessScope.KDN || subject.level() == AccessLevel.SOQM);
    }

    /**
     * The Admin Panel, its users and the audit trail: every SoQM Team member and only them (the stored
     * admin_access flag decides nothing). The one place this rule lives.
     */
    public static boolean hasAdminAccess(Subject subject) {
        return isSoqm(subject);
    }

    public static boolean canOpenAdminPanel(Subject subject) {
        return hasAdminAccess(subject);
    }

    /** Changing users in the Admin Panel: whoever opens it. */
    public static boolean canManageUsers(Subject subject) {
        return hasAdminAccess(subject);
    }

    public static boolean canCreateControls(Subject subject) {
        return isSoqm(subject);
    }

    /**
     * The Excel export of a list (a component, All components, KDN): SoQM Admin Team only; the button is shown to
     * them alone and the server refuses everyone else (403). The Excel of one completed control is
     * {@link #canExportCompletedControl}.
     */
    public static boolean canExportControlLists(Subject subject) {
        return isSoqm(subject);
    }

    /** The organisation-wide dashboard charts; everyone else gets charts of their own controls. */
    public static boolean seesOrganisationCharts(Subject subject) {
        return isSoqm(subject);
    }

    /** The full user list (assignment pickers, Admin Panel). */
    public static boolean canListAllUsers(Subject subject) {
        return isSoqm(subject);
    }

    /**
     * Why a level and a scope cannot go together, or empty when they can: SoQM sees every control (scope
     * ALL), and KDN users (staff of other countries) only watch the KDN controls, so scope KDN is READ_ONLY.
     */
    public static Optional<String> levelScopeRefusal(AccessLevel level, AccessScope scope) {
        if (level == AccessLevel.SOQM && scope != AccessScope.ALL) {
            return Optional.of("SoQM Admin Team always sees all controls");
        }
        if (scope == AccessScope.KDN && level != AccessLevel.READ_ONLY) {
            return Optional.of("KDN access is always Read Only");
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- one control

    /**
     * Whether the user sees the control, in lists and on its pages. This is also the one rule for drafts:
     * SoQM Team and All controls see every draft, Read Only included (decision of 2026-10-07); My controls
     * see a draft only when assigned, shared or its creator; KDN as {@link #kdnSees}.
     */
    public static boolean canView(Subject subject, ControlFacts control) {
        if (!active(subject) || control == null) {
            return false;
        }
        return subject.level() == AccessLevel.SOQM || inScope(subject, control);
    }

    /**
     * The control is within the user's scope: assigned, shared or its creator (My controls), every control
     * (All controls), or every KDN control (KDN, {@link #kdnSees}).
     */
    private static boolean inScope(Subject subject, ControlFacts control) {
        return switch (subject.scope()) {
            case ALL -> true;
            case KDN -> kdnSees(control);
            case OWN -> control.assigned() || control.shared() || control.creator();
        };
    }

    /**
     * Whether KDN users see their drafts too: every KDN draft, like the other KDN controls (business decision of
     * 2026-10-07). Set to false to show KDN users a KDN control only once it is initiated.
     */
    static final boolean KDN_SEES_DRAFTS = true;

    /**
     * What a KDN user sees, the one place of this rule (business decision of 2026-10-07): every KDN control,
     * whoever is assigned to it, shared with it or created it, drafts as {@link #KDN_SEES_DRAFTS} says; never
     * another control. They only view it ({@link #levelScopeRefusal} keeps them Read Only).
     */
    static boolean kdnSees(ControlFacts control) {
        return control.kdn() && (KDN_SEES_DRAFTS || !control.draft());
    }

    /**
     * Opening the control: as {@link #canView}, except that a draft stays closed to a user it is only
     * shared with (the "Not available yet" page) unless they see it without being on it
     * ({@link #seesWithoutBeingOn}).
     */
    public static ReadAccess readAccess(Subject subject, ControlFacts control) {
        if (!canView(subject, control)) {
            return ReadAccess.DENIED;
        }
        if (control.draft() && control.sharedOnly() && !seesWithoutBeingOn(subject)) {
            return ReadAccess.DRAFT_NOT_INITIATED;
        }
        return ReadAccess.ALLOWED;
    }

    /**
     * A participant (User with Edit) acts in a field they are listed in, on a control within their scope:
     * seeing every control (All controls) gives no step and no edit where they are not assigned.
     */
    private static boolean actsAsParticipant(Subject subject, ControlFacts control, boolean listed) {
        return listed && active(subject) && subject.level() == AccessLevel.PARTICIPANT && inScope(subject, control);
    }

    /**
     * Fields a participant may change while the step is theirs: Control Steps Performed and Results, one field
     * for both steps as in the old system, by the Facilitator in In Progress and by the Control Operator in
     * Review; the Process Owner Process Owner Comments in Process Owner Review. Control Operator's Program is no
     * participant's field: SoQM Team writes it ({@link ControlPermission#canWriteOperatorReview}).
     */
    public static Set<String> participantFields(Subject subject, ControlFacts control) {
        Set<String> fields = new LinkedHashSet<>();
        if (control == null || !mayWrite(subject)) {
            return fields;
        }
        if ("IN_PROGRESS".equals(control.status()) && actsAsParticipant(subject, control, control.facilitator())) {
            fields.add(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }
        // TODO: BUSINESS CONFIRMATION: does the Control Operator add to the Facilitator's text or replace it?
        if ("REVIEW".equals(control.status()) && actsAsParticipant(subject, control, control.controlOperator())) {
            fields.add(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
        }
        if ("PROCESS_OWNER_REVIEW".equals(control.status()) && actsAsParticipant(subject, control, control.processOwner())) {
            fields.add(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS);
        }
        return fields;
    }

    /** A completed control: the status after Process Owner's Complete, until SoQM returns it. */
    public static boolean isCompleted(ControlFacts control) {
        return control != null && control.completed();
    }

    /**
     * The one rule for completed controls (spec 9.5, business decisions 4 and of 2026-10-09): everyone but SoQM
     * Team - User of any kind, KDN, Shared With, the people assigned to it - only reads one: no field, assignment,
     * document or attachment change and no step. SoQM Team changes it in place ({@link #editsAfterCompletion}) or
     * returns it to an earlier status ({@link #soqmTargets}); renaming its Control ID is not an edit of its content.
     * Every check of the lock - the save endpoints, /api/permissions, View Control - reads it from here,
     * through {@link ControlPermission#isLocked} and {@link ControlPermission#isCompletedEdit}.
     */
    public static boolean isLocked(Subject subject, ControlFacts control) {
        return isCompleted(control) && !isSoqm(subject);
    }

    /**
     * SoQM Admin Team changing a completed control in place, without returning it (business decisions of 2026-10-09
     * and 2026-10-14): every field of every tab - Control Status, SoQM Year, the Control Operation Date and the
     * frequency included -, Control Operator's Program, Control Steps Performed and Results, the attachments (upload,
     * and a delete that only hides the file, see FileAttachmentController) and the people, Shared With included; not
     * {@link #COMPLETED_FIXED_FIELDS}, which are calculated. Every change needs a reason, is audited before and after
     * and marked "Edited after completion" ({@link CompletedEdit}); nobody is notified
     * ({@link #notifiesOnCompletedEdit}). The workflow status, the completion and its date and reopened_at stay as
     * they are; "Closed late" follows the recalculated deadline.
     */
    public static boolean editsAfterCompletion(Subject subject, ControlFacts control) {
        return isCompleted(control) && isSoqm(subject) && !isLocked(subject, control);
    }

    public static final String LOCKED_MESSAGE =
            "This control is completed and locked: only SoQM Admin Team can change it";

    /**
     * What nobody changes on a completed control, SoQM Admin Team included (business decision of 2026-10-14): the
     * deadline and the next date. They are read only on the page and never taken from a request; the server
     * calculates them from the Control Operation Date and the frequency by the usual rules
     * (ControlScheduleCalculator) whenever one of those two changes, on a completed control as on any other, and
     * "Closed late" follows the new deadline. Such a change makes no new control and sends no e-mail by itself
     * (TODO: BUSINESS CONFIRMATION); Create Control later takes the new next date as for every control.
     * The Control ID changes only through Rename ID ({@link #canRenameId}) and the workflow status only through a
     * workflow move ({@link #soqmTargets}).
     */
    public static final List<String> COMPLETED_FIXED_FIELDS = List.of(
            "Control Operation Deadline", "Next Control Operation Date");

    /**
     * Whether saving a completed control in place tells anyone, a person newly in Shared With included: no
     * (business decision of 2026-10-14). The Changelog and the audit trail record the change.
     */
    public static boolean notifiesOnCompletedEdit() {
        return false;
    }

    /**
     * Whether a control of this frequency offers the Create Control button for its next cycle (business decision of
     * 2026-10-14): Monthly and Quarterly only, so the button goes when the frequency stops being one of them, a
     * completed control included. A frequency the application does not know offers none.
     */
    public static boolean frequencyOffersCreateControl(String frequency) {
        return ControlFrequency.tryFromValue(frequency)
                .map(value -> value == ControlFrequency.MONTHLY || value == ControlFrequency.QUARTERLY)
                .orElse(false);
    }

    /**
     * Create Control, the next cycle made by hand from View Control (business decision of 2026-10-14): SoQM Admin
     * Team, on a control they see. Everyone else - Users, Read Only, KDN - is refused (403) before the control itself is
     * looked at ({@link #nextCycleRefusal}, 400).
     */
    public static boolean canCreateNextCycle(ControlPermission permission) {
        return permission != null && permission.canView() && permission.canUseWorkflowActions()
                && permission.isSoqmLead();
    }

    public static final String NEXT_CYCLE_FREQUENCY = "Create Control is only for Monthly and Quarterly controls";
    public static final String NEXT_CYCLE_DRAFT = "This control is still a Draft: initiate it before creating its next control";

    /**
     * Why a control makes no next cycle with Create Control (400), empty when it does: Monthly and Quarterly only
     * ({@link #frequencyOffersCreateControl}); a draft is initiated first; a Deleted or Superseded control has no next
     * cycle. Any working status and Completed make one (TODO: BUSINESS CONFIRMATION:
     * from which statuses of the source).
     */
    public static Optional<String> nextCycleRefusal(String frequency, String performanceStatus, String controlStatus) {
        if (!frequencyOffersCreateControl(frequency)) {
            return Optional.of(NEXT_CYCLE_FREQUENCY);
        }
        if ("DRAFT".equals(normalizeStatus(performanceStatus))) {
            return Optional.of(NEXT_CYCLE_DRAFT);
        }
        if (ControlStatusValue.isRetired(controlStatus)) {
            return Optional.of("This control is " + ControlStatusValue.badge(controlStatus)
                    + ": it has no next control");
        }
        return Optional.empty();
    }

    /** Whether View Control shows Create Control (on every tab): the person may use it and the control makes a next cycle. */
    public static boolean offersCreateControl(ControlPermission permission, String frequency, String performanceStatus,
                                              String controlStatus) {
        return canCreateNextCycle(permission) && nextCycleRefusal(frequency, performanceStatus, controlStatus).isEmpty();
    }

    /** Everything the user may do on the control. */
    public static ControlPermission resolve(Subject subject, ControlFacts control) {
        boolean canView = canView(subject, control);
        if (!canView) {
            return ControlPermission.denied();
        }
        boolean writer = mayWrite(subject);
        boolean soqm = isSoqm(subject);
        boolean locked = isLocked(subject, control);
        boolean canEditAll = soqm && !locked;
        Set<String> fields = isCompleted(control) ? Set.of() : participantFields(subject, control);
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
                locked,
                editsAfterCompletion(subject, control));
    }

    /**
     * The calm notice on a control's page for someone who sees it but may not change it, so the page shows no
     * button the server would refuse: READ_ONLY for Read Only and KDN (no edit, no step anywhere), NOT_ASSIGNED
     * for a User with Edit who is in none of the Facilitator, Control Operator or Process Owner fields (seeing a
     * control - All controls, shared, creator - gives no edit and no step); COMPLETED for everyone but SoQM Team on
     * a completed control ({@link #isLocked}), whatever else they may do elsewhere. SoQM Team and assigned people:
     * NONE (SoQM Team on a completed control has its own banner, View Control's completedEdit).
     */
    public enum Notice { NONE, READ_ONLY, NOT_ASSIGNED, COMPLETED }

    public static Notice notice(Subject subject, ControlPermission permission) {
        if (permission == null || !permission.canView()) {
            return Notice.NONE;
        }
        if (permission.isLocked()) {
            return Notice.COMPLETED;
        }
        if (!mayWrite(subject) || !permission.canUseWorkflowActions()) {
            return Notice.READ_ONLY;
        }
        if (isSoqm(subject)) {
            return Notice.NONE;
        }
        boolean assigned = permission.isFacilitator() || permission.isControlOperator() || permission.isProcessOwner();
        return assigned ? Notice.NONE : Notice.NOT_ASSIGNED;
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
     * decision 3: SoQM Head, Team and Delegate move a control on or back for any role). A User, KDN and Shared With
     * do not.
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

    /**
     * The one-control Excel export of a completed control (Performance Cycle page): SoQM Admin Team only (decision of
     * 2026-10-14; a user the control is shared with no longer exports it). The completed status is checked where the
     * export is made.
     */
    public static boolean canExportCompletedControl(Subject subject, ControlFacts control) {
        return isSoqm(subject) && canView(subject, control);
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
                    : Optional.of("is not SoQM Admin Team");
            case FACILITATOR, CONTROL_OPERATOR, PROCESS_OWNER -> switch (candidate.level()) {
                case PARTICIPANT -> Optional.empty();
                case SOQM -> Optional.of("is SoQM Admin Team; " + slot.getLabel() + " takes a User with Edit access");
                case READ_ONLY -> candidate.scope() == AccessScope.KDN && kdnControl
                        ? Optional.empty()
                        : Optional.of("has Read Only access and cannot be assigned");
            };
        };
    }

    /**
     * As {@link #assignmentRefusal(Subject, Slot, boolean)}, for a save of a control's people: a person deleted from
     * QTracker (business decision of 2026-10-13) stays where they were until SoQM Admin Team changes it, so a save
     * that keeps them in the same field is not refused; putting them anywhere new is ("is not a QTracker user").
     */
    public static Optional<String> assignmentRefusal(Subject candidate, Slot slot, boolean kdnControl,
                                                     boolean deletedAndAlreadyThere) {
        if (candidate == null && deletedAndAlreadyThere) {
            return Optional.empty();
        }
        return assignmentRefusal(candidate, slot, kdnControl);
    }

    /**
     * Deleting a user from the Admin Panel (business decision of 2026-10-13): SoQM Admin Team only, never oneself
     * ({@link #DELETE_SELF}) and never the last active SoQM Admin Team member ({@link #DELETE_LAST_SOQM}); a disabled
     * user may be deleted. The rest (the two confirmations, what is kept) is UserDeletion's.
     *
     * @param activeSoqmLeft active SoQM Admin Team members, the target included
     */
    public static Optional<String> userDeletionRefusal(Subject actor, Long actorId, Subject target, Long targetId,
                                                       long activeSoqmLeft) {
        if (!isSoqm(actor)) {
            return Optional.of(DELETE_NOT_ALLOWED);
        }
        if (actorId != null && actorId.equals(targetId)) {
            return Optional.of(DELETE_SELF);
        }
        if (target != null && target.enabled() && target.level() == AccessLevel.SOQM && activeSoqmLeft <= 1) {
            return Optional.of(DELETE_LAST_SOQM);
        }
        return Optional.empty();
    }

    public static final String DELETE_NOT_ALLOWED =
            "Only " + com.kpmg.qtracker.enums.UserRole.SOQM_TEAM_NAME + " can delete users";
    public static final String DELETE_SELF = "You cannot delete your own account";
    public static final String DELETE_LAST_SOQM = "This is the last active "
            + com.kpmg.qtracker.enums.UserRole.SOQM_TEAM_NAME + " member: they cannot be deleted";

    /** Whether a user is offered in the picker of an assignment field (disabled users are not). */
    public static boolean isOfferedFor(Subject candidate, Slot slot, boolean kdnControl) {
        return candidate != null && candidate.enabled() && assignmentRefusal(candidate, slot, kdnControl).isEmpty();
    }

    // ---------------------------------------------------------------- Shared With

    /**
     * What a place in Shared With gives a person on one control, as {@link #readAccess} decides it: VIEWS - they
     * open it and download its files, and edit nothing for being there (spec 5.6); AFTER_INITIATION - a draft they
     * are only shared with stays closed until it is initiated; NOT_SEEN - they never see it (a KDN user on a
     * control that is not a KDN control, which {@link #assignmentRefusal} also refuses to save); DISABLED - a
     * disabled user sees nothing; NOT_A_USER - an address without a QTracker user, which cannot be saved.
     */
    public enum SharedAccess { VIEWS, AFTER_INITIATION, NOT_SEEN, DISABLED, NOT_A_USER }

    /**
     * @param candidate null when there is no QTracker user with that address
     * @param control   how the person stands on the control, whether or not Shared With already holds them
     */
    public static SharedAccess sharedAccess(Subject candidate, ControlFacts control) {
        if (candidate == null || control == null) {
            return SharedAccess.NOT_A_USER;
        }
        if (!candidate.enabled()) {
            return SharedAccess.DISABLED;
        }
        return switch (readAccess(candidate, control.withShared(true))) {
            case ALLOWED -> SharedAccess.VIEWS;
            case DRAFT_NOT_INITIATED -> SharedAccess.AFTER_INITIATION;
            case DENIED -> SharedAccess.NOT_SEEN;
        };
    }

    /**
     * Who is told on View Control what Shared With gives each person (Disabled, Not in the system, ...): SoQM
     * Team, who change the list. Everyone else sees the names.
     */
    public static boolean seesSharedWithNotes(Subject subject) {
        return isSoqm(subject);
    }
}
