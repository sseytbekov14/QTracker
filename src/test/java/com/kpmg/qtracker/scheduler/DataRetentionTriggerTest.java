package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.DataRetention;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The daily run deletes (apply) as "scheduler", and a refusal or a failure does not stop the application. */
class DataRetentionTriggerTest {

    private final DataRetention retention = mock(DataRetention.class);
    private final DataRetentionTrigger trigger = new DataRetentionTrigger(retention);

    @Test
    void theDailyRun_deletes_asTheScheduler() {
        when(retention.run(true, "scheduler")).thenReturn(new DataRetention.Report(true, null, 7, LocalDateTime.now(),
                List.of(), Map.of("controls", 0), 0, List.of(), 0, 0));

        trigger.run();

        verify(retention).run(true, "scheduler");
    }

    @Test
    void aRefusalOrAFailure_isLogged_notThrown() {
        when(retention.run(true, "scheduler")).thenReturn(new DataRetention.Report(true, "too few years", 6,
                LocalDateTime.now(), List.of(), Map.of(), 0, List.of(), 0, 0));
        assertThatCode(trigger::run).doesNotThrowAnyException();

        when(retention.run(true, "scheduler")).thenThrow(new IllegalStateException("database down"));
        assertThatCode(trigger::run).doesNotThrowAnyException();
    }
}
