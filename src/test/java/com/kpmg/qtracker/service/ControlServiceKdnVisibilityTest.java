package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlServiceKdnVisibilityTest {

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
    void kdnRoleSeesOnlyControlsWithKdnPrefix() {
        Control kdnControl = new Control();
        kdnControl.setId(3L);
        kdnControl.setControlId("KDN-3001");

        Control hrControl = new Control();
        hrControl.setId(4L);
        hrControl.setControlId("HR-3002");

        when(userRepository.findByMail("kdn@kpmg.kz")).thenReturn(Optional.empty());
        when(controlRepository.findAllByOrderByIdDesc()).thenReturn(List.of(hrControl, kdnControl));

        List<Control> visible = controlService.findVisibleControlsForUser("kdn@kpmg.kz", "KDN");

        assertThat(visible).extracting(Control::getControlId).containsExactly("KDN-3001");
    }

    @Test
    void secondaryKdnRoleAlsoGetsKdnOnlyVisibility() {
        User user = new User();
        user.setMail("mixed@kpmg.kz");
        user.setRole("FACILITATOR");
        user.setSecondaryRole("KDN");

        Control kdnControl = new Control();
        kdnControl.setId(5L);
        kdnControl.setControlId("KDN-5001");

        Control govControl = new Control();
        govControl.setId(6L);
        govControl.setControlId("GOV-5002");

        when(userRepository.findByMail("mixed@kpmg.kz")).thenReturn(Optional.of(user));
        when(controlRepository.findAllByOrderByIdDesc()).thenReturn(List.of(govControl, kdnControl));

        List<Control> visible = controlService.findVisibleControlsForUser("mixed@kpmg.kz", "FACILITATOR");

        assertThat(visible).extracting(Control::getControlId).containsExactly("KDN-5001");
    }
}
