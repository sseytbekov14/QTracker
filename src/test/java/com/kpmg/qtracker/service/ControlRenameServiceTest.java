package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlRenameServiceTest {

    @Mock
    private ControlRepository controlRepository;
    @Mock
    private ControlAssignmentService controlAssignmentService;
    @Mock
    private IControlService controlService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService adminAuditService;
    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;
    @Mock
    private FileStorageService fileStorageService;

    private ControlRenameService service;
    private final User soqm = TestUsers.user("soqm@kpmg.kz", AccessLevel.SOQM, AccessScope.ALL, false);
    private final User kdn = TestUsers.user("kdn@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, false);
    private final User part = TestUsers.user("part@kpmg.kz", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
    /** A KDN user on no field of the control: KDN users see every KDN control. */
    private final User otherKdn = TestUsers.user("other.kdn@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, false);
    private final User disabledKdn = TestUsers.user("off.kdn@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, false);
    private Control control;

    @BeforeEach
    void setUp() {
        ControlPermissionService permissionService = new ControlPermissionService(controlService, controlAssignmentService);
        service = new ControlRenameService(controlRepository, controlAssignmentService, permissionService,
                userRepository, adminAuditService, adminAuditLogRepository, fileStorageService);
        control = new Control();
        control.setId(5L);
        control.setControlId("KDN-5");
        control.setPerformanceStatus("REVIEW");
        lenient().when(controlRepository.findById(5L)).thenReturn(Optional.of(control));
        lenient().when(controlRepository.save(any(Control.class))).thenAnswer(inv -> inv.getArgument(0));
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of("KDN@kpmg.kz"));
        assignment.setControlOperator(List.of("part@kpmg.kz"));
        lenient().when(controlAssignmentService.getAssignmentByControlId(5L)).thenReturn(assignment);
        disabledKdn.setEnabled(false);
        lenient().when(userRepository.findAll()).thenReturn(List.of(part, otherKdn, soqm, disabledKdn, kdn));
    }

    @Test
    void kdnChange_withoutComment_isRefused_andNothingIsSaved() {
        assertThatThrownBy(() -> service.rename(5L, "HR-5", "  ", soqm))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A comment is required: the new Control ID makes this control no longer a KDN control;"
                        + " KDN users who will no longer see it: kdn@kpmg.kz, other.kdn@kpmg.kz");
        verify(controlRepository, never()).save(any());
        verify(adminAuditService, never()).logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusedRename_movesNoAttachments() throws Exception {
        assertThatThrownBy(() -> service.rename(5L, "HR-5", null, soqm)).isInstanceOf(IllegalArgumentException.class);
        verify(fileStorageService, never()).moveControlFolder(any(), any());
    }

    @Test
    void rename_movesTheAttachmentFolder_toTheNewId() throws Exception {
        when(adminAuditService.logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AdminAuditLog());
        service.rename(5L, "KDN-5/FY26/KZ", null, soqm);
        verify(fileStorageService).moveControlFolder("KDN-5", "KDN-5/FY26/KZ");
    }

    @Test
    void rename_isKept_whenTheFolderCannotBeMoved() throws Exception {
        when(adminAuditService.logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AdminAuditLog());
        doThrow(new java.io.IOException("locked")).when(fileStorageService).moveControlFolder(any(), any());
        assertThat(service.rename(5L, "KDN-6", null, soqm).getControlId()).isEqualTo("KDN-6");
    }

    @Test
    void attachmentFolders_areTheCurrentId_thenEarlierIdsFromTheRenameAudit_newestFirst() {
        control.setControlId("KDN");
        when(adminAuditLogRepository.findByControlIdAndActionTypeOrderByCreatedAtDesc(5L, ControlRenameService.AUDIT_ACTION))
                .thenReturn(List.of(audit("{\"Control ID\":\"HR-CTRL-MF-5/FY26/Central/2H\",\"KDN control\":\"No\"}"),
                        audit("{\"Control ID\":\"HR-5\"}"),
                        audit("{\"Control ID\":\"KDN\"}"),
                        audit("not json"),
                        audit(null)));

        assertThat(service.attachmentFolders(control))
                .containsExactly("KDN", "HR-CTRL-MF-5/FY26/Central/2H", "HR-5");
    }

    @Test
    void attachmentFolders_ofAControlWithoutId_isItsDatabaseId() {
        control.setControlId(" ");
        assertThat(service.attachmentFolders(control)).containsExactly("5");
    }

    private static AdminAuditLog audit(String previousValues) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setPreviousValues(previousValues);
        return entry;
    }

    @Test
    void kdnChange_whoseAuditCannotBeWritten_isNotKept() {
        when(adminAuditService.logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        assertThatThrownBy(() -> service.rename(5L, "HR-5", "Local control", soqm))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherRename_isAudited_andTrimmed() {
        when(adminAuditService.logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AdminAuditLog());
        Control saved = service.rename(5L, "  kdn-6 ", null, soqm);

        assertThat(saved.getControlId()).isEqualTo("kdn-6");
        verify(adminAuditService).logActionWithChanges(eq("soqm@kpmg.kz"), any(), eq(ControlRenameService.AUDIT_ACTION),
                eq(control), eq("Control ID renamed: KDN-5 -> kdn-6"), eq("[\"Control ID\"]"),
                eq("{\"Control ID\":\"KDN-5\"}"), eq("{\"Control ID\":\"kdn-6\"}"));
    }

    @Test
    void sameIdOrIdInUse() {
        assertThat(service.rename(5L, " KDN-5 ", null, soqm)).isSameAs(control);
        when(controlRepository.existsByControlId("KDN-6")).thenReturn(true);
        assertThatThrownBy(() -> service.rename(5L, "KDN-6", null, soqm))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.rename(5L, " ", null, soqm))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Control ID cannot be empty");
        verify(adminAuditService, never()).logActionWithChanges(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void kdnAccessChange_namesEveryActiveKdnUser_onTheControlOrNot() {
        ControlRenameService.KdnAccessChange losing = service.kdnAccessChange(control, false);
        assertThat(losing.losing()).containsExactly("kdn@kpmg.kz", "other.kdn@kpmg.kz");
        assertThat(losing.gaining()).isEmpty();

        control.setControlId("HR-5");
        ControlRenameService.KdnAccessChange gaining = service.kdnAccessChange(control, true);
        assertThat(gaining.gaining()).containsExactly("kdn@kpmg.kz", "other.kdn@kpmg.kz");
        assertThat(gaining.losing()).isEmpty();
    }

    @Test
    void preview_namesWhoLosesOrGainsAccess_andSavesNothing() {
        ControlRenameService.RenamePreview stops = service.preview(control, " HR-5 ");
        assertThat(stops.kdnChange()).isTrue();
        assertThat(stops.becomesKdn()).isFalse();
        assertThat(stops.losing()).containsExactly("kdn@kpmg.kz", "other.kdn@kpmg.kz");
        assertThat(stops.title()).isEqualTo("This control will stop being a KDN control");
        assertThat(stops.explanation()).contains("2 KDN users will lose access", "A comment is required", "audit log");

        control.setControlId("HR-5");
        ControlRenameService.RenamePreview becomes = service.preview(control, "kdn-5");
        assertThat(becomes.kdnChange()).isTrue();
        assertThat(becomes.becomesKdn()).isTrue();
        assertThat(becomes.gaining()).containsExactly("kdn@kpmg.kz", "other.kdn@kpmg.kz");
        assertThat(becomes.explanation()).startsWith("The new ID starts with KDN, so every KDN user will see this control");

        // KDN further on in the ID, or no ID: nothing to confirm
        assertThat(service.preview(control, "HR-KDN-5").kdnChange()).isFalse();
        assertThat(service.preview(control, "  ").kdnChange()).isFalse();
        verify(controlRepository, never()).save(any());
        verify(adminAuditService, never()).logActionWithChanges(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
