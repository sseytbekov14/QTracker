package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Admin Panel saves of level, scope, admin access and status: a blank level or scope keeps the stored
 * one, SoQM always gets scope ALL, the old role columns are never touched, and an admin cannot lock
 * themselves out.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceAccessTest {

    private static final long TARGET_ID = 7L;
    private static final long ADMIN_ID = 1L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService service;

    private User stored(AccessLevel level, AccessScope scope) {
        User user = new User();
        user.setId(TARGET_ID);
        user.setMail("target@example.test");
        user.setRole("FACILITATOR");
        user.setSecondaryRole("PROCESS_OWNER");
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setEnabled(true);
        user.setAdminAccess(false);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        return user;
    }

    @Test
    void blankLevelAndScope_keepTheStoredOnes_whileStatusChanges() {
        stored(AccessLevel.PARTICIPANT, AccessScope.KDN);

        User saved = service.updateUserAccess(TARGET_ID, "", null, false, false, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.KDN);
        assertThat(saved.getEnabled()).isFalse();
    }

    @Test
    void levelAndScope_areSaved_andTheOldRoleColumnsStayAsTheyWere() {
        stored(AccessLevel.PARTICIPANT, AccessScope.OWN);

        User saved = service.updateUserAccess(TARGET_ID, "read-only", "all", true, true, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.ALL);
        assertThat(saved.getAdminAccess()).isTrue();
        assertThat(saved.getRole()).isEqualTo("FACILITATOR");
        assertThat(saved.getSecondaryRole()).isEqualTo("PROCESS_OWNER");
    }

    @Test
    void soqm_alwaysGetsScopeAll_andRefusesAnyOther() {
        stored(AccessLevel.PARTICIPANT, AccessScope.KDN);

        assertThat(service.updateUserAccess(TARGET_ID, "SOQM", "", false, true, ADMIN_ID).getAccessScope())
                .isEqualTo(AccessScope.ALL);
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "SOQM", "OWN", false, true, ADMIN_ID))
                .hasMessage("SoQM always sees all controls: scope must be ALL");
    }

    @Test
    void leavingSoqm_keepsScopeAll_untilAnotherIsChosen() {
        stored(AccessLevel.SOQM, AccessScope.ALL);

        User saved = service.updateUserAccess(TARGET_ID, "PARTICIPANT", "", false, true, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.ALL);
    }

    @Test
    void unknownLevelOrScope_isRefused() {
        stored(AccessLevel.PARTICIPANT, AccessScope.OWN);

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "ADMIN", "", false, true, ADMIN_ID))
                .hasMessage("Unsupported access level: ADMIN");
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "PARTICIPANT", "TEAM", false, true, ADMIN_ID))
                .hasMessage("Unsupported scope: TEAM");
    }

    @Test
    void admin_cannotDisableThemselves_removeTheirAdminAccess_orBecomeReadOnly() {
        User self = stored(AccessLevel.PARTICIPANT, AccessScope.ALL);
        self.setAdminAccess(true);

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "", "", true, false, TARGET_ID))
                .hasMessage("You cannot disable your own account or remove your own admin access");
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "", "", false, true, TARGET_ID))
                .hasMessage("You cannot disable your own account or remove your own admin access");
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "READ_ONLY", "", true, true, TARGET_ID))
                .hasMessage("You cannot make your own access read-only");
        assertThat(service.updateUserAccess(TARGET_ID, "SOQM", "", true, true, TARGET_ID).getAccessLevel())
                .isEqualTo(AccessLevel.SOQM);
    }

    @Test
    void createUser_withLevelAndScope_hasNoOldRole() {
        when(userRepository.existsByMail("ro@example.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = service.createUser("ro@example.test", "Viewer", "READ_ONLY", "ALL", false, true);

        assertThat(created.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(created.getAccessScope()).isEqualTo(AccessScope.ALL);
        assertThat(created.getRole()).isNull();
    }

    @Test
    void createUser_soqmGetsScopeAll_andRefusesAnyOtherScope() {
        when(userRepository.existsByMail(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.createUser("s@example.test", null, "SOQM", null, false, true).getAccessScope())
                .isEqualTo(AccessScope.ALL);
        assertThat(service.createUser("p@example.test", null, "PARTICIPANT", "", false, true).getAccessScope())
                .isEqualTo(AccessScope.OWN);
        assertThatThrownBy(() -> service.createUser("s2@example.test", null, "SOQM", "KDN", false, true))
                .hasMessage("SoQM always sees all controls: scope must be ALL");
        assertThatThrownBy(() -> service.createUser("x@example.test", null, "ADMIN", "ALL", false, true))
                .hasMessage("Unsupported access level: ADMIN");
        assertThatThrownBy(() -> service.createUser("y@example.test", null, "PARTICIPANT", "MINE", false, true))
                .hasMessage("Unsupported scope: MINE");
        assertThatThrownBy(() -> service.createUser("z@example.test", null, " ", "OWN", false, true))
                .hasMessage("Access level is required");
    }
}
