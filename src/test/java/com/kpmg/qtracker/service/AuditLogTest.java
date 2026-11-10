package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.AdminAuditLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The Audit trail's groups, names and before / after of the stored entries. */
class AuditLogTest {

    private final AuditLog auditLog = new AuditLog(null, mock(ControlHistoryService.class), null);

    @Test
    void everyStoredType_fallsInOneOfThreeGroups() {
        for (String type : List.of("USER_CREATE", "USER_ACCESS_UPDATE", "USER_EMAIL_UPDATE", "USER_DELETED",
                "USER_DELETE", "USER_SSO_LINKED", "USER_SSO_REFUSED")) {
            assertThat(AuditLog.Group.of(type)).as(type).isEqualTo(AuditLog.Group.USERS);
        }
        for (String type : List.of("EDIT", "RENAME_ID", "WORKFLOW_MOVE", "ATTACHMENT_ADDED", "ATTACHMENT_REMOVED",
                "ATTACHMENT_HIDDEN", "ATTACHMENT_REPLACED", "CONTROL_DEACTIVATED", "CONTROL_INITIATED")) {
            assertThat(AuditLog.Group.of(type)).as(type).isEqualTo(AuditLog.Group.CONTROLS);
        }
        for (String type : List.of("DATA_RETENTION_PURGE", "RELATED_CONTROLS_IMPORT", "DATA_INTEGRITY_ERROR", "VIEW", "")) {
            assertThat(AuditLog.Group.of(type)).as(type).isEqualTo(AuditLog.Group.SYSTEM);
        }
        assertThat(AuditLog.Group.of(null)).isEqualTo(AuditLog.Group.SYSTEM);
    }

    @Test
    void theTypes_readAsActions() {
        assertThat(AuditLog.label("USER_ACCESS_UPDATE")).isEqualTo("Access changed");
        assertThat(AuditLog.label("USER_DELETE")).isEqualTo("User deleted");
        assertThat(AuditLog.label("EDIT")).isEqualTo("Control edited");
        assertThat(AuditLog.label("WORKFLOW_MOVE")).isEqualTo("Workflow step");
        assertThat(AuditLog.label("ATTACHMENT_ADDED")).isEqualTo("File uploaded");
        assertThat(AuditLog.label("ATTACHMENT_HIDDEN")).isEqualTo("File hidden");
        assertThat(AuditLog.label("CONTROL_DEACTIVATED")).isEqualTo("Control deactivated");
        assertThat(AuditLog.label("DATA_RETENTION_PURGE")).isEqualTo("Data retention");
        assertThat(AuditLog.label("SOME_NEW_EVENT")).isEqualTo("Some new event");
        assertThat(AuditLog.label(null)).isEqualTo("Event");
        // Every action of the filter is in one group and has a name
        for (AuditLog.Action action : AuditLog.ACTIONS) {
            assertThat(action.label()).as(action.key()).isNotBlank();
            assertThat(action.group()).as(action.key()).isNotNull();
        }
    }

    @Test
    void userChanges_readAsFieldBeforeAndAfter_alsoWithACommaInAName() {
        Map<String, String> pairs = auditLog.pairs("name=Smith, Anna, role=USER, visibility=MY");
        assertThat(pairs.keySet()).containsExactly("name", "role", "visibility");
        assertThat(pairs).containsEntry("name", "Smith, Anna").containsEntry("role", "USER").containsEntry("visibility", "MY");
        assertThat(auditLog.pairs("{\"mail\":\"a@kpmg.kz\"}")).containsEntry("mail", "a@kpmg.kz");
        assertThat(auditLog.pairs("-")).isEmpty();
        assertThat(auditLog.pairs(null)).isEmpty();

        AdminAuditLog log = new AdminAuditLog();
        log.setActionType("USER_ACCESS_UPDATE");
        log.setPreviousValues("role=USER, status=Active");
        log.setNewValues("role=SOQM_TEAM, status=Inactive");
        assertThat(auditLog.changes(log, AuditLog.Group.USERS)).containsExactly(
                new AuditLog.Change("Role", "USER", "SOQM_TEAM"), new AuditLog.Change("Status", "Active", "Inactive"));
    }

    @Test
    void aDeactivatedControl_showsItsSnapshot_line_by_line() {
        AdminAuditLog log = new AdminAuditLog();
        log.setActionType(ControlDeactivation.AUDIT_ACTION);
        log.setNewValues("{\"controlId\":\"HR-1\",\"performanceStatus\":\"REVIEW\",\"roles\":{\"Facilitator\":"
                + "[{\"email\":\"a@kpmg.kz\",\"name\":\"Anna\"}]},\"files\":[]}");
        assertThat(auditLog.changes(log, AuditLog.Group.CONTROLS)).containsExactly(
                new AuditLog.Change("Control ID", "", "HR-1"), new AuditLog.Change("Status", "", "Review"),
                new AuditLog.Change("Facilitator", "", "Anna (a@kpmg.kz)"), new AuditLog.Change("Files", "", "-"));
    }

    @Test
    void anEntryWithNothingRecorded_hasNoDetails() {
        AdminAuditLog log = new AdminAuditLog();
        log.setActionType("USER_SSO_LINKED");
        assertThat(auditLog.changes(log, AuditLog.Group.USERS)).isEmpty();
    }

    @Test
    void wordsThatOnlyRepeatTheAction_areLeftOut() {
        assertThat(AuditLog.repeatsTheAction("Edit Control", "Control edited")).isTrue();
        assertThat(AuditLog.repeatsTheAction("Created user", "User created")).isTrue();
        assertThat(AuditLog.repeatsTheAction("  ", "Workflow step")).isTrue();
        assertThat(AuditLog.repeatsTheAction("workflow step", "Workflow step")).isTrue();
        assertThat(AuditLog.repeatsTheAction("Changed role from User to SoQM Admin Team", "Access changed")).isFalse();
        assertThat(AuditLog.repeatsTheAction("Edit Control - Edited after completion", "Control edited")).isFalse();
    }
}
