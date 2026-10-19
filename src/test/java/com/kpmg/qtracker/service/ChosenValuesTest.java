package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ChosenValues: what a save of a field chosen from a list may store, and the "not in list" marks. */
class ChosenValuesTest {

    private static final String LABEL = "Other Related Controls";
    /** The list by key, as the list writes each value. */
    private static final Map<String, String> LIST = Map.of(
            "hr-1/fy27", "HR-1/FY27", "kdn-2", "KDN-2", "ep-3", "EP-3");

    private static ChosenValues.Checked check(String before, String incoming, int max) {
        return ChosenValues.check(LABEL, "control", before, incoming, LIST, max);
    }

    @Test
    void valuesOfTheList_areStoredAsTheListWritesThem_inTheOrderGiven() {
        ChosenValues.Checked checked = check(null, " kdn-2 ;HR-1/FY27;", 50);
        assertThat(checked.refusal()).isNull();
        assertThat(checked.stored()).isEqualTo("KDN-2; HR-1/FY27");
        assertThat(check(null, "KDN - 2", 50).stored()).isEqualTo("KDN-2");
    }

    @Test
    void none_isStoredAsEmpty() {
        assertThat(check("HR-1/FY27", "", 50).stored()).isEmpty();
        assertThat(check("HR-1/FY27", " ; ", 50).stored()).isEmpty();
    }

    @Test
    void aValueNotInTheList_isRefused_unlessTheControlHadItBefore() {
        assertThat(check(null, "HR-1/FY27; OLD-9", 50).refusal())
                .isEqualTo(LABEL + ": OLD-9 is not in the list. Reload the page and choose again.");
        assertThat(check(null, "OLD-9; OLD-8", 50).refusal())
                .isEqualTo(LABEL + ": OLD-9, OLD-8 are not in the list. Reload the page and choose again.");

        // Stored before and no longer in the list: kept as stored, in any case
        ChosenValues.Checked kept = check("Old-9; HR-1/FY27", "old-9; EP-3", 50);
        assertThat(kept.refusal()).isNull();
        assertThat(kept.stored()).isEqualTo("Old-9; EP-3");
    }

    @Test
    void aRepeat_inAnyCase_isRefused() {
        assertThat(check(null, "HR-1/FY27; hr-1/fy27", 50).refusal()).isEqualTo(LABEL + ": hr-1/fy27 is given twice");
        assertThat(check(null, "EP-3;EP - 3", 50).refusal()).isEqualTo(LABEL + ": EP - 3 is given twice");
    }

    @Test
    void moreThanTheLimit_isRefused() {
        assertThat(check(null, "HR-1/FY27; KDN-2; EP-3", 2).refusal()).isEqualTo(LABEL + ": at most 2 controls");
        assertThat(check(null, "HR-1/FY27; KDN-2", 2).refusal()).isNull();
    }

    @Test
    void theMarks_nameTheValuesTheListNoLongerHas() {
        assertThat(ChosenValues.shown("HR-1/FY27; Old-9; kdn-2", LIST)).containsExactly(
                new ChosenValues.Shown("HR-1/FY27", false),
                new ChosenValues.Shown("Old-9", true),
                new ChosenValues.Shown("kdn-2", false));
        assertThat(ChosenValues.shown(null, LIST)).isEmpty();
    }

    @Test
    void theDefaultLimit_is50() {
        assertThat(RelatedControls.DEFAULT_MAX).isEqualTo(50);
    }
}
