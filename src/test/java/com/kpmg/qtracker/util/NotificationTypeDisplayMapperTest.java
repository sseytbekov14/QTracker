package com.kpmg.qtracker.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationTypeDisplayMapperTest {

    private final NotificationTypeDisplayMapper mapper = new NotificationTypeDisplayMapper();

    @Test
    void mapsMonthlyDay0() {
        NotificationTypeDisplayMapper.Display display = mapper.map("MONTHLY_DAY0", null);
        assertEquals("Control Activated", display.label());
        assertEquals("badge-activated", display.badgeClass());
    }

    @Test
    void mapsMonthlyDay6Reminder2() {
        NotificationTypeDisplayMapper.Display display = mapper.map("MONTHLY_DAY6", null);
        assertEquals("Control Reminder (2)", display.label());
        assertEquals("badge-reminder", display.badgeClass());
    }

    @Test
    void mapsQuarterlyOverdueRepeat() {
        NotificationTypeDisplayMapper.Display display = mapper.map("QUARTERLY_OVERDUE_REPEAT", null);
        assertEquals("Control Deadline Notification", display.label());
        assertEquals("badge-overdue", display.badgeClass());
    }

    @Test
    void mapsWorkflowStepCompletedByTitle() {
        NotificationTypeDisplayMapper.Display display =
                mapper.map("WORKFLOW_STEP", "Control successfully completed!");
        assertEquals("Control Completed", display.label());
        assertEquals("badge-completed", display.badgeClass());
    }

    @Test
    void mapsWorkflowStepSubmittedTitleAsActivated() {
        NotificationTypeDisplayMapper.Display display =
                mapper.map("WORKFLOW_STEP", "Control Submitted by Facilitator");
        assertEquals("Workflow Update", display.label());
        assertEquals("badge-activated", display.badgeClass());
    }

    @Test
    void initiateNotificationsAreVisible() {
        assertEquals(false, mapper.isHiddenType("INITIATE"));
        assertEquals(false, mapper.isHiddenType("CONTROL_INITIATED"));
        assertEquals(false, mapper.isHiddenType("MONTHLY_DAY0"));
        assertTrue(mapper.isHiddenType("DRAFT_INITIATE_REMINDER"));
    }

    @Test
    void mapsInitiateNotifications() {
        NotificationTypeDisplayMapper.Display display = mapper.map("INITIATE", null);
        assertEquals("Control Initiated", display.label());
        assertEquals("badge-activated", display.badgeClass());
    }

    @Test
    void mapsAutoCreated() {
        NotificationTypeDisplayMapper.Display display = mapper.map("CONTROL_AUTO_CREATED", null);
        assertEquals("New Control Created", display.label());
        assertEquals("badge-auto-created", display.badgeClass());
    }
}

