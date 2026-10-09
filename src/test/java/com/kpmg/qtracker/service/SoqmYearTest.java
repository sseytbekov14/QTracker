package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SoqmYearTest {

    @Test
    void newSoqmYearStartsOnFirstOctober() {
        assertThat(SoqmYear.current(LocalDate.of(2026, 9, 30))).isEqualTo("1 OCT 2025 - 30 SEP 2026");
        assertThat(SoqmYear.current(LocalDate.of(2026, 10, 1))).isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(SoqmYear.current(LocalDate.of(2027, 1, 15))).isEqualTo("1 OCT 2026 - 30 SEP 2027");
    }

    @Test
    void optionsAreTwoYearsBackToOneYearAhead() {
        assertThat(SoqmYear.options(LocalDate.of(2026, 10, 1))).containsExactly(
                "1 OCT 2024 - 30 SEP 2025",
                "1 OCT 2025 - 30 SEP 2026",
                "1 OCT 2026 - 30 SEP 2027",
                "1 OCT 2027 - 30 SEP 2028");
    }

    @Test
    void preselected_keepsAListedStoredYear_otherwiseTakesTheCurrentOne() {
        LocalDate today = LocalDate.of(2026, 10, 1);

        assertThat(SoqmYear.preselected("1 OCT 2025 - 30 SEP 2026", today)).isEqualTo("1 OCT 2025 - 30 SEP 2026");
        assertThat(SoqmYear.preselected(null, today)).isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(SoqmYear.preselected("FY21", today)).isEqualTo("1 OCT 2026 - 30 SEP 2027");
    }

    @Test
    void isValid_acceptsOnlyConsecutiveOctoberToSeptemberYears() {
        assertThat(SoqmYear.isValid("1 OCT 2031 - 30 SEP 2032")).isTrue();
        assertThat(SoqmYear.isValid(" 1 OCT 2026 - 30 SEP 2027 ")).isTrue();
        assertThat(SoqmYear.isValid("1 OCT 2026 - 30 SEP 2028")).isFalse();
        assertThat(SoqmYear.isValid("2026-27")).isFalse();
        assertThat(SoqmYear.isValid("2031")).isFalse();
        assertThat(SoqmYear.isValid(null)).isFalse();
    }
}
