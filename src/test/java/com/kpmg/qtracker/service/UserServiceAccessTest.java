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
 * Admin Panel saves: a blank role or additional role keeps the stored value, also one the lists do not
 * offer, so changing another field of a user never rewrites it.
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

    private User stored(String role, String secondaryRole) {
        User user = new User();
        user.setId(TARGET_ID);
        user.setMail("target@example.test");
        user.setRole(role);
        user.setSecondaryRole(secondaryRole);
        user.setEnabled(true);
        user.setAdminAccess(false);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        return user;
    }

    @Test
    void blankRole_keepsAdminRole_whileStatusChanges() {
        stored("ADMIN", null);

        User saved = service.updateUserAccess(TARGET_ID, "", "", false, false, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("ADMIN");
        assertThat(saved.getSecondaryRole()).isNull();
        assertThat(saved.getEnabled()).isFalse();
    }

    @Test
    void blankRole_keepsNonStandardSpelling() {
        stored("SoQM Team", null);

        User saved = service.updateUserAccess(TARGET_ID, null, null, true, true, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("SoQM Team");
        assertThat(saved.getAdminAccess()).isTrue();
    }

    @Test
    void blankRole_keepsValueWithComma() {
        stored("FACILITATOR,PROCESS_OWNER", null);

        User saved = service.updateUserAccess(TARGET_ID, " ", "", false, false, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("FACILITATOR,PROCESS_OWNER");
    }

    @Test
    void blankAdditionalRole_keepsUnlistedValue_whenRoleChanges() {
        stored("FACILITATOR", "Soqm_Team");

        User saved = service.updateUserAccess(TARGET_ID, "PROCESS_OWNER", "", false, true, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("PROCESS_OWNER");
        assertThat(saved.getSecondaryRole()).isEqualTo("Soqm_Team");
    }

    @Test
    void blankAdditionalRole_keepsValueWithComma() {
        stored("SOQM_TEAM", "FACILITATOR;CONTROL_OPERATOR");

        User saved = service.updateUserAccess(TARGET_ID, "", "", false, false, ADMIN_ID);

        assertThat(saved.getSecondaryRole()).isEqualTo("FACILITATOR;CONTROL_OPERATOR");
    }

    @Test
    void noneClearsAdditionalRole() {
        stored("ADMIN", "facilitator");

        User saved = service.updateUserAccess(TARGET_ID, "", "NONE", false, true, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("ADMIN");
        assertThat(saved.getSecondaryRole()).isNull();
    }

    @Test
    void additionalRoleChange_keepsUnlistedRole() {
        stored("ADMIN", null);

        User saved = service.updateUserAccess(TARGET_ID, "", "process_owner", false, true, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("ADMIN");
        assertThat(saved.getSecondaryRole()).isEqualTo("PROCESS_OWNER");
    }

    @Test
    void listedValues_areStillValidated() {
        stored("ADMIN", null);

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "", "KDN", false, true, ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Additional role can only be");
        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "ADMIN", "", false, true, ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported role");
    }

    @Test
    void newRoleEqualToKeptAdditionalRole_isRefused() {
        stored("CONTROL_OPERATOR", "Facilitator");

        assertThatThrownBy(() -> service.updateUserAccess(TARGET_ID, "FACILITATOR", "", false, true, ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void statusChange_doesNotRecheckStoredRoles() {
        // Old data with the same value twice: the admin can still disable the user
        stored("FACILITATOR", "FACILITATOR");

        User saved = service.updateUserAccess(TARGET_ID, "", "", false, false, ADMIN_ID);

        assertThat(saved.getEnabled()).isFalse();
        assertThat(saved.getSecondaryRole()).isEqualTo("FACILITATOR");
    }

    @Test
    void listedRoles_areSavedAsBefore() {
        stored("FACILITATOR", null);

        User saved = service.updateUserAccess(TARGET_ID, "CONTROL_OPERATOR", "PROCESS_OWNER", false, true, ADMIN_ID);

        assertThat(saved.getRole()).isEqualTo("CONTROL_OPERATOR");
        assertThat(saved.getSecondaryRole()).isEqualTo("PROCESS_OWNER");
    }

    @Test
    void roleChange_setsTheMatchingAccessLevelAndScope() {
        stored("FACILITATOR", null);

        User saved = service.updateUserAccess(TARGET_ID, "SOQM_TEAM", "", false, true, ADMIN_ID);

        assertThat(saved.getAccessLevel()).isEqualTo(AccessLevel.SOQM);
        assertThat(saved.getAccessScope()).isEqualTo(AccessScope.ALL);
    }

    @Test
    void createUser_setsTheAccessOfItsRole() {
        when(userRepository.existsByMail("kdn@example.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = service.createUser("kdn@example.test", "KDN User", "KDN", false, true);

        assertThat(created.getAccessLevel()).isEqualTo(AccessLevel.PARTICIPANT);
        assertThat(created.getAccessScope()).isEqualTo(AccessScope.KDN);
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
    }
}
