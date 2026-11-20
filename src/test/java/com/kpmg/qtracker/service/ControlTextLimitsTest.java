package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One maximum for every free-text field; long texts are shortened only where they are shown in a small place. */
class ControlTextLimitsTest {

    @Test
    void theMaximum_andShortening() {
        assertThat(ControlTextLimits.MAX).isEqualTo(20000);
        assertThat(ControlTextLimits.shorten("short", 500)).isEqualTo("short");
        assertThat(ControlTextLimits.shorten(null, 500)).isNull();
        String cut = ControlTextLimits.shorten("x".repeat(20000), ControlTextLimits.EMAIL_DESCRIPTION);
        assertThat(cut).hasSize(500).endsWith("…");
        assertThat(ControlTextLimits.shorten("y".repeat(40000), ControlTextLimits.EXCEL_CELL)).hasSize(32767);
    }
}
