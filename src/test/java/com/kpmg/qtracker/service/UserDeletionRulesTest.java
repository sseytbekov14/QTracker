package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AccessPolicy.Subject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of deleting a user (AccessPolicy, business decision of 2026-10-13) and of disabling one by Status. */
class UserDeletionRulesTest {

    private static final Subject SOQM = new Subject(AccessLevel.SOQM, AccessScope.ALL, true);
    private static final Subject SOQM_OFF = new Subject(AccessLevel.SOQM, AccessScope.ALL, false);
    private static final Subject USER = new Subject(AccessLevel.PARTICIPANT, AccessScope.OWN, true);
    private static final Subject ALL_EDIT = new Subject(AccessLevel.PARTICIPANT, AccessScope.ALL, true);
    private static final Subject KDN = new Subject(AccessLevel.READ_ONLY, AccessScope.KDN, true);

    @Test
    void soqmAdminTeamOnly_neverOneself_neverTheLastActiveSoqm_aDisabledOneMayGo() {
        assertThat(AccessPolicy.userDeletionRefusal(SOQM, 1L, USER, 2L, 1)).isEmpty();
        assertThat(AccessPolicy.userDeletionRefusal(USER, 1L, USER, 2L, 2)).hasValue(AccessPolicy.DELETE_NOT_ALLOWED);
        assertThat(AccessPolicy.userDeletionRefusal(ALL_EDIT, 1L, USER, 2L, 2)).hasValue(AccessPolicy.DELETE_NOT_ALLOWED);
        assertThat(AccessPolicy.userDeletionRefusal(KDN, 1L, USER, 2L, 2)).hasValue(AccessPolicy.DELETE_NOT_ALLOWED);
        assertThat(AccessPolicy.userDeletionRefusal(SOQM_OFF, 1L, USER, 2L, 2)).hasValue(AccessPolicy.DELETE_NOT_ALLOWED);
        assertThat(AccessPolicy.userDeletionRefusal(SOQM, 1L, SOQM, 1L, 2)).hasValue(AccessPolicy.DELETE_SELF);
        // The last active SoQM Admin Team member (the admin is counted too, so only a disabled admin's case)
        assertThat(AccessPolicy.userDeletionRefusal(SOQM, 1L, SOQM, 2L, 1)).hasValue(AccessPolicy.DELETE_LAST_SOQM);
        assertThat(AccessPolicy.userDeletionRefusal(SOQM, 1L, SOQM, 2L, 2)).isEmpty();
        // A disabled SoQM Admin Team member is not an active one
        assertThat(AccessPolicy.userDeletionRefusal(SOQM, 1L, SOQM_OFF, 2L, 1)).isEmpty();
        assertThat(AccessPolicy.DELETE_NOT_ALLOWED).isEqualTo("Only SoQM Admin Team can delete users");
    }

    /** Status is the one way to disable an account (2026-10-23): the same two refusals as deleting. */
    @Test
    void status_neverDeactivatesOneself_orTheLastActiveSoqm_andActivatingIsNeverRefused() {
        assertThat(AccessPolicy.statusChangeRefusal(1L, USER, 2L, false, 1)).isEmpty();
        assertThat(AccessPolicy.statusChangeRefusal(1L, SOQM, 1L, false, 2)).hasValue(AccessPolicy.DEACTIVATE_SELF);
        assertThat(AccessPolicy.statusChangeRefusal(1L, SOQM, 2L, false, 1)).hasValue(AccessPolicy.DEACTIVATE_LAST_SOQM);
        assertThat(AccessPolicy.statusChangeRefusal(1L, SOQM, 2L, false, 2)).isEmpty();
        assertThat(AccessPolicy.statusChangeRefusal(1L, SOQM_OFF, 2L, false, 1)).isEmpty();
        assertThat(AccessPolicy.statusChangeRefusal(1L, SOQM, 1L, true, 1)).isEmpty();
        assertThat(AccessPolicy.statusChangeRefusal(1L, SOQM_OFF, 2L, true, 0)).isEmpty();
        assertThat(AccessPolicy.DEACTIVATE_SELF).isEqualTo("You cannot deactivate your own account");
        assertThat(AccessPolicy.DEACTIVATE_LAST_SOQM).isEqualTo("This is the last active SoQM Admin Team member:"
                + " they cannot be deactivated until someone else is SoQM Admin Team");
    }

    @Test
    void aDeletedPerson_staysWhereTheyWere_butIsAddedNowhere() {
        for (AccessPolicy.Slot slot : AccessPolicy.Slot.values()) {
            assertThat(AccessPolicy.assignmentRefusal(null, slot, false, true)).as(slot.name()).isEmpty();
            assertThat(AccessPolicy.assignmentRefusal(null, slot, false, false)).as(slot.name())
                    .hasValue("is not a QTracker user");
        }
        // A real user is checked as always, whatever the flag
        assertThat(AccessPolicy.assignmentRefusal(KDN, AccessPolicy.Slot.FACILITATOR, false, true)).isPresent();
    }
}
