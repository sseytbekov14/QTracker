package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.DeadlineRecalculation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadlineRecalculationCommandTest {

    // 19:30 UTC on 7 October is already 8 October in Almaty
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T19:30:00Z"), ZoneId.of("UTC"));
    private final DeadlineRecalculation recalculation = mock(DeadlineRecalculation.class);
    private final DeadlineRecalculationCommand command = new DeadlineRecalculationCommand(recalculation, clock);

    @Test
    void requested_onlyWithTheOption() {
        assertThat(DeadlineRecalculationCommand.requested("--recalculate-deadlines")).isTrue();
        assertThat(DeadlineRecalculationCommand.requested("--server.port=8080", "--recalculate-deadlines", "--apply")).isTrue();
        assertThat(DeadlineRecalculationCommand.requested("--apply")).isFalse();
        assertThat(DeadlineRecalculationCommand.requested()).isFalse();
        assertThat(DeadlineRecalculationCommand.requested((String[]) null)).isFalse();
    }

    @Test
    void anOrdinaryStart_runsNothing() {
        command.run(new DefaultApplicationArguments("--apply"));

        verify(recalculation, never()).run(anyBoolean(), any(), any());
    }

    @Test
    void dryRunByDefault_applyOnlyWithTheFlag_onTheAlmatyDay() {
        when(recalculation.run(anyBoolean(), any(), any())).thenReturn(report());

        command.run(new DefaultApplicationArguments("--recalculate-deadlines"));
        verify(recalculation).run(eq(false), eq(LocalDate.of(2026, 10, 8)), any());

        command.run(new DefaultApplicationArguments("--recalculate-deadlines", "--apply"));
        verify(recalculation).run(eq(true), eq(LocalDate.of(2026, 10, 8)), any());
    }

    private static DeadlineRecalculation.Report report() {
        return new DeadlineRecalculation.Report(false, LocalDate.of(2026, 10, 8), 0, 0, 0, List.of(), List.of());
    }
}
