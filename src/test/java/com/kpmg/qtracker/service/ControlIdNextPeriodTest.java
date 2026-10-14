package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.kpmg.qtracker.service.ControlIdGeneratorService.nextPeriodId;
import static org.assertj.core.api.Assertions.assertThat;

/** The Control ID proposed for the next cycle (Create Control): only the period moves on, FY at the year's turn. */
class ControlIdNextPeriodTest {

    @Test
    void businessExamples() {
        assertThat(nextPeriodId("HR-CTRL-MF-3/FY27/Central/OCT", ControlFrequency.MONTHLY))
                .contains("HR-CTRL-MF-3/FY27/Central/NOV");
        assertThat(nextPeriodId("HR-CTRL-MF-3/FY27/Central/1Q", ControlFrequency.QUARTERLY))
                .contains("HR-CTRL-MF-3/FY27/Central/2Q");
    }

    @ParameterizedTest
    @CsvSource({
            "OCT, NOV", "NOV, DEC", "DEC, JAN", "JAN, FEB", "FEB, MAR", "MAR, APR",
            "APR, MAY", "MAY, JUN", "JUN, JUL", "JUL, AUG", "AUG, SEP"})
    void monthly_everyMonthInsideTheFiscalYear(String from, String to) {
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY26/Central/" + from, ControlFrequency.MONTHLY))
                .contains("HR-CTRL-MF-424/FY26/Central/" + to);
    }

    @Test
    void monthly_septemberToOctober_startsTheNextFiscalYear() {
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY26/Central/SEP", ControlFrequency.MONTHLY))
                .contains("HR-CTRL-MF-424/FY27/Central/OCT");
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY99/Central/SEP", ControlFrequency.MONTHLY))
                .contains("HR-CTRL-MF-424/FY00/Central/OCT");
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY09/Central/SEP", ControlFrequency.MONTHLY))
                .contains("HR-CTRL-MF-424/FY10/Central/OCT");
    }

    @ParameterizedTest
    @CsvSource({"1Q, 2Q", "2Q, 3Q", "3Q, 4Q"})
    void quarterly_insideTheFiscalYear(String from, String to) {
        assertThat(nextPeriodId("HR-CTRL-MF-416/FY26/Central/" + from, ControlFrequency.QUARTERLY))
                .contains("HR-CTRL-MF-416/FY26/Central/" + to);
    }

    @Test
    void quarterly_fourthToFirst_startsTheNextFiscalYear() {
        assertThat(nextPeriodId("HR-CTRL-MF-416/FY26/Central/4Q", ControlFrequency.QUARTERLY))
                .contains("HR-CTRL-MF-416/FY27/Central/1Q");
    }

    @Test
    void everythingButThePeriodAndTheYearStaysAsWritten() {
        assertThat(nextPeriodId("KDN-RM-7/FY27/Almaty office/MAR", ControlFrequency.MONTHLY))
                .contains("KDN-RM-7/FY27/Almaty office/APR");
        assertThat(nextPeriodId("  HR-1/fy27/central/sep ", ControlFrequency.MONTHLY))
                .contains("HR-1/fy28/central/oct");
        assertThat(nextPeriodId("HR-1/FY27/Central/2q", ControlFrequency.QUARTERLY)).contains("HR-1/FY27/Central/3q");
        assertThat(nextPeriodId("A/B/C/FY27/D/Jan", ControlFrequency.MONTHLY)).contains("A/B/C/FY27/D/FEB");
    }

    @Test
    void notRecognised_isEmpty() {
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY26/Central", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY26/Central/OCTOBER", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-424/FY26/Central/1Q", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-416/FY26/Central/OCT", ControlFrequency.QUARTERLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-416/FY26/Central/5Q", ControlFrequency.QUARTERLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-416/FY26/Central/Q1", ControlFrequency.QUARTERLY)).isEmpty();
        assertThat(nextPeriodId("OCT", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-424_OCT", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId(null, ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("HR-1/FY26/Central/OCT", null)).isEmpty();
    }

    @Test
    void yearTurn_withoutOneFySegment_isEmpty() {
        // Without the year the ID would repeat the period of a year ago
        assertThat(nextPeriodId("HR-CTRL-MF-424/Central/SEP", ControlFrequency.MONTHLY)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-416/2026/Central/4Q", ControlFrequency.QUARTERLY)).isEmpty();
        assertThat(nextPeriodId("HR-1/FY26/FY27/SEP", ControlFrequency.MONTHLY)).isEmpty();
        // Inside the year no FY segment is needed
        assertThat(nextPeriodId("HR-CTRL-MF-424/Central/OCT", ControlFrequency.MONTHLY))
                .contains("HR-CTRL-MF-424/Central/NOV");
    }

    @ParameterizedTest
    @EnumSource(value = ControlFrequency.class, names = {"ANNUAL", "SEMI_ANNUAL", "RECURRING", "AD_HOC"})
    void otherFrequencies_proposeNothing(ControlFrequency frequency) {
        assertThat(nextPeriodId("HR-CTRL-MF-300/FY26/Central/1H", frequency)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-300/FY26/Central/OCT", frequency)).isEmpty();
        assertThat(nextPeriodId("HR-CTRL-MF-300/FY26/Central/1Q", frequency)).isEmpty();
    }
}
