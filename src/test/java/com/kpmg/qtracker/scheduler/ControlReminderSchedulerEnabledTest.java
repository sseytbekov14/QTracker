package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.ReminderNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "reminders.enabled=true")
class ControlReminderSchedulerEnabledTest {

    @Autowired
    private ControlReminderScheduler controlReminderScheduler;

    @MockBean
    private ReminderNotificationService reminderNotificationService;

    @Test
    void runDailyInvokesReminderService() {
        controlReminderScheduler.runDaily();
        verify(reminderNotificationService, times(1)).runDailyReminders(any(LocalDate.class));
    }
}
