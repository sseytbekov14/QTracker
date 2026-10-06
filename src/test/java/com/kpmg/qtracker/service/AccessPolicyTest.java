package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AccessPolicy.ControlFacts;
import com.kpmg.qtracker.service.AccessPolicy.ReadAccess;
import com.kpmg.qtracker.service.AccessPolicy.Slot;
import com.kpmg.qtracker.service.AccessPolicy.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            Map.entry("SOQM", subject(AccessLevel.SOQM, AccessScope.ALL, false, true)),
            Map.entry("PART", subject(AccessLevel.PARTICIPANT, AccessScope.OWN, false, true)),
            Map.entry("PART_ALL", subject(AccessLevel.PARTICIPANT, AccessScope.ALL, false, true)),
            Map.entry("KDN", subject(AccessLevel.READ_ONLY, AccessScope.KDN, false, true)),
            Map.entry("RO", subject(AccessLevel.READ_ONLY, AccessScope.OWN, false, true)),
            Map.entry("RO_ALL", subject(AccessLevel.READ_ONLY, AccessScope.ALL, false, true)),
            Map.entry("ADMIN_SOQM", subject(AccessLevel.SOQM, AccessScope.ALL, true, true)),
            Map.entry("ADMIN_PART", subject(AccessLevel.PARTICIPANT, AccessScope.OWN, true, true)),
            Map.entry("ADMIN_RO", subject(AccessLevel.READ_ONLY, AccessScope.OWN, true, true)),
            Map.entry("ADMIN_KDN", subject(AccessLevel.READ_ONLY, AccessScope.KDN, true, true)),
            Map.entry("DISABLED_SOQM", subject(AccessLevel.SOQM, AccessScope.ALL, true, false)),
            Map.entry("DISABLED_PART", subject(AccessLevel.PARTICIPANT, AccessScope.OWN, false, false)));

    private static Subject subject(AccessLevel level, AccessScope scope, boolean admin, boolean enabled) {
        return new Subject(level, scope, admin, enabled);
    }

    private static Subject who(String key) {
        return "NONE".equals(key) ? null : SUBJECTS.get(key);
    }

    /** "SPLIT" among the places: the Facilitator and the Control Operator are different people. */
    private static ControlFacts control(String status, boolean kdn, String places) {
        Set<String> p = "-".equals(places) ? Set.of() : Arrays.stream(places.split("\\+")).collect(Collectors.toSet());
        return new ControlFacts(status, kdn, p.contains("F"), p.contains("CO"), p.contains("SOQM"),
                p.contains("PO"), p.contains("SHARED"), p.contains("SPLIT"));
    }

    // ------------------------------------------------------------------ the user alone

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // user,          write, soqm,  all,   adminPanel, manageUsers, create, exportAll, allUsers
            "SOQM,            true,  true,  true,  false,      false,       true,   true,      true",
            "PART,            true,  false, false, false,      false,       false,  false,     false",
            "PART_ALL,        true,  false, true,  false,      false,       false,  false,     false",
            "KDN,             false, false, false, false,      false,       false,  false,     false",
            "RO,              false, false, false, false,      false,       false,  false,     false",
            "RO_ALL,          false, false, true,  false,      false,       false,  false,     false",
            "ADMIN_SOQM,      true,  true,  true,  true,       true,        true,   true,      true",
            "ADMIN_PART,      true,  false, true,  true,       true,        false,  false,     true",
            "ADMIN_RO,        false, false, true,  true,       false,       false,  false,     true",
            "ADMIN_KDN,       false, false, true,  true,       false,       false,  false,     true",
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
            "KDN,          DRAFT,                true,  F,       true,  ALLOWED",
            "KDN,          DRAFT,                true,  SHARED,  true,  DRAFT_NOT_INITIATED",
            "KDN,          REVIEW,               true,  SHARED,  true,  ALLOWED",
            "KDN,          REVIEW,               true,  -,       false, DENIED",
            "KDN,          REVIEW,               false, CO,      false, DENIED",
            "KDN,          COMPLETED,            false, SHARED,  false, DENIED",
            "RO,           COMPLETED,            false, SHARED,  true,  ALLOWED",
            "RO,           DRAFT,                false, SHARED,  true,  DRAFT_NOT_INITIATED",
            "RO,           COMPLETED,            false, -,       false, DENIED",
            "RO,           IN_PROGRESS,          false, F,       true,  ALLOWED",
            "RO_ALL,       DRAFT,                false, -,       true,  ALLOWED",
            "RO_ALL,       PROCESS_OWNER_REVIEW, true,  -,       true,  ALLOWED",
            "ADMIN_PART,   DRAFT,                false, -,       true,  ALLOWED",
            "ADMIN_RO,     REVIEW,               true,  -,       true,  ALLOWED",
            "ADMIN_KDN,    REVIEW,               false, -,       true,  ALLOWED",
            "ADMIN_SOQM,   DRAFT,                false, SHARED,  true,  ALLOWED",
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
            "SOQM,         COMPLETED,            false, -,          true,  true,    -",
            "SOQM,         IN_PROGRESS,          true,  -,          true,  true,    -",
            "PART,         IN_PROGRESS,          false, F,          true,  false,   controlStepsPerformed",
            "PART,         REVIEW,               false, F,          false, false,   -",
            "PART,         DRAFT,                false, F,          false, false,   -",
            "PART,         REVIEW,               false, CO,         true,  false,   controlStepsPerformed",
            "PART,         REVIEW,               false, F+CO,       true,  false,   controlStepsPerformed",
            // Facilitator and Control Operator are different people: one field each, each on its own step
            "PART,         IN_PROGRESS,          false, F+SPLIT,    true,  false,   controlStepsPerformed",
            "PART,         REVIEW,               false, F+SPLIT,    false, false,   -",
            "PART,         REVIEW,               false, CO+SPLIT,   true,  false,   controlOperatorReview",
            "PART,         IN_PROGRESS,          false, CO+SPLIT,   false, false,   -",
            "PART,         SOQM_HEAD_REVIEW,     false, CO+SPLIT,   false, false,   -",
            "PART,         COMPLETED,            false, CO+SPLIT,   false, false,   -",
            "PART,         REVIEW,               false, F+CO+SPLIT, true,  false,   controlOperatorReview",
            "PART,         REVIEW,               false, SHARED+SPLIT, false, false, -",
            "KDN,          REVIEW,               true,  CO+SPLIT,   false, false,   -",
            "KDN,          REVIEW,               false, CO+SPLIT,   false, false,   -",
            "RO,           REVIEW,               false, CO+SPLIT,   false, false,   -",
            "ADMIN_RO,     REVIEW,               false, CO+SPLIT,   false, false,   -",
            "SOQM,         REVIEW,               false, SPLIT,      true,  true,    -",
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
            "ADMIN_PART,   IN_PROGRESS,          false, -,          false, false,   -",
            "ADMIN_PART,   IN_PROGRESS,          false, F,          true,  false,   controlStepsPerformed",
            "ADMIN_RO,     IN_PROGRESS,          false, F,          false, false,   -",
            "ADMIN_KDN,    IN_PROGRESS,          false, F,          false, false,   -",
            "ADMIN_SOQM,   REVIEW,               false, -,          true,  true,    -",
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
        assertThat(p.isStepsSplit()).as("stepsSplit").isEqualTo(p.canView() && places.contains("SPLIT"));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
            // user,     status,      places,       steps field, operator review field
            "SOQM,       REVIEW,      SPLIT,        true,  true",
            "SOQM,       REVIEW,      -,            true,  false",
            "SOQM,       COMPLETED,   SPLIT,        true,  true",
            "PART,       IN_PROGRESS, F+SPLIT,      true,  false",
            "PART,       IN_PROGRESS, F,            true,  false",
            "PART,       REVIEW,      CO+SPLIT,     false, true",
            "PART,       REVIEW,      CO,           true,  false",
            "PART,       REVIEW,      F+SPLIT,      false, false",
            "PART,       REVIEW,      SHARED+SPLIT, false, false",
            "RO_ALL,     REVIEW,      SPLIT,        false, false",
            "ADMIN_PART, REVIEW,      SPLIT,        false, false",
    })
    void whoWritesEachStepsField(String user, String status, String places, boolean steps, boolean review) {
        ControlPermission p = AccessPolicy.resolve(who(user), control(status, false, places));
        assertThat(p.canWriteStepsPerformed()).as("Control Steps Performed").isEqualTo(steps);
        assertThat(p.canWriteOperatorReview()).as("Control Operator Review").isEqualTo(review);
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
            "PART,         COMPLETED,            false, SHARED,   SHARED_RESUBMIT_TO_SOQM_TEAM,  false",
            "PART,         COMPLETED,            false, PO+SHARED, SHARED_RESUBMIT_TO_SOQM_TEAM, false",
            "PART_ALL,     IN_PROGRESS,          false, -,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "PART_ALL,     IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    true",
            "KDN,          IN_PROGRESS,          true,  F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "KDN,          PROCESS_OWNER_REVIEW, true,  PO,       COMPLETE,                      false",
            "KDN,          IN_PROGRESS,          true,  SHARED,   SUBMIT_TO_CONTROL_OPERATOR,    false",
            "KDN,          IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "RO,           IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "RO,           PROCESS_OWNER_REVIEW, false, PO,       COMPLETE,                      false",
            "RO_ALL,       DRAFT,                false, -,        INITIATE,                      false",
            "ADMIN_PART,   DRAFT,                false, -,        INITIATE,                      false",
            "ADMIN_PART,   SOQM_HEAD_REVIEW,     false, -,        SUBMIT_TO_PROCESS_OWNER,       false",
            "ADMIN_PART,   IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    true",
            "ADMIN_RO,     SOQM_HEAD_REVIEW,     false, -,        RETURN_TO_OPERATOR,            false",
            "ADMIN_RO,     IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "ADMIN_SOQM,   DRAFT,                false, -,        INITIATE,                      true",
            "DISABLED_SOQM, DRAFT,               false, -,        INITIATE,                      false",
            "DISABLED_PART, IN_PROGRESS,         false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
            "NONE,         IN_PROGRESS,          false, F,        SUBMIT_TO_CONTROL_OPERATOR,    false",
    })
    void workflowActor(String user, String status, boolean kdn, String places,
                       WorkflowTransition transition, boolean allowed) {
        ControlPermission p = AccessPolicy.resolve(who(user), control(status, kdn, places));
        assertThat(AccessPolicy.isActor(transition.getActor(), p)).isEqualTo(allowed);
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
            "ADMIN_RO,   REVIEW,               CO,    false",
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
            "ADMIN_PART, -,      false",
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
            "ADMIN_RO,    FACILITATOR,      false,      false",
            "ADMIN_PART,  FACILITATOR,      false,      true",
            "NONE,        SHARED_WITH,      false,      false",
    })
    void assignment(String candidate, Slot slot, boolean kdnControl, boolean allowed) {
        assertThat(AccessPolicy.assignmentRefusal(who(candidate), slot, kdnControl).isEmpty()).isEqualTo(allowed);
    }

    @Test
    void assignmentRefusal_saysWhy() {
        assertThat(AccessPolicy.assignmentRefusal(who("RO"), Slot.FACILITATOR, false))
                .hasValue("has read-only access and cannot be assigned");
        assertThat(AccessPolicy.assignmentRefusal(who("KDN"), Slot.PROCESS_OWNER, false))
                .hasValue("sees only KDN controls and cannot be added to this control");
        assertThat(AccessPolicy.assignmentRefusal(who("RO_ALL"), Slot.PROCESS_OWNER, true))
                .hasValue("has read-only access and cannot be assigned");
        assertThat(AccessPolicy.levelScopeRefusal(AccessLevel.PARTICIPANT, AccessScope.KDN))
                .hasValue("KDN users only view their KDN controls: level must be Read only");
        assertThat(AccessPolicy.assignmentRefusal(who("SOQM"), Slot.PROCESS_OWNER, false))
                .hasValue("is a SoQM user; Process Owner must be a participant");
        assertThat(AccessPolicy.assignmentRefusal(who("PART"), Slot.SOQM_LEAD, false))
                .hasValue("is not a SoQM user");
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
    void subjectOfUser_readsLevelScopeAndFlags_andDefaultsToTheLeastAccess() {
        User user = new User();
        user.setAccessLevel(AccessLevel.SOQM);
        user.setAccessScope(AccessScope.ALL);
        user.setAdminAccess(true);
        user.setEnabled(true);
        assertThat(Subject.of(user)).isEqualTo(subject(AccessLevel.SOQM, AccessScope.ALL, true, true));

        User bare = new User();
        bare.setAccessLevel(null);
        bare.setAccessScope(null);
        bare.setAdminAccess(null);
        bare.setEnabled(null);
        assertThat(Subject.of(bare)).isEqualTo(subject(AccessLevel.READ_ONLY, AccessScope.OWN, false, false));
        assertThat(Subject.of(null)).isNull();
    }

    @Test
    void kdnControl_isAControlIdStartingWithKdnDash() {
        for (String id : List.of("KDN-CTRL-MF-01/FY26/KZ/Q1", " kdn-01", "Kdn-")) {
            assertThat(AccessPolicy.isKdnControl(id)).as(id).isTrue();
        }
        for (String id : List.of("HR-KDN-01", "KDN/FY26", "KDNX-01", "Kdn", "", " ")) {
            assertThat(AccessPolicy.isKdnControl(id)).as(id).isFalse();
        }
        assertThat(AccessPolicy.isKdnControl(null)).isFalse();
    }
}
