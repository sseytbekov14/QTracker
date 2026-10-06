package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin Panel saves by role: SoQM Team, User with Visibility and Access (every combination allowed, a new
 * User starts at My controls and Read Only), KDN. The stored level, scope and admin flag follow the role,
 * the old role columns are never touched, nobody changes their own role or deactivates themselves, and the
 * last active SoQM Team member keeps the role and the account.
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
        user.setAdminAccess(level == AccessLevel.SOQM);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        // Two active SoQM Team members unless a test says otherwise
        lenient().when(userRepository.countActiveByAccessLevel(AccessLevel.SOQM)).thenReturn(2L);
        return user;
    }

    private User update(String role, String visibility, String access, boolean enabled, long actor) {
        return service.updateUser(TARGET_ID, null, null, role, visibility, access, enabled, actor);
    }

    private static String stored(User user) {
        return user.getAccessLevel() + "/" + user.getAccessScope() + "/" + user.getAdminAccess();
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(nullValues = "-", value = {
            // role,      visibility, access,    stored level/scope/admin
            "SOQM_TEAM,   -,          -,         SOQM/ALL/true",
            "USER,        MY,         EDIT,      PARTICIPANT/OWN/false",
            "USER,        ALL,        EDIT,      PARTICIPANT/ALL/false",
            "USER,        MY,         READ_ONLY, READ_ONLY/OWN/false",
            "USER,        ALL,        READ_ONLY, READ_ONLY/ALL/false",
            "KDN,         -,          -,         READ_ONLY/KDN/false",
            "user,        all,        read-only, READ_ONLY/ALL/false",
    })
    void everyRoleAndEveryUserCombination_isStoredAsDecided(String role, String visibility, String access,
                                                            String expected) {
        stored(AccessLevel.READ_ONLY, AccessScope.KDN);

        assertThat(stored(update(role, visibility, access, true, ADMIN_ID))).isEqualTo(expected);
    }

    @Test
    void aNewUser_startsAtMyControlsAndReadOnly() {
        when(userRepository.existsByMail(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = service.createUser("new@example.test", "New", "USER", null, null, true);

        assertThat(stored(created)).isEqualTo("READ_ONLY/OWN/false");
        assertThat(created.getRole()).isNull();
    }

    @Test
    void becomingUser_startsAtMyControlsAndReadOnly_stayingUser_keepsWhatIsNotSent() {
        stored(AccessLevel.READ_ONLY, AccessScope.KDN);
        assertThat(stored(update("USER", "", "", true, ADMIN_ID))).isEqualTo("READ_ONLY/OWN/false");

        User user = stored(AccessLevel.PARTICIPANT, AccessScope.ALL);
        assertThat(stored(update("USER", "", "READ_ONLY", true, ADMIN_ID))).isEqualTo("READ_ONLY/ALL/false");
        assertThat(stored(update("", "", "", false, ADMIN_ID))).isEqualTo("READ_ONLY/ALL/false");
        assertThat(user.getEnabled()).isFalse();
        // The old role columns stay as they were
        assertThat(user.getRole()).isEqualTo("FACILITATOR");
        assertThat(user.getSecondaryRole()).isEqualTo("PROCESS_OWNER");
    }

    @Test
    void visibilityAndAccess_belongToUsersOnly_andUnknownValuesAreRefused() {
        stored(AccessLevel.PARTICIPANT, AccessScope.OWN);

        assertThatThrownBy(() -> update("KDN", "ALL", "", true, ADMIN_ID))
                .hasMessage("Visibility and Access apply only to the role User");
        assertThatThrownBy(() -> update("SOQM_TEAM", "", "EDIT", true, ADMIN_ID))
                .hasMessage("Visibility and Access apply only to the role User");
        assertThatThrownBy(() -> update("ADMIN", "", "", true, ADMIN_ID)).hasMessage("Unknown role: ADMIN");
        assertThatThrownBy(() -> update("USER", "TEAM", "", true, ADMIN_ID)).hasMessage("Unknown visibility: TEAM");
        assertThatThrownBy(() -> update("USER", "", "WRITE", true, ADMIN_ID)).hasMessage("Unknown access: WRITE");
        assertThatThrownBy(() -> service.createUser("x@example.test", null, " ", null, null, true))
                .hasMessage("Role is required");
    }

    @Test
    void nobodyChangesTheirOwnRole_orDeactivatesThemselves_butMayRenameThemselves() {
        User self = stored(AccessLevel.SOQM, AccessScope.ALL);

        assertThatThrownBy(() -> update("USER", "ALL", "EDIT", true, TARGET_ID))
                .hasMessage("You cannot change your own role");
        assertThatThrownBy(() -> update("KDN", "", "", true, TARGET_ID))
                .hasMessage("You cannot change your own role");
        assertThatThrownBy(() -> update("SOQM_TEAM", "", "", false, TARGET_ID))
                .hasMessage("You cannot deactivate your own account");
        assertThat(service.updateUser(TARGET_ID, null, "Me Myself", "SOQM_TEAM", "", "", true, TARGET_ID)
                .getDisplayName()).isEqualTo("Me Myself");
        assertThat(stored(self)).isEqualTo("SOQM/ALL/true");
    }

    @Test
    void theLastActiveSoqmTeamMember_keepsTheRoleAndTheAccount() {
        User last = stored(AccessLevel.SOQM, AccessScope.ALL);
        when(userRepository.countActiveByAccessLevel(AccessLevel.SOQM)).thenReturn(1L);

        assertThatThrownBy(() -> update("USER", "ALL", "EDIT", true, ADMIN_ID))
                .hasMessage(UserService.LAST_SOQM_ROLE);
        assertThatThrownBy(() -> update("SOQM_TEAM", "", "", false, ADMIN_ID))
                .hasMessage(UserService.LAST_SOQM_DEACTIVATE);
        assertThat(stored(last)).isEqualTo("SOQM/ALL/true");
        assertThat(last.getEnabled()).isTrue();
        verify(userRepository, never()).save(any(User.class));

        // With a second active member both are allowed
        when(userRepository.countActiveByAccessLevel(AccessLevel.SOQM)).thenReturn(2L);
        assertThat(stored(update("USER", "ALL", "EDIT", true, ADMIN_ID))).isEqualTo("PARTICIPANT/ALL/false");
    }

    @Test
    void anInactiveSoqmTeamMember_isNotTheLastActiveOne() {
        User inactive = stored(AccessLevel.SOQM, AccessScope.ALL);
        inactive.setEnabled(false);
        lenient().when(userRepository.countActiveByAccessLevel(AccessLevel.SOQM)).thenReturn(1L);

        assertThat(stored(update("KDN", "", "", false, ADMIN_ID))).isEqualTo("READ_ONLY/KDN/false");
    }

    @Test
    void updateUser_changesNameEmailAndRoleTogether_beforeTheFirstLogin() {
        stored(AccessLevel.READ_ONLY, AccessScope.OWN);
        when(userRepository.existsByMail("new@example.test")).thenReturn(false);

        User saved = service.updateUser(TARGET_ID, " New@Example.test ", "  Jane Doe ", "KDN", "", "", true, ADMIN_ID);

        assertThat(saved.getMail()).isEqualTo("new@example.test");
        assertThat(saved.getDisplayName()).isEqualTo("Jane Doe");
        assertThat(stored(saved)).isEqualTo("READ_ONLY/KDN/false");
    }

    @Test
    void updateUser_refusedValue_changesNothing() {
        User user = stored(AccessLevel.READ_ONLY, AccessScope.OWN);
        user.setDisplayName("Old Name");
        user.setLastLoginAt(java.time.LocalDateTime.of(2026, 10, 1, 9, 0));

        assertThatThrownBy(() -> service.updateUser(TARGET_ID, "other@example.test", "New Name", "SOQM_TEAM", "", "",
                true, ADMIN_ID))
                .hasMessage("Email can be changed only before the first login");
        assertThatThrownBy(() -> service.updateUser(TARGET_ID, null, "   ", "SOQM_TEAM", "", "", true, ADMIN_ID))
                .hasMessage("Name is required");
        assertThatThrownBy(() -> service.updateUser(TARGET_ID, null, "x".repeat(256), "SOQM_TEAM", "", "", true, ADMIN_ID))
                .hasMessage("Name is too long (255 characters at most)");
        assertThat(user.getMail()).isEqualTo("target@example.test");
        assertThat(user.getDisplayName()).isEqualTo("Old Name");
        assertThat(stored(user)).isEqualTo("READ_ONLY/OWN/false");

        // The same address in another case is no change, also after the first login
        assertThat(service.updateUser(TARGET_ID, "TARGET@example.test", null, "", "", "", true, ADMIN_ID).getMail())
                .isEqualTo("target@example.test");
    }

    @Test
    void createUser_byRole() {
        when(userRepository.existsByMail(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(stored(service.createUser("s@example.test", null, "SOQM_TEAM", null, null, true)))
                .isEqualTo("SOQM/ALL/true");
        assertThat(stored(service.createUser("k@example.test", null, "KDN", "", "", true)))
                .isEqualTo("READ_ONLY/KDN/false");
        assertThat(stored(service.createUser("a@example.test", null, "USER", "ALL", "EDIT", true)))
                .isEqualTo("PARTICIPANT/ALL/false");
        assertThatThrownBy(() -> service.createUser("x@example.test", null, "MASTER", null, null, true))
                .hasMessage("Unknown role: MASTER");
    }
}
