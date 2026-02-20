package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.ReminderNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = "reminders.enabled=false")
class ControlReminderSchedulerDisabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @MockBean
    private ReminderNotificationService reminderNotificationService;

    @Test
    void schedulerBeanNotCreatedWhenDisabled() {
        assertTrue(applicationContext.getBeansOfType(ControlReminderScheduler.class).isEmpty());
        verifyNoInteractions(reminderNotificationService);
    }
}

