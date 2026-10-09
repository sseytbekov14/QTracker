package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditTrailTest {

    @Mock
    private AdminAuditLogRepository repository;

    @InjectMocks
    private AdminAuditTrail trail;

    private static AdminAuditLog log(String type, String description, String newValues, String controlId, int minute) {
        AdminAuditLog log = new AdminAuditLog();
        log.setActionType(type);
        log.setActionDescription(description);
        log.setNewValues(newValues);
        log.setControlControlId(controlId);
        log.setAdminEmail("admin@kpmg.kz");
        log.setAdminName("Admin User");
        log.setCreatedAt(LocalDateTime.of(2026, 10, 2, 12, minute));
        return log;
    }

    @Test
    void accessUpdate_targetFromTheDescription_actionWithoutIt() {
        AdminAuditTrail.Entry entry = AdminAuditTrail.entry(log("USER_ACCESS_UPDATE",
                "Changed role from FACILITATOR to PROCESS_OWNER; Changed status from ACTIVE to INACTIVE for a.b@kpmg.kz",
                "role=PROCESS_OWNER, secondaryRole=null, adminAccess=false, enabled=false", null, 0));

        assertThat(entry.group()).isEqualTo(AdminAuditTrail.Group.USER_ACCESS);
        assertThat(entry.target()).isEqualTo("a.b@kpmg.kz");
        assertThat(entry.targetIsUser()).isTrue();
        assertThat(entry.action()).isEqualTo("Changed role from FACILITATOR to PROCESS_OWNER; Changed status from ACTIVE to INACTIVE");
        assertThat(entry.changedBy()).isEqualTo("Admin User");
    }

    @Test
    void createAndEmailChange_targetsTheirUser() {
        AdminAuditTrail.Entry created = AdminAuditTrail.entry(log("USER_CREATE", "Created user new@kpmg.kz",
                "mail=new@kpmg.kz, displayName=New, role=KDN, adminAccess=false, enabled=true", null, 0));
        AdminAuditTrail.Entry email = AdminAuditTrail.entry(log("USER_EMAIL_UPDATE",
                "Changed email from old@kpmg.kz to new@kpmg.kz for user id 12", "mail=new@kpmg.kz", null, 0));

        assertThat(created.target()).isEqualTo("new@kpmg.kz");
        assertThat(created.action()).isEqualTo("Created user");
        assertThat(email.target()).isEqualTo("new@kpmg.kz");
        assertThat(email.action()).isEqualTo("Changed email from old@kpmg.kz to new@kpmg.kz");
    }

    @Test
    void controlEvents_targetTheControl() {
        AdminAuditTrail.Entry edit = AdminAuditTrail.entry(log("EDIT", "Updated control", null, "CTRL-001", 0));
        AdminAuditTrail.Entry attachment = AdminAuditTrail.entry(log("ATTACHMENT_ADDED", "Added file to Details", null, "CTRL-002", 0));
        AdminAuditTrail.Entry other = AdminAuditTrail.entry(log("VIEW", null, null, null, 0));

        assertThat(edit.group()).isEqualTo(AdminAuditTrail.Group.CONTROL_EDIT);
        assertThat(edit.target()).isEqualTo("CTRL-001");
        assertThat(edit.targetIsUser()).isFalse();
        assertThat(attachment.group()).isEqualTo(AdminAuditTrail.Group.ATTACHMENT);
        assertThat(other.group()).isEqualTo(AdminAuditTrail.Group.OTHER);
        assertThat(other.action()).isEqualTo("VIEW");
        assertThat(other.target()).isNull();
    }

    @Test
    void latest_mergesTheTypesNewestFirst_andCountsThem() {
        when(repository.findTop100ByActionTypeStartingWithOrderByCreatedAtDesc("USER_"))
                .thenReturn(List.of(log("USER_CREATE", "Created user x@kpmg.kz", null, null, 30)));
        when(repository.findTop100ByActionTypeOrderByCreatedAtDesc("EDIT"))
                .thenReturn(List.of(log("EDIT", "Updated", null, "CTRL-1", 40), log("EDIT", "Updated", null, "CTRL-2", 10)));
        when(repository.findTop100ByActionTypeStartingWithOrderByCreatedAtDesc("ATTACHMENT_")).thenReturn(List.of());
        when(repository.findOtherActionTypes(any())).thenReturn(List.of());

        AdminAuditTrail.Trail result = trail.latest();

        assertThat(result.entries()).extracting(AdminAuditTrail.Entry::target)
                .containsExactly("CTRL-1", "x@kpmg.kz", "CTRL-2");
        assertThat(result.counts()).containsEntry(AdminAuditTrail.Group.USER_ACCESS, 1)
                .containsEntry(AdminAuditTrail.Group.CONTROL_EDIT, 2)
                .containsEntry(AdminAuditTrail.Group.ATTACHMENT, 0)
                .containsEntry(AdminAuditTrail.Group.OTHER, 0);
    }
}
