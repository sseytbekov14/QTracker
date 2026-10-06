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
        stored(AccessLevel.READ_ONLY, AccessScope.KDN);

        User saved = service.updateUserAccess(TARGET_ID, "", null, false, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.KDN);
        assertThat(saved.getEnabled()).isFalse();
    }

    @Test
    void levelAndScope_areSaved_andTheOldRoleColumnsStayAsTheyWere() {
        stored(AccessLevel.PARTICIPANT, AccessScope.OWN);

        User saved = service.updateUserAccess(TARGET_ID, "read-only", "all", true, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.ALL);
        // The admin flag follows the level: SoQM Team only
        assertThat(saved.getAdminAccess()).isFalse();
        assertThat(saved.getRole()).isEqualTo("FACILITATOR");
        assertThat(saved.getSecondaryRole()).isEqualTo("PROCESS_OWNER");
    }

    @Test
    void soqm_alwaysGetsScopeAll_andRefusesAnyOther() {
        stored(AccessLevel.READ_ONLY, AccessScope.KDN);

        assertThat(service.updateUserAccess(TARGET_ID, "SOQM", "", true, ADMIN_ID).getAccessScope())
                .isEqualTo(AccessScope.ALL);
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "SOQM", "OWN", true, ADMIN_ID))
                .hasMessage("SoQM Team always sees all controls");
    }

    @Test
    void kdn_isAlwaysReadOnly_whicheverWayTheLevelOrScopeChanges() {
        stored(AccessLevel.READ_ONLY, AccessScope.KDN);
        String refusal = "KDN users have read-only access";

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "PARTICIPANT", "", true, ADMIN_ID))
                .hasMessage(refusal);
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "PARTICIPANT", "KDN", true, ADMIN_ID))
                .hasMessage(refusal);
        assertThat(service.updateUserAccess(TARGET_ID, "PARTICIPANT", "OWN", true, ADMIN_ID).getAccessScope())
                .isEqualTo(AccessScope.OWN);
        assertThatThrownBy(() -> service.createUser("k@example.test", null, "PARTICIPANT", "KDN", true))
                .hasMessage(refusal);
    }

    @Test
    void leavingSoqm_keepsScopeAll_untilAnotherIsChosen() {
        stored(AccessLevel.SOQM, AccessScope.ALL);

        User saved = service.updateUserAccess(TARGET_ID, "PARTICIPANT", "", true, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.ALL);
    }

    @Test
    void unknownLevelOrScope_isRefused() {
        stored(AccessLevel.PARTICIPANT, AccessScope.OWN);

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "ADMIN", "", true, ADMIN_ID))
                .hasMessage("Unsupported access level: ADMIN");
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "PARTICIPANT", "TEAM", true, ADMIN_ID))
                .hasMessage("Unsupported scope: TEAM");
    }

    @Test
    void soqmTeam_cannotDisableThemselves_orChangeTheirOwnRole() {
        stored(AccessLevel.SOQM, AccessScope.ALL);

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "", "", false, TARGET_ID))
                .hasMessage("You cannot deactivate your own account");
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "PARTICIPANT", "ALL", true, TARGET_ID))
                .hasMessage("You cannot change your own role");
        assertThat(service.updateUserAccess(TARGET_ID, "SOQM", "", true, TARGET_ID).getAccessLevel())
                .isEqualTo(AccessLevel.SOQM);
    }

    @Test
    void updateUser_changesNameEmailAndAccessTogether_beforeTheFirstLogin() {
        stored(AccessLevel.READ_ONLY, AccessScope.OWN);
        when(userRepository.existsByMail("new@example.test")).thenReturn(false);

        User saved = service.updateUser(TARGET_ID, " New@Example.test ", "  Jane Doe ", "READ_ONLY", "KDN", true, ADMIN_ID);

        assertThat(saved.getMail()).isEqualTo("new@example.test");
        assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.KDN);
    }

    @Test
    void updateUser_refusedValue_changesNothing() {
        User user = stored(AccessLevel.READ_ONLY, AccessScope.OWN);
        user.setDisplayName("Old Name");
        user.setLastLoginAt(java.time.LocalDateTime.of(2026, 10, 1, 9, 0));

        assertThatThrownBy(() -> service.updateUser(TARGET_ID, "other@example.test", "New Name", "SOQM", "", true, ADMIN_ID))
                .hasMessage("Email can be changed only before the first login");
        assertThatThrownBy(() -> service.updateUser(TARGET_ID, null, "   ", "SOQM", "", true, ADMIN_ID))
                .hasMessage("Name is required");
        assertThatThrownBy(() -> service.updateUser(TARGET_ID, null, "x".repeat(256), "SOQM", "", true, ADMIN_ID))
                .hasMessage("Name is too long (255 characters at most)");
        assertThat(user.getMail()).isEqualTo("target@example.test");
        assertThat(user.getDisplayName()).isEqualTo("Old Name");
        assertThat(user.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(user.getAdminAccess()).isFalse();

        // The same address in another case is no change, also after the first login
        assertThat(service.updateUser(TARGET_ID, "TARGET@example.test", null, "", "", true, ADMIN_ID).getMail())
                .isEqualTo("target@example.test");
    }

    @Test
    void createUser_withLevelAndScope_hasNoOldRole() {
        when(userRepository.existsByMail("ro@example.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = service.createUser("ro@example.test", "Viewer", "READ_ONLY", "ALL", true);

        assertThat(created.getAccessLevel()).isEqualTo(AccessLevel.READ_ONLY);
        assertThat(created.getAccessScope()).isEqualTo(AccessScope.ALL);
        assertThat(created.getRole()).isNull();
    }

    @Test
    void createUser_soqmGetsScopeAll_andRefusesAnyOtherScope() {
        when(userRepository.existsByMail(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.createUser("s@example.test", null, "SOQM", null, true).getAccessScope())
                .isEqualTo(AccessScope.ALL);
        assertThat(service.createUser("p@example.test", null, "PARTICIPANT", "", true).getAccessScope())
                .isEqualTo(AccessScope.OWN);
        assertThatThrownBy(() -> service.createUser("s2@example.test", null, "SOQM", "KDN", true))
                .hasMessage("SoQM Team always sees all controls");
        assertThatThrownBy(() -> service.createUser("x@example.test", null, "ADMIN", "ALL", true))
                .hasMessage("Unsupported access level: ADMIN");
        assertThatThrownBy(() -> service.createUser("y@example.test", null, "PARTICIPANT", "MINE", true))
                .hasMessage("Unsupported scope: MINE");
        assertThatThrownBy(() -> service.createUser("z@example.test", null, " ", "OWN", true))
                .hasMessage("Access level is required");
    }
}
