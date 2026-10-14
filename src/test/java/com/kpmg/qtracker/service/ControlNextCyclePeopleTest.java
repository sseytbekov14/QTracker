package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Who the next cycle takes along, and what stops it (ControlNextCycle.people). */
class ControlNextCyclePeopleTest {

    private final Map<String, User> users = new HashMap<>();
    private final Set<String> deleted = Set.of("gone@example.test");

    private User user(String mail, AccessLevel level, AccessScope scope, boolean enabled) {
        User user = new User();
        user.setMail(mail);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setEnabled(enabled);
        users.put(mail, user);
        return user;
    }

    private ControlNextCycle.People people(ControlAssignment source, boolean kdnControl) {
        return ControlNextCycle.people(source, kdnControl, mail -> Optional.ofNullable(users.get(mail)), deleted::contains);
    }

    private ControlAssignment assignment(String facilitator, String operator, String owner, String soqm, String shared) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setFacilitator(facilitator);
        assignment.setControlOperator(operator);
        assignment.setProcessOwner(owner);
        assignment.setSoqmLead(soqm);
        assignment.setControlSharedWith(shared);
        return assignment;
    }

    @Test
    void everyoneFine_noProblems_sharedWithKept() {
        user("f@example.test", AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        user("o@example.test", AccessLevel.PARTICIPANT, AccessScope.ALL, true);
        user("p@example.test", AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        user("s@example.test", AccessLevel.SOQM, AccessScope.ALL, true);
        user("r@example.test", AccessLevel.READ_ONLY, AccessScope.OWN, true);

        ControlNextCycle.People people = people(assignment("f@example.test", "o@example.test", "p@example.test",
                "s@example.test", "r@example.test"), false);

        assertThat(people.problems()).isEmpty();
        assertThat(people.sharedWith()).isEqualTo("r@example.test");
        assertThat(people.sharedWithLeftOut()).isEmpty();
    }

    @Test
    void problems_inTheBusinessOrder_oneLinePerPerson() {
        user("f@example.test", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        user("f2@example.test", AccessLevel.READ_ONLY, AccessScope.OWN, true);
        user("s@example.test", AccessLevel.PARTICIPANT, AccessScope.OWN, true);

        ControlNextCycle.People people = people(assignment("f@example.test, f2@example.test", "gone@example.test",
                "nobody@example.test", "s@example.test", null), false);

        assertThat(people.problems()).containsExactly(
                "Facilitator: f@example.test is disabled",
                "Facilitator: f2@example.test has Read Only access and cannot be assigned",
                "Control Operator: gone@example.test was deleted from QTracker",
                "Process Owner: nobody@example.test is not a QTracker user",
                "SoQM Team / Delegate: s@example.test is not SoQM Admin Team");
    }

    @Test
    void emptySource_everyFieldNamed() {
        assertThat(people(null, false).problems()).containsExactly(
                "Facilitator: nobody is assigned",
                "Control Operator: nobody is assigned",
                "Process Owner: nobody is assigned",
                "SoQM Team / Delegate: nobody is assigned");
    }

    @Test
    void sharedWith_kdnUserOnANonKdnControlLeftOut_keptOnAKdnControl() {
        user("f@example.test", AccessLevel.PARTICIPANT, AccessScope.OWN, true);
        user("s@example.test", AccessLevel.SOQM, AccessScope.ALL, true);
        user("k@example.test", AccessLevel.READ_ONLY, AccessScope.KDN, true);
        user("off@example.test", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        ControlAssignment source = assignment("f@example.test", "f@example.test", "f@example.test", "s@example.test",
                "k@example.test,gone@example.test,off@example.test,stranger@example.test");

        ControlNextCycle.People hr = people(source, false);
        assertThat(hr.sharedWith()).isEqualTo("off@example.test");
        assertThat(hr.sharedWithLeftOut()).containsExactly(
                "k@example.test (sees only KDN controls and cannot be added to this control)",
                "gone@example.test (deleted from QTracker)",
                "stranger@example.test (not a QTracker user)");

        ControlNextCycle.People kdn = people(source, true);
        assertThat(kdn.sharedWith()).isEqualTo("k@example.test,off@example.test");
    }
}
