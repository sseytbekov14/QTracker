package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessRight;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.enums.UserRole;
import com.kpmg.qtracker.enums.Visibility;
import com.kpmg.qtracker.service.AccessPolicy.ControlFacts;
import com.kpmg.qtracker.service.AccessPolicy.ReadAccess;
import com.kpmg.qtracker.service.AccessPolicy.Slot;
import com.kpmg.qtracker.service.AccessPolicy.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access rules as a matrix: kinds of users x control statuses x operations. Users are named by the
 * keys of {@link #SUBJECTS}; a control by its status, whether it is a KDN control and the user's places
 * on it (F, CO, SOQM, PO, SHARED, joined with "+", "-" for none).
 */
class AccessPolicyTest {

    private static final Map<String, Subject> SUBJECTS = Map.ofEntries(
            Map.entry("SOQM", subject(AccessLevel.SOQM, AccessScope.ALL, true)),
            Map.entry("PART", subject(AccessLevel.PARTICIPANT, AccessScope.OWN, true)),
            Map.entry("PART_ALL", subject(AccessLevel.PARTICIPANT, AccessScope.ALL, true)),
            Map.entry("KDN", subject(AccessLevel.READ_ONLY, AccessScope.KDN, true)),
            Map.entry("RO", subject(AccessLevel.READ_ONLY, AccessScope.OWN, true)),
            Map.entry("RO_ALL", subject(AccessLevel.READ_ONLY, AccessScope.ALL, true)),
            Map.entry("DISABLED_SOQM", subject(AccessLevel.SOQM, AccessScope.ALL, false)),
            Map.entry("DISABLED_PART", subject(AccessLevel.PARTICIPANT, AccessScope.OWN, false)));

    private static Subject subject(AccessLevel level, AccessScope scope, boolean enabled) {
        return new Subject(level, scope, enabled);
    }

    private static Subject who(String key) {
        return "NONE".equals(key) ? null : SUBJECTS.get(key);
    }

    /** "CREATOR" among the places: the user created the control. */
    private static ControlFacts control(String status, boolean kdn, String places) {
        Set<String> p = "-".equals(places) ? Set.of() : Arrays.stream(places.split("\\+")).collect(Collectors.toSet());
        return new ControlFacts(status, kdn, p.contains("F"), p.contains("CO"), p.contains("SOQM"),
                p.contains("PO"), p.contains("SHARED"), p.contains("CREATOR"));
    }

    // ------------------------------------------------------------------ the user alone

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // user,          write, soqm,  all,   adminPanel, manageUsers, create, exportAll, allUsers
            "SOQM,            true,  true,  true,  true,       true,        true,   true,      true",
            "PART,            true,  false, false, false,      false,       false,  false,     false",
            "PART_ALL,        true,  false, true,  false,      false,       false,  false,     false",
            "KDN,             false, false, false, false,      false,       false,  false,     false",
            "RO,              false, false, false, false,      false,       false,  false,     false",
            "RO_ALL,          false, false, true,  false,      false,       false,  false,     false",
            "DISABLED_SOQM,   false, false, false, false,      false,       false,  false,     false",
            "NONE,            false, false, false, false,      false,       false,  false,     false",
    })
    void userLevelRules(String user, boolean write, boolean soqm, boolean all, boolean adminPanel,
                        boolean manageUsers, boolean create, boolean exportAll, boolean allUsers) {
        Subject s = who(user);
        assertThat(AccessPolicy.mayWrite(s)).as("mayWrite").isEqualTo(write);
        assertThat(AccessPolicy.isSoqm(s)).as("isSoqm").isEqualTo(soqm);
        assertThat(AccessPolicy.seesAllControls(s)).as("seesAllControls").isEqualTo(all);
        assertThat(AccessPolicy.canOpenAdminPanel(s)).as("canOpenAdminPanel").isEqualTo(adminPanel);
        assertThat(AccessPolicy.canManageUsers(s)).as("canManageUsers").isEqualTo(manageUsers);
        assertThat(AccessPolicy.canCreateControls(s)).as("canCreateControls").isEqualTo(create);
        assertThat(AccessPolicy.canExportAllControls(s)).as("canExportAllControls").isEqualTo(exportAll);
        assertThat(AccessPolicy.canListAllUsers(s)).as("canListAllUsers").isEqualTo(allUsers);
    }

    // ------------------------------------------------------------------ seeing and opening a control

    @ParameterizedTest(name = "{0} {1} kdn={2} {3}")
    @CsvSource({
            // user,       status,               kdn,   places,  view,  read
            "SOQM,         DRAFT,                false, -,       true,  ALLOWED",
            "SOQM,         IN_PROGRESS,          true,  -,       true,  ALLOWED",
            "PART,         IN_PROGRESS,          false, -,       false, DENIED",
            "PART,         DRAFT,                false, F,       true,  ALLOWED",
            "PART,         DRAFT,                false, PO,      true,  ALLOWED",
            "PART,         DRAFT,                false, SHARED,  true,  DRAFT_NOT_INITIATED",
            "PART,         DRAFT,                false, F+SHARED, true, ALLOWED",
            "PART,         IN_PROGRESS,          false, SHARED,  true,  ALLOWED",
            "PART,         COMPLETED,            false, SHARED,  true,  ALLOWED",
            "PART,         IN_PROGRESS,          true,  -,       false, DENIED",
            "PART,         IN_PROGRESS,          true,  CO,      true,  ALLOWED",
            "PART_ALL,     DRAFT,                false, -,       true,  ALLOWED",
            "PART_ALL,     DRAFT,                false, SHARED,  true,  ALLOWED",
            "PART_ALL,     COMPLETED,            true,  -,       true,  ALLOWED",
            // KDN sees every KDN control, drafts included, on it or not; never another control
            "KDN,          DRAFT,                true,  F,       true,  ALLOWED",
            "KDN,          DRAFT,                true,  SHARED,  true,  ALLOWED",
            "KDN,          DRAFT,                true,  -,       true,  ALLOWED",
            "KDN,          REVIEW,               true,  SHARED,  true,  ALLOWED",
            "KDN,          REVIEW,               true,  -,       true,  ALLOWED",
            "KDN,          COMPLETED,            true,  -,       true,  ALLOWED",
            "KDN,          REVIEW,               false, CO,      false, DENIED",
            "KDN,          COMPLETED,            false, SHARED,  false, DENIED",
            "KDN,          DRAFT,                false, F,       false, DENIED",
            "KDN,          REVIEW,               true,  CREATOR, true,  ALLOWED",
            "KDN,          REVIEW,               false, CREATOR, false, DENIED",
            "RO,           REVIEW,               false, CREATOR, true,  ALLOWED",
            "PART,         REVIEW,               false, CREATOR, true,  ALLOWED",
            "RO,           COMPLETED,            false, SHARED,  true,  ALLOWED",
            "RO,           DRAFT,                false, SHARED,  true,  DRAFT_NOT_INITIATED",
            "RO,           COMPLETED,            false, -,       false, DENIED",
            "RO,           IN_PROGRESS,          false, F,       true,  ALLOWED",
            "RO_ALL,       DRAFT,                false, -,       true,  ALLOWED",
            "RO_ALL,       PROCESS_OWNER_REVIEW, true,  -,       true,  ALLOWED",
            "DISABLED_SOQM, IN_PROGRESS,         false, -,       false, DENIED",
            "DISABLED_PART, IN_PROGRESS,         false, F,       false, DENIED",
            "NONE,         COMPLETED,            false, -,       false, DENIED",
    })
    void viewAndRead(String user, String status, boolean kdn, String places, boolean view, ReadAccess read) {
        Subject s = who(user);
        ControlFacts c = control(status, kdn, places);
        assertThat(AccessPolicy.canView(s, c)).as("canView").isEqualTo(view);
        assertThat(AccessPolicy.readAccess(s, c)).as("readAccess").isEqualTo(read);
        assertThat(AccessPolicy.resolve(s, c).canView()).as("resolve.canView").isEqualTo(view);
    }

    @Test
    void blankStatus_isADraft() {
        ControlFacts c = new ControlFacts("  ", false, false, false, false, false, true);
        assertThat(c.draft()).isTrue();
        assertThat(AccessPolicy.readAccess(who("PART"), c)).isEqualTo(ReadAccess.DRAFT_NOT_INITIATED);
    }

    // ------------------------------------------------------------------ editing

    @ParameterizedTest(name = "{0} {1} kdn={2} {3}")
    @CsvSource({
            // user,       status,               kdn,   places,     edit,  editAll, fields
            "SOQM,         DRAFT,                false, -,          true,  true,    -",
            // A completed control is locked for everyone, SoQM included (decision 4)
            "SOQM,         COMPLETED,            false, -,          false, false,   -",
            "SOQM,         IN_PROGRESS,          true,  -,          true,  true,    -",
            "PART,         IN_PROGRESS,          false, F,          true,  false,   controlStepsPerformed",
            "PART,         REVIEW,               false, F,          false, false,   -",
            "PART,         DRAFT,                false, F,          false, false,   -",
            // Control Steps Performed and Results: the Facilitator's in In Progress, the Control Operator's in
            // Review, whether or not they are also a Facilitator
            "PART,         REVIEW,               false, CO,         true,  false,   controlStepsPerformed+controlOperatorReview",
            "PART,         REVIEW,               false, F+CO,       true,  false,   controlStepsPerformed+controlOperatorReview",
            "PART,         IN_PROGRESS,          false, F+CO,       true,  false,   controlStepsPerformed",
            "KDN,          REVIEW,               true,  CO,         false, false,   -",
            "RO,           REVIEW,               false, CO,         false, false,   -",
            "PART,         REVIEW,               false, SHARED,     false, false,   -",
            "PART,         SOQM_HEAD_REVIEW,     false, CO+SOQM,    false, false,   -",
            "PART,         PROCESS_OWNER_REVIEW, false, PO,         true,  false,   processOwnerComments",
            "PART,         COMPLETED,            false, F+CO+PO,    false, false,   -",
            "PART,         COMPLETED,            false, SHARED,     false, false,   -",
            "PART,         IN_PROGRESS,          false, SHARED,     false, false,   -",
            "PART_ALL,     IN_PROGRESS,          false, -,          false, false,   -",
            "PART_ALL,     IN_PROGRESS,          false, F,          true,  false,   controlStepsPerformed",
            "KDN,          IN_PROGRESS,          true,  F,          false, false,   -",
            "KDN,          IN_PROGRESS,          false, F,          false, false,   -",
            "RO,           IN_PROGRESS,          false, F,          false, false,   -",
            "RO,           PROCESS_OWNER_REVIEW, false, PO+SHARED,  false, false,   -",
            "RO_ALL,       REVIEW,               false, -,          false, false,   -",
            "DISABLED_SOQM, REVIEW,              false, -,          false, false,   -",
            "NONE,         REVIEW,               false, -,          false, false,   -",
    })
    void editing(String user, String status, boolean kdn, String places,
                 boolean edit, boolean editAll, String fields) {
        ControlPermission p = AccessPolicy.resolve(who(user), control(status, kdn, places));
        assertThat(p.canEdit()).as("canEdit").isEqualTo(edit);
        assertThat(p.canEditAll()).as("canEditAll").isEqualTo(editAll);
        assertThat(p.getAllowedEditableFields())
                .as("fields")
                .isEqualTo("-".equals(fields) ? Set.of() : Set.of(fields.split("\\+")));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            // user,     status,      places,       steps field, Control Operator's Program
            "SOQM,       REVIEW,      -,            true,  true",
            "SOQM,       COMPLETED,   -,            false, false",
            "RO_ALL,     REVIEW,      -,            false, false",
            "PART,       IN_PROGRESS, F,            true,  false",
            "PART,       REVIEW,      CO,           true,  true",
            "PART,       REVIEW,      F+CO,         true,  true",
            "PART,       IN_PROGRESS, F+CO,         true,  false",
            "PART,       REVIEW,      F,            false, false",
            "PART,       REVIEW,      SHARED,       false, false",
            "KDN,        REVIEW,      CO,           false, false",
            "RO,         REVIEW,      CO,           false, false",
    })
    void whoWritesEachStepsField(String user, String status, String places, boolean steps, boolean review) {
        ControlPermission p = AccessPolicy.resolve(who(user), control(status, false, places));
        assertThat(p.canWriteStepsPerformed()).as("Control Steps Performed").isEqualTo(steps);
        assertThat(p.canWriteOperatorReview()).as("Control Operator's Program").isEqualTo(review);
    }

    // ------------------------------------------------------------------ workflow steps

    @ParameterizedTest(name = "{0} {1} kdn={2} {3} {4}")
    @CsvSource({
            // user,       status,               kdn,   places,   transition,                    allowed
            "SOQM,         DRAFT,                false, -,        INITIATE,                      true",
            "SOQM,         SOQM_HEAD_REVIEW,     false, -,        SUBMIT_TO_PROCESS_OWNER,       true",
            "SOQM,         SOQM_HEAD_REVIEW,     false, SOQM,     RETURN_TO_OPERATOR,            true",
            "SOQM,         IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "SOQM,         PROCESS_OWNER_REVIEW, false, -,        COMPLETE,                      false",
            "PART,         DRAFT,                false, F,        INITIATE,                      false",
            "PART,         IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    true",
            "PART,         IN_PROGRESS,          false, CO,       SUBMIT_TO_CONTROL_OPERATOR,    false",
            "PART,         REVIEW,               false, CO,       RETURN_TO_FACILITATOR,         true",
            "PART,         REVIEW,               false, CO,       SUBMIT_TO_SOQM_TEAM,           true",
            "PART,         REVIEW,               false, F+CO,     SUBMIT_TO_SOQM_TEAM,           true",
            "PART,         SOQM_HEAD_REVIEW,     false, SOQM,     SUBMIT_TO_PROCESS_OWNER,       false",
            "PART,         PROCESS_OWNER_REVIEW, false, PO,       COMPLETE,                      true",
            "PART,         PROCESS_OWNER_REVIEW, false, PO,       OWNER_RETURN_TO_OPERATOR,      true",
            "PART,         PROCESS_OWNER_REVIEW, false, CO,       COMPLETE,                      false",
            "PART_ALL,     IN_PROGRESS,          false, -,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "PART_ALL,     IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    true",
            "KDN,          IN_PROGRESS,          true,  F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "KDN,          PROCESS_OWNER_REVIEW, true,  PO,       COMPLETE,                      false",
            "KDN,          IN_PROGRESS,          true,  SHARED,   SUBMIT_TO_CONTROL_OPERATOR,    false",
            "KDN,          IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "RO,           IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "RO,           PROCESS_OWNER_REVIEW, false, PO,       COMPLETE,                      false",
            "RO_ALL,       DRAFT,                false, -,        INITIATE,                      false",
            "DISABLED_SOQM, DRAFT,               false, -,        INITIATE,                      false",
            "DISABLED_PART, IN_PROGRESS,         false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "NONE,         IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
    })
    void workflowActor(String user, String status, boolean kdn, String places,
                       WorkflowTransition transition, boolean allowed) {
        ControlPermission p = AccessPolicy.resolve(who(user), control(status, kdn, places));
        assertThat(AccessPolicy.isActor(transition.getActor(), p)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            // user,     status,               target,               places, allowed, onBehalf, comment
            "SOQM,       IN_PROGRESS,          REVIEW,               -,      true,    true,     true",
            "SOQM,       REVIEW,               SOQM_HEAD_REVIEW,     -,      true,    true,     true",
            "SOQM,       REVIEW,               IN_PROGRESS,          -,      true,    true,     true",
            "SOQM,       SOQM_HEAD_REVIEW,     PROCESS_OWNER_REVIEW, -,      true,    false,    false",
            "SOQM,       SOQM_HEAD_REVIEW,     REVIEW,               -,      true,    false,    true",
            "SOQM,       SOQM_HEAD_REVIEW,     IN_PROGRESS,          -,      true,    false,    true",
            "SOQM,       PROCESS_OWNER_REVIEW, COMPLETED,            -,      true,    true,     true",
            "SOQM,       PROCESS_OWNER_REVIEW, SOQM_HEAD_REVIEW,     -,      true,    true,     true",
            "SOQM,       PROCESS_OWNER_REVIEW, IN_PROGRESS,          -,      true,    true,     true",
            "SOQM,       IN_PROGRESS,          SOQM_HEAD_REVIEW,     -,      false,   false,    false",
            "SOQM,       IN_PROGRESS,          DRAFT,                -,      false,   false,    false",
            "SOQM,       DRAFT,                IN_PROGRESS,          -,      false,   false,    false",
            // A completed control only goes back, SoQM's own step: comment, not on behalf (decision 4)
            "SOQM,       COMPLETED,            PROCESS_OWNER_REVIEW, -,      true,    false,    true",
            "SOQM,       COMPLETED,            SOQM_HEAD_REVIEW,     -,      true,    false,    true",
            "SOQM,       COMPLETED,            REVIEW,               -,      true,    false,    true",
            "SOQM,       COMPLETED,            IN_PROGRESS,          -,      true,    false,    true",
            "SOQM,       COMPLETED,            DRAFT,                -,      false,   false,    false",
            "PART,       COMPLETED,            PROCESS_OWNER_REVIEW, PO,     false,   false,    false",
            "RO_ALL,     COMPLETED,            IN_PROGRESS,          -,      false,   false,    false",
            "PART,       IN_PROGRESS,          REVIEW,               F,      true,    false,    false",
            "PART,       REVIEW,               IN_PROGRESS,          CO,     true,    false,    true",
            "PART,       PROCESS_OWNER_REVIEW, REVIEW,               PO,     true,    false,    true",
            "PART,       PROCESS_OWNER_REVIEW, IN_PROGRESS,          PO,     false,   false,    false",
            "PART,       REVIEW,               SOQM_HEAD_REVIEW,     F,      false,   false,    false",
            "RO_ALL,     IN_PROGRESS,          REVIEW,               -,      false,   false,    false",
            "KDN,        IN_PROGRESS,          REVIEW,               F,      false,   false,    false",
            "DISABLED_SOQM, IN_PROGRESS,       REVIEW,               -,      false,   false,    false",
    })
    void moves(String user, String status, String target, String places,
               boolean allowed, boolean onBehalf, boolean comment) {
        ControlPermission p = AccessPolicy.resolve(who(user), control(status, places.equals("KDN") || user.equals("KDN"), places));
        var move = AccessPolicy.move(p, status, target);
        assertThat(move.isPresent()).as("allowed").isEqualTo(allowed);
        if (allowed) {
            assertThat(move.get().onBehalf()).as("onBehalf").isEqualTo(onBehalf);
            assertThat(move.get().commentRequired()).as("comment").isEqualTo(comment);
            assertThat(move.get().actingFor()).as("actingFor").isEqualTo(AccessPolicy.stepOwner(status));
        }
    }

    @Test
    void completedControl_isLockedForEveryone_butSoqmStillRenamesAndReturnsIt() {
        for (String user : List.of("SOQM", "PART", "PART_ALL", "RO_ALL")) {
            ControlPermission p = AccessPolicy.resolve(who(user), control("COMPLETED", false, "F+CO+PO+SHARED"));
            assertThat(p.isLocked()).as(user).isTrue();
            assertThat(p.canEdit()).as(user).isFalse();
            assertThat(p.canEditAll()).as(user).isFalse();
            assertThat(p.getAllowedEditableFields()).as(user).isEmpty();
            assertThat(p.editRefusal("other")).as(user).isEqualTo(AccessPolicy.LOCKED_MESSAGE);
        }
        ControlPermission soqm = AccessPolicy.resolve(who("SOQM"), control("COMPLETED", false, "-"));
        assertThat(AccessPolicy.canRenameId(soqm)).isTrue();
        assertThat(soqm.isSoqmLead()).isTrue();
        assertThat(AccessPolicy.canRenameId(AccessPolicy.resolve(who("PART"), control("COMPLETED", false, "PO")))).isFalse();
        assertThat(AccessPolicy.canRenameId(AccessPolicy.resolve(who("PART_ALL"), control("REVIEW", false, "-")))).isFalse();
        // Not completed: not locked, SoQM edits
        ControlPermission review = AccessPolicy.resolve(who("SOQM"), control("PROCESS_OWNER_REVIEW", false, "-"));
        assertThat(review.isLocked()).isFalse();
        assertThat(review.canEditAll()).isTrue();
        assertThat(review.editRefusal("other")).isEqualTo("other");
    }

    @Test
    void soqmTargets_areTheNextStatusAndEveryEarlierWorkingOne() {
        assertThat(AccessPolicy.soqmTargets("DRAFT")).isEmpty();
        assertThat(AccessPolicy.soqmTargets("IN_PROGRESS")).containsExactly("REVIEW");
        assertThat(AccessPolicy.soqmTargets("REVIEW")).containsExactly("SOQM_HEAD_REVIEW", "IN_PROGRESS");
        assertThat(AccessPolicy.soqmTargets("PROCESS_OWNER_REVIEW"))
                .containsExactly("COMPLETED", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW");
        assertThat(AccessPolicy.soqmTargets("COMPLETED"))
                .containsExactly("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW");
        assertThat(AccessPolicy.isReturn("COMPLETED", "IN_PROGRESS")).isTrue();
        assertThat(AccessPolicy.isReturn("IN_PROGRESS", "REVIEW")).isFalse();
        assertThat(AccessPolicy.isReturn("REVIEW", "DRAFT")).isFalse();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            "SOQM,       SOQM_HEAD_REVIEW,     SOQM,  true",
            "SOQM,       SOQM_HEAD_REVIEW,     -,     false",
            "PART,       IN_PROGRESS,          F,     true",
            "PART,       REVIEW,               F,     false",
            "PART,       REVIEW,               CO,    true",
            "PART,       PROCESS_OWNER_REVIEW, PO,    true",
            "PART,       SOQM_HEAD_REVIEW,     SOQM,  false",
            "PART,       DRAFT,                F,     false",
            "PART,       COMPLETED,            PO,    false",
            "RO,         IN_PROGRESS,          F,     false",
            "DISABLED_PART, IN_PROGRESS,       F,     false",
    })
    void myTurn(String user, String status, String places, boolean expected) {
        assertThat(AccessPolicy.isMyTurn(who(user), control(status, false, places))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "SOQM,       -,      true",
            "PART,       SHARED, true",
            "PART,       PO,     false",
            "RO,         SHARED, true",
            "RO_ALL,     -,      false",
            "PART_ALL,   -,      false",
            "DISABLED_SOQM, -,   false",
    })
    void completedControlExport(String user, String places, boolean expected) {
        assertThat(AccessPolicy.canExportCompletedControl(who(user), control("COMPLETED", false, places)))
                .isEqualTo(expected);
    }

    // ------------------------------------------------------------------ assignment

    @ParameterizedTest(name = "{0} as {1} kdn={2}")
    @CsvSource({
            // candidate, slot,             kdnControl, allowed
            "SOQM,        SOQM_LEAD,        false,      true",
            "SOQM,        SOQM_LEAD,        true,       true",
            "SOQM,        FACILITATOR,      false,      false",
            "SOQM,        CONTROL_OPERATOR, false,      false",
            "SOQM,        PROCESS_OWNER,    false,      false",
            "SOQM,        SHARED_WITH,      false,      true",
            "PART,        FACILITATOR,      false,      true",
            "PART,        CONTROL_OPERATOR, true,       true",
            "PART,        PROCESS_OWNER,    false,      true",
            "PART,        SOQM_LEAD,        false,      false",
            "PART_ALL,    FACILITATOR,      false,      true",
            "KDN,         FACILITATOR,      true,       true",
            "KDN,         CONTROL_OPERATOR, true,       true",
            "KDN,         PROCESS_OWNER,    true,       true",
            "KDN,         SOQM_LEAD,        true,       false",
            "KDN,         CONTROL_OPERATOR, false,      false",
            "KDN,         FACILITATOR,      false,      false",
            "KDN,         SHARED_WITH,      false,      false",
            "KDN,         SHARED_WITH,      true,       true",
            "RO,          FACILITATOR,      false,      false",
            "RO,          CONTROL_OPERATOR, false,      false",
            "RO,          PROCESS_OWNER,    false,      false",
            "RO,          SOQM_LEAD,        false,      false",
            "RO,          SHARED_WITH,      false,      true",
            "RO,          FACILITATOR,      true,       false",
            "RO_ALL,      FACILITATOR,      false,      false",
            "RO_ALL,      PROCESS_OWNER,    true,       false",
            "NONE,        SHARED_WITH,      false,      false",
    })
    void assignment(String candidate, Slot slot, boolean kdnControl, boolean allowed) {
        assertThat(AccessPolicy.assignmentRefusal(who(candidate), slot, kdnControl).isEmpty()).isEqualTo(allowed);
    }

    @Test
    void assignmentRefusal_saysWhy() {
        assertThat(AccessPolicy.assignmentRefusal(who("RO"), Slot.FACILITATOR, false))
                .hasValue("has Read Only access and cannot be assigned");
        assertThat(AccessPolicy.assignmentRefusal(who("KDN"), Slot.PROCESS_OWNER, false))
                .hasValue("sees only KDN controls and cannot be added to this control");
        assertThat(AccessPolicy.assignmentRefusal(who("RO_ALL"), Slot.PROCESS_OWNER, true))
                .hasValue("has Read Only access and cannot be assigned");
        assertThat(AccessPolicy.levelScopeRefusal(AccessLevel.PARTICIPANT, AccessScope.KDN))
                .hasValue("KDN access is always Read Only");
        assertThat(AccessPolicy.assignmentRefusal(who("SOQM"), Slot.PROCESS_OWNER, false))
                .hasValue("is SoQM Team; Process Owner takes a User with Edit access");
        assertThat(AccessPolicy.assignmentRefusal(who("PART"), Slot.SOQM_LEAD, false))
                .hasValue("is not SoQM Team");
        assertThat(AccessPolicy.assignmentRefusal(null, Slot.FACILITATOR, false))
                .hasValue("is not a QTracker user");
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
            "SOQM,        ALL,  true",
            "SOQM,        OWN,  false",
            "SOQM,        KDN,  false",
            "PARTICIPANT, OWN,  true",
            "PARTICIPANT, ALL,  true",
            "PARTICIPANT, KDN,  false",
            "READ_ONLY,   OWN,  true",
            "READ_ONLY,   ALL,  true",
            "READ_ONLY,   KDN,  true",
    })
    void levelAndScope_goTogether(AccessLevel level, AccessScope scope, boolean allowed) {
        assertThat(AccessPolicy.levelScopeRefusal(level, scope).isEmpty()).isEqualTo(allowed);
    }

    @Test
    void kdnUserInAStepField_seesTheControl_butNeitherEditsNorActs() {
        for (String places : List.of("F", "CO", "PO")) {
            for (String status : List.of("IN_PROGRESS", "REVIEW", "PROCESS_OWNER_REVIEW")) {
                ControlFacts c = control(status, true, places);
                ControlPermission p = AccessPolicy.resolve(who("KDN"), c);
                assertThat(p.canView()).as(places + " " + status).isTrue();
                assertThat(p.canEdit()).as(places + " " + status).isFalse();
                assertThat(p.canUseWorkflowActions()).as(places + " " + status).isFalse();
                assertThat(AccessPolicy.isMyTurn(who("KDN"), c)).as(places + " " + status).isFalse();
                assertThat(AccessPolicy.canExportCompletedControl(who("KDN"), c)).isFalse();
            }
        }
        assertThat(AccessPolicy.canView(who("KDN"), control("REVIEW", false, "CO"))).isFalse();
    }

    @Test
    void pickers_leaveOutDisabledUsers() {
        assertThat(AccessPolicy.isOfferedFor(who("PART"), Slot.FACILITATOR, false)).isTrue();
        assertThat(AccessPolicy.isOfferedFor(who("DISABLED_PART"), Slot.FACILITATOR, false)).isFalse();
        assertThat(AccessPolicy.isOfferedFor(who("RO"), Slot.CONTROL_OPERATOR, false)).isFalse();
        assertThat(AccessPolicy.isOfferedFor(who("SOQM"), Slot.SOQM_LEAD, false)).isTrue();
    }

    // ------------------------------------------------------------------ inputs

    @Test
    void subjectOfUser_readsLevelScopeAndStatus_andDefaultsToTheLeastAccess() {
        User user = new User();
        user.setAccessLevel(AccessLevel.SOQM);
        user.setAccessScope(AccessScope.ALL);
        user.setAdminAccess(false);
        user.setEnabled(true);
        assertThat(Subject.of(user)).isEqualTo(subject(AccessLevel.SOQM, AccessScope.ALL, true));

        User bare = new User();
        bare.setAccessLevel(null);
        bare.setAccessScope(null);
        bare.setAdminAccess(null);
        bare.setEnabled(null);
        assertThat(Subject.of(bare)).isEqualTo(subject(AccessLevel.READ_ONLY, AccessScope.OWN, false));
        assertThat(Subject.of(null)).isNull();
    }

    @Test
    void adminAccess_isTheLevel_notTheStoredFlag() {
        User flaggedUser = new User();
        flaggedUser.setAccessLevel(AccessLevel.PARTICIPANT);
        flaggedUser.setAccessScope(AccessScope.ALL);
        flaggedUser.setAdminAccess(true);
        flaggedUser.setEnabled(true);
        Subject flagged = Subject.of(flaggedUser);
        assertThat(AccessPolicy.hasAdminAccess(flagged)).isFalse();
        assertThat(AccessPolicy.canOpenAdminPanel(flagged)).isFalse();
        assertThat(AccessPolicy.canManageUsers(flagged)).isFalse();
        assertThat(AccessPolicy.hasAdminAccess(who("SOQM"))).isTrue();
        for (String user : List.of("PART", "PART_ALL", "RO", "RO_ALL", "KDN", "DISABLED_SOQM", "NONE")) {
            assertThat(AccessPolicy.hasAdminAccess(who(user))).as(user).isFalse();
        }
    }

    // ------------------------------------------------------------------ roles as people see them

    @ParameterizedTest(name = "{0} {1} -> {2} {3} {4}")
    @CsvSource(nullValues = "-", value = {
            // level,     scope, role,      visibility, access
            "SOQM,        ALL,   SOQM_TEAM, -,          -",
            "PARTICIPANT, OWN,   USER,      MY,         EDIT",
            "PARTICIPANT, ALL,   USER,      ALL,        EDIT",
            "READ_ONLY,   OWN,   USER,      MY,         READ_ONLY",
            "READ_ONLY,   ALL,   USER,      ALL,        READ_ONLY",
            "READ_ONLY,   KDN,   KDN,       -,          -",
            "-,           -,     USER,      MY,         READ_ONLY",
    })
    void profile_mapsTheStoredLevelAndScope_bothWays(AccessLevel level, AccessScope scope, UserRole role,
                                                     Visibility visibility, AccessRight access) {
        AccessPolicy.Profile profile = AccessPolicy.Profile.of(level, scope);
        assertThat(profile).isEqualTo(new AccessPolicy.Profile(role, visibility, access));
        if (level != null) {
            assertThat(profile.level()).isEqualTo(level);
            assertThat(profile.scope()).isEqualTo(scope);
            assertThat(AccessPolicy.levelScopeRefusal(profile.level(), profile.scope())).isEmpty();
        }
        assertThat(profile.adminAccess()).isEqualTo(role == UserRole.SOQM_TEAM);
    }

    @Test
    void profile_newUserStartsWithMyControlsAndReadOnly_andOtherRolesHaveNeither() {
        assertThat(AccessPolicy.Profile.user(null, null))
                .isEqualTo(new AccessPolicy.Profile(UserRole.USER, Visibility.MY, AccessRight.READ_ONLY));
        assertThat(AccessPolicy.Profile.user(null, null).level()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(AccessPolicy.Profile.user(null, null).scope()).isEqualTo(AccessScope.OWN);
        AccessPolicy.Profile soqm = new AccessPolicy.Profile(UserRole.SOQM_TEAM, Visibility.MY, AccessRight.READ_ONLY);
        assertThat(soqm.visibility()).isNull();
        assertThat(soqm.access()).isNull();
        assertThat(new AccessPolicy.Profile(UserRole.KDN, Visibility.ALL, AccessRight.EDIT).level())
                .isEqualTo(AccessLevel.READ_ONLY);
    }

    @Test
    void everyUserCombination_isAllowed() {
        for (Visibility visibility : Visibility.values()) {
            for (AccessRight access : AccessRight.values()) {
                AccessPolicy.Profile profile = AccessPolicy.Profile.user(visibility, access);
                assertThat(AccessPolicy.levelScopeRefusal(profile.level(), profile.scope()))
                        .as(visibility + " " + access).isEmpty();
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource({
            // user,     status,           places,  notice
            "SOQM,       IN_PROGRESS,      -,       NONE",
            "SOQM,       COMPLETED,        -,       NONE",
            "PART,       IN_PROGRESS,      F,       NONE",
            // Assigned, not their step now: no notice (the step hint and the stepper say whose step it is)
            "PART,       REVIEW,           F,       NONE",
            "PART,       IN_PROGRESS,      SHARED,  NOT_ASSIGNED",
            "PART,       IN_PROGRESS,      CREATOR, NOT_ASSIGNED",
            "PART_ALL,   IN_PROGRESS,      -,       NOT_ASSIGNED",
            "PART_ALL,   PROCESS_OWNER_REVIEW, PO,  NONE",
            "RO,         IN_PROGRESS,      SHARED,  READ_ONLY",
            "RO_ALL,     IN_PROGRESS,      -,       READ_ONLY",
            "RO_ALL,     IN_PROGRESS,      F,       READ_ONLY",
            "PART,       IN_PROGRESS,      -,       NONE",
    })
    void notice_saysWhyThePageHasNoButtons(String user, String status, String places, AccessPolicy.Notice notice) {
        Subject s = who(user);
        ControlFacts c = control(status, false, places);
        ControlPermission p = AccessPolicy.resolve(s, c);
        assertThat(AccessPolicy.notice(s, p)).isEqualTo(notice);
        if (notice != AccessPolicy.Notice.NONE) {
            // A notice means the server refuses every change and step on this control
            assertThat(p.canEdit()).isFalse();
            assertThat(p.isFacilitator() || p.isControlOperator() || p.isProcessOwner() || p.isSoqmLead()).isFalse();
            assertThat(AccessPolicy.move(p, status, "REVIEW")).isEmpty();
        }
    }

    @Test
    void notice_forKdn_isReadOnly_alsoInTheStepFields() {
        Subject kdn = who("KDN");
        ControlFacts c = control("REVIEW", true, "CO");
        assertThat(AccessPolicy.notice(kdn, AccessPolicy.resolve(kdn, c))).isEqualTo(AccessPolicy.Notice.READ_ONLY);
    }

    @Test
    void allControlsEdit_seesEverything_butEditsAndStepsOnlyWhereAssigned() {
        Subject allEdit = who("PART_ALL");
        for (String status : List.of("DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED")) {
            ControlFacts notOnIt = control(status, false, "-");
            assertThat(AccessPolicy.canView(allEdit, notOnIt)).as(status).isTrue();
            assertThat(AccessPolicy.resolve(allEdit, notOnIt).canEdit()).as(status).isFalse();
            assertThat(AccessPolicy.isMyTurn(allEdit, notOnIt)).as(status).isFalse();
            ControlFacts shared = control(status, false, "SHARED");
            assertThat(AccessPolicy.resolve(allEdit, shared).canEdit()).as(status + " shared").isFalse();
        }
        assertThat(AccessPolicy.resolve(allEdit, control("IN_PROGRESS", false, "F")).canEdit()).isTrue();
        assertThat(AccessPolicy.isMyTurn(allEdit, control("IN_PROGRESS", false, "F"))).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"KDN-001", "KDN001", "kdn-5", "Kdn", "KDN", "  KDN-001  ", " kdn-5", "\tkdn-5\n",
            "KDN/FY26/Central/OCT", "KDNX-01", "kDn 7",
            // real IDs (2026-10-07)
            "KDN_RER-CTRL-MF-33A/FY26/Central/1Q", "KDN_EP-CTRL-MF-109A/FY26/Central/DEC-SEP",
            " kdn_rer-ctrl-mf-33a/fy26/central/1q "})
    void kdnControl_idStartsWithKdn_afterTrim_inAnyCase(String id) {
        assertThat(AccessPolicy.isKdnControl(id)).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"X-KDN-12", "HR-KDN-01", "HR-CTRL-MF-1/FY26/kDn", "HR-1", "HR-CTRL-MF-1/FY26/Central",
            "KD-N-01", "K DN-01", "KD", "DN", "-KDN-1", "_KDN1", "", " ", "\t",
            // a real non-KDN ID (2026-10-07)
            "HR-CTRL-MF-151A/FY26/UZB/OCT"})
    void kdnControl_notWhenTheIdDoesNotStartWithKdn(String id) {
        assertThat(AccessPolicy.isKdnControl(id)).isFalse();
    }

    @Test
    void kdnUser_onlyTheRoleKdn_andTheyHaveNoActionQueue() {
        assertThat(AccessPolicy.isKdnUser(who("KDN"))).isTrue();
        for (String key : List.of("SOQM", "PART", "PART_ALL", "RO", "RO_ALL", "DISABLED_SOQM", "DISABLED_PART")) {
            assertThat(AccessPolicy.isKdnUser(who(key))).as(key).isFalse();
        }
        assertThat(AccessPolicy.isKdnUser(subject(AccessLevel.READ_ONLY, AccessScope.KDN, false))).isFalse();
        assertThat(AccessPolicy.isKdnUser(null)).isFalse();

        assertThat(AccessPolicy.seesActionQueue(who("KDN"))).isFalse();
        for (String key : List.of("SOQM", "PART", "PART_ALL", "RO", "RO_ALL")) {
            assertThat(AccessPolicy.seesActionQueue(who(key))).as(key).isTrue();
        }
        assertThat(AccessPolicy.seesActionQueue(null)).isFalse();
    }

    @ParameterizedTest(name = "{0} with {1} KDN controls -> {2}")
    @CsvSource({
            "SOQM, 0, true", "SOQM, 3, true",
            "PART_ALL, 0, true", "RO_ALL, 0, true", "RO_ALL, 2, true",
            "KDN, 0, true", "KDN, 5, true",
            "PART, 0, false", "PART, 1, true", "RO, 0, false", "RO, 2, true",
            "DISABLED_SOQM, 4, false", "DISABLED_PART, 1, false", "NONE, 1, false"})
    void kdnBlock_seenWithoutBeingOn_orWhenMyControlsHoldSome(String key, long kdnControls, boolean shown) {
        assertThat(AccessPolicy.showsKdnBlock(who(key), kdnControls)).isEqualTo(shown);
    }

    @Test
    void kdnControl_notForNoId() {
        assertThat(AccessPolicy.isKdnControl(null)).isFalse();
    }

    @Test
    void renameChangesKdn_whenKdnAppearsOrGoes() {
        assertThat(AccessPolicy.renameChangesKdn("HR-1", "KDN-HR-1")).isTrue();
        assertThat(AccessPolicy.renameChangesKdn("kdn-5", "HR-5")).isTrue();
        assertThat(AccessPolicy.renameChangesKdn("KDN-1", "x-kdn-1")).isTrue();
        assertThat(AccessPolicy.renameChangesKdn(null, "KDN001")).isTrue();
        assertThat(AccessPolicy.renameChangesKdn("HR-1", "HR-KDN-1")).isFalse();
        assertThat(AccessPolicy.renameChangesKdn("KDN-1", " kdn-2 ")).isFalse();
        assertThat(AccessPolicy.renameChangesKdn("HR-1", "HR-2")).isFalse();
        assertThat(AccessPolicy.renameChangesKdn("", null)).isFalse();
    }

    @Test
    void kdnUser_seesAndIsAssignedOnEveryIdStartingWithKdn_onlyThere() {
        for (String id : List.of("KDN-001", "KDN001", "kdn-5", " KDN-7 ")) {
            boolean kdn = AccessPolicy.isKdnControl(id);
            assertThat(AccessPolicy.canView(who("KDN"), control("REVIEW", kdn, "F"))).as(id).isTrue();
            assertThat(AccessPolicy.canView(who("KDN"), control("REVIEW", kdn, "SHARED"))).as(id).isTrue();
            assertThat(AccessPolicy.canView(who("KDN"), control("DRAFT", kdn, "-"))).as(id).isTrue();
            for (Slot slot : List.of(Slot.FACILITATOR, Slot.CONTROL_OPERATOR, Slot.PROCESS_OWNER, Slot.SHARED_WITH)) {
                assertThat(AccessPolicy.assignmentRefusal(who("KDN"), slot, kdn)).as(id + " " + slot).isEmpty();
            }
            assertThat(AccessPolicy.assignmentRefusal(who("KDN"), Slot.SOQM_LEAD, kdn)).as(id).isPresent();
        }
        for (String id : Arrays.asList("HR-001", "X-KDN-12", "HR-KDN-1", "KD-N-1", "", null)) {
            boolean kdn = AccessPolicy.isKdnControl(id);
            assertThat(AccessPolicy.canView(who("KDN"), control("REVIEW", kdn, "F"))).as(id).isFalse();
            for (Slot slot : Slot.values()) {
                assertThat(AccessPolicy.assignmentRefusal(who("KDN"), slot, kdn)).as(id + " " + slot)
                        .hasValueSatisfying(reason -> assertThat(reason).isNotBlank());
            }
            // the others are not affected by the KDN mark
            assertThat(AccessPolicy.canView(who("PART"), control("REVIEW", kdn, "F"))).as(id).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // user,          seesWithoutBeingOn
            "SOQM,            true",
            "PART_ALL,        true",
            "RO_ALL,          true",
            "KDN,             true",
            "PART,            false",
            "RO,              false",
            "DISABLED_SOQM,   false",
            "NONE,            false",
    })
    void seesWithoutBeingOn_soqmAllControlsAndKdn(String user, boolean expected) {
        assertThat(AccessPolicy.seesWithoutBeingOn(who(user))).isEqualTo(expected);
    }

    @Test
    void kdnSees_everyKdnControl_inEveryStatus_onItOrNot_andNoOther() {
        for (String status : List.of("DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED")) {
            for (String places : List.of("-", "F", "CO", "PO", "SHARED", "CREATOR")) {
                ControlFacts kdnControl = control(status, true, places);
                ControlFacts other = control(status, false, places);
                assertThat(AccessPolicy.readAccess(who("KDN"), kdnControl)).as(status + " " + places)
                        .isEqualTo(ReadAccess.ALLOWED);
                assertThat(AccessPolicy.readAccess(who("KDN"), other)).as(status + " " + places + " non-KDN")
                        .isEqualTo(ReadAccess.DENIED);
                ControlPermission p = AccessPolicy.resolve(who("KDN"), kdnControl);
                assertThat(p.canEdit()).as(status + " " + places).isFalse();
                assertThat(p.canUseWorkflowActions()).as(status + " " + places).isFalse();
                assertThat(AccessPolicy.notice(who("KDN"), p)).as(status + " " + places)
                        .isEqualTo(AccessPolicy.Notice.READ_ONLY);
            }
        }
        assertThat(AccessPolicy.KDN_SEES_DRAFTS).isTrue();
    }

    // ------------------------------------------------------------------ Shared With

    @ParameterizedTest(name = "{0} {1} kdn={2} {3}")
    @CsvSource({
            // user,         status,      kdn,   places (before sharing), what Shared With gives
            "PART,           IN_PROGRESS, false, -,       VIEWS",
            "RO,             COMPLETED,   false, -,       VIEWS",
            "RO_ALL,         REVIEW,      false, -,       VIEWS",
            "SOQM,           REVIEW,      false, -,       VIEWS",
            "KDN,            REVIEW,      true,  -,       VIEWS",
            "KDN,            REVIEW,      false, -,       NOT_SEEN",
            "KDN,            DRAFT,       false, -,       NOT_SEEN",
            "RO,             DRAFT,       false, -,       AFTER_INITIATION",
            "PART,           DRAFT,       false, -,       AFTER_INITIATION",
            "PART,           DRAFT,       false, F,       VIEWS",
            // readAccess: a draft is closed to whoever is only shared with it, its creator too (they see it unshared)
            "RO,             DRAFT,       false, CREATOR, AFTER_INITIATION",
            "RO_ALL,         DRAFT,       false, -,       VIEWS",
            "SOQM,           DRAFT,       false, -,       VIEWS",
            "KDN,            DRAFT,       true,  -,       VIEWS",
            "DISABLED_PART,  IN_PROGRESS, false, -,       DISABLED",
            "DISABLED_SOQM,  DRAFT,       false, -,       DISABLED",
            "NONE,           IN_PROGRESS, false, -,       NOT_A_USER",
    })
    void sharedAccess_isWhatReadAccessGivesThemOnceShared(String user, String status, boolean kdn, String places,
                                                         AccessPolicy.SharedAccess expected) {
        ControlFacts before = control(status, kdn, places);
        assertThat(AccessPolicy.sharedAccess(who(user), before)).isEqualTo(expected);
        // Already in the list or about to be: the same answer
        assertThat(AccessPolicy.sharedAccess(who(user), before.withShared(true))).isEqualTo(expected);
    }

    /** The hint at the field, "Can view this control and download files, no editing", is the policy's. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "PART,   IN_PROGRESS", "PART,   REVIEW", "PART,   PROCESS_OWNER_REVIEW", "PART,   COMPLETED",
            "PART_ALL, IN_PROGRESS", "RO,     REVIEW", "RO_ALL, COMPLETED", "KDN, SOQM_HEAD_REVIEW",
    })
    void sharedOnly_viewsAndDownloads_andChangesNothing(String user, String status) {
        boolean kdn = "KDN".equals(user);
        ControlFacts c = control(status, kdn, "SHARED");
        assertThat(AccessPolicy.sharedAccess(who(user), c)).isEqualTo(AccessPolicy.SharedAccess.VIEWS);
        // Viewing and downloading are both the read rule
        assertThat(AccessPolicy.readAccess(who(user), c)).isEqualTo(ReadAccess.ALLOWED);
        ControlPermission p = AccessPolicy.resolve(who(user), c);
        assertThat(p.canEdit()).isFalse();
        assertThat(p.getAllowedEditableFields()).isEmpty();
        for (WorkflowTransition.Actor actor : WorkflowTransition.Actor.values()) {
            assertThat(AccessPolicy.isActor(actor, p)).as(actor.name()).isFalse();
        }
    }

    @Test
    void sharedWithNotes_forSoqmTeamOnly() {
        assertThat(AccessPolicy.seesSharedWithNotes(who("SOQM"))).isTrue();
        for (String user : List.of("PART", "PART_ALL", "KDN", "RO", "RO_ALL", "DISABLED_SOQM", "NONE")) {
            assertThat(AccessPolicy.seesSharedWithNotes(who(user))).as(user).isFalse();
        }
    }
}
