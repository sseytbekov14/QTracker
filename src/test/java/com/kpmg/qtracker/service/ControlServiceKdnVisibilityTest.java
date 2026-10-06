package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Control lists through the access policy: scope KDN, scope OWN with drafts, and users who see everything. */
@ExtendWith(MockitoExtension.class)
class ControlServiceKdnVisibilityTest {

    private static final String MAIL = "kdn@kpmg.kz";

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ControlAssignmentRepository controlAssignmentRepository;

    @Mock
    private StatusDisplayMapper statusDisplayMapper;

    private ControlService controlService;

    @BeforeEach
    void setUp() {
        controlService = new ControlService(
                controlRepository,
                userService,
                userRepository,
                controlAssignmentRepository,
                statusDisplayMapper
        );
    }

    @Test
    void kdnScope_seesOnlyTheKdnControlsItIsAssignedToOrSharedWith() {
        Control kdnAssigned = control(3L, "KDN-3001", "IN_PROGRESS");
        Control hrAssigned = control(4L, "HR-3002", "IN_PROGRESS");
        Control kdnShared = control(7L, "kdn-7001", "COMPLETED");
        candidates(List.of(kdnAssigned, hrAssigned, kdnShared),
                List.of(assignment(3L, MAIL, null), assignment(4L, MAIL, null), assignment(7L, null, MAIL)));

        List<Control> visible = controlService.findVisibleControlsForUser(
                TestUsers.user(MAIL, AccessLevel.READ_ONLY, AccessScope.KDN, false));

        assertThat(visible).extracting(Control::getControlId).containsExactly("kdn-7001", "KDN-3001");
    }

    @Test
    void kdnScope_seesEveryIdWithKdnAnywhere_inAnyCase() {
        List<Control> controls = List.of(
                control(31L, "KDN-001", "IN_PROGRESS"),
                control(32L, "KDN001", "REVIEW"),
                control(33L, "X-KDN-12", "COMPLETED"),
                control(34L, "kdn-5", "DRAFT"),
                control(35L, "  KDN-35  ", "REVIEW"),
                control(36L, "HR-36", "REVIEW"),
                control(37L, "KD-N-37", "REVIEW"),
                control(38L, "", "REVIEW"),
                control(39L, null, "REVIEW"));
        candidates(controls, controls.stream().map(c -> assignment(c.getId(), MAIL, null)).toList());

        List<Control> visible = controlService.findVisibleControlsForUser(
                TestUsers.user(MAIL, AccessLevel.READ_ONLY, AccessScope.KDN, false));

        assertThat(visible).extracting(Control::getId).containsExactly(35L, 34L, 33L, 32L, 31L);
    }

    @Test
    void kdnScope_alsoSeesTheKdnControlsItCreated_butNotOthersItCreated() {
        User kdnUser = TestUsers.user(MAIL, AccessLevel.READ_ONLY, AccessScope.KDN, false);
        kdnUser.setId(77L);
        Control kdnCreated = control(20L, "KDN-20", "REVIEW");
        kdnCreated.setCreatedBy(kdnUser);
        Control hrCreated = control(21L, "HR-21", "REVIEW");
        hrCreated.setCreatedBy(kdnUser);
        when(controlRepository.findByCreatedByMailOrderByCreatedAtDesc(MAIL)).thenReturn(List.of(kdnCreated, hrCreated));
        when(controlAssignmentRepository.findAllById(anyIterable())).thenReturn(List.of());
        when(controlRepository.findAllById(anyIterable())).thenReturn(List.of(kdnCreated, hrCreated));

        assertThat(controlService.findVisibleControlsForUser(kdnUser))
                .extracting(Control::getControlId).containsExactly("KDN-20");
    }

    @Test
    void ownScope_seesAssignedAndSharedControls_draftsIncluded() {
        Control draftAssigned = control(10L, "HR-10", "DRAFT");
        Control draftShared = control(11L, "HR-11", null);
        Control running = control(12L, "GOV-12", "REVIEW");
        candidates(List.of(draftAssigned, draftShared, running),
                List.of(assignment(10L, MAIL, null), assignment(11L, null, MAIL), assignment(12L, "b" + MAIL, null)));

        List<Control> visible = controlService.findVisibleControlsForUser(
                TestUsers.user(MAIL, AccessLevel.READ_ONLY, AccessScope.OWN, false));

        // "bkdn@kpmg.kz" contains the address but is someone else
        assertThat(visible).extracting(Control::getControlId).containsExactly("HR-11", "HR-10");
    }

    @Test
    void soqmAdminsAndScopeAll_seeEveryControl() {
        Control kdn = control(1L, "KDN-1", "DRAFT");
        Control hr = control(2L, "HR-2", "COMPLETED");
        when(controlRepository.findAllByOrderByIdDesc()).thenReturn(List.of(hr, kdn));

        for (User user : List.of(
                TestUsers.user("soqm@kpmg.kz", AccessLevel.SOQM, AccessScope.ALL, false),
                TestUsers.user("admin@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, true),
                TestUsers.user("all@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.ALL, false))) {
            assertThat(controlService.findVisibleControlsForUser(user))
                    .as(user.getMail())
                    .extracting(Control::getControlId)
                    .containsExactly("HR-2", "KDN-1");
        }
    }

    @Test
    void disabledUser_seesNothing() {
        User disabled = TestUsers.user("soqm@kpmg.kz", AccessLevel.SOQM, AccessScope.ALL, true);
        disabled.setEnabled(false);
        Control control = control(5L, "HR-5", "REVIEW");
        candidates(List.of(control), List.of(assignment(5L, "soqm@kpmg.kz", null)));

        assertThat(controlService.findVisibleControlsForUser(disabled)).isEmpty();
    }

    private void candidates(List<Control> controls, List<ControlAssignment> assignments) {
        List<Long> ids = controls.stream().map(Control::getId).toList();
        lenient().when(controlAssignmentRepository.findControlIdsByFacilitator(MAIL)).thenReturn(ids);
        lenient().when(controlAssignmentRepository.findControlIdsByFacilitator("soqm@kpmg.kz")).thenReturn(ids);
        when(controlAssignmentRepository.findAllById(anyIterable())).thenReturn(assignments);
        when(controlRepository.findAllById(anyIterable())).thenReturn(controls);
    }

    private Control control(Long id, String controlId, String status) {
        Control control = new Control();
        control.setId(id);
        control.setControlId(controlId);
        control.setPerformanceStatus(status);
        return control;
    }

    private ControlAssignment assignment(Long controlId, String facilitator, String sharedWith) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(controlId);
        assignment.setFacilitator(facilitator);
        assignment.setControlSharedWith(sharedWith);
        return assignment;
    }
}
