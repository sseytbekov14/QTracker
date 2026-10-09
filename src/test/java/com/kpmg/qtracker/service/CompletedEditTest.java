package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompletedEditTest {

    private static ControlPermission soqm(boolean completedEdit) {
        return new ControlPermission(true, true, Set.of(), true, true, false, false, false, true, false,
                false, completedEdit);
    }

    @Test
    void reason_isRequired_trimmed_andAtMost500Characters() {
        assertThat(CompletedEdit.reasonRefusal(null)).contains(CompletedEdit.REASON_REQUIRED);
        assertThat(CompletedEdit.reasonRefusal("   ")).contains(CompletedEdit.REASON_REQUIRED);
        assertThat(CompletedEdit.reasonRefusal("x".repeat(500))).isEmpty();
        assertThat(CompletedEdit.reasonRefusal("  " + "x".repeat(500) + "  ")).isEmpty();
        assertThat(CompletedEdit.reasonRefusal("x".repeat(501))).contains(CompletedEdit.REASON_TOO_LONG);
        assertThat(CompletedEdit.clean("  Typo in the steps \n")).isEqualTo("Typo in the steps");
        assertThat(CompletedEdit.clean(" ")).isNull();
    }

    @Test
    void onlyAChangeOfACompletedControlInPlace_needsAReason() {
        assertThat(CompletedEdit.refusal(soqm(true), true, null)).contains(CompletedEdit.REASON_REQUIRED);
        assertThat(CompletedEdit.refusal(soqm(true), true, "Typo")).isEmpty();
        // Nothing changes: nothing to explain
        assertThat(CompletedEdit.refusal(soqm(true), false, null)).isEmpty();
        // Any other save: no reason asked, none recorded
        assertThat(CompletedEdit.refusal(soqm(false), true, null)).isEmpty();
        assertThat(CompletedEdit.refusal(null, true, null)).isEmpty();
        assertThat(CompletedEdit.reasonOf(soqm(false), "Typo")).isNull();
        assertThat(CompletedEdit.reasonOf(soqm(true), " Typo ")).isEqualTo("Typo");
    }

    @Test
    void theAuditEntry_isMarked_andHoldsTheReasonAsItsLastField() {
        assertThat(CompletedEdit.describe("Edit Control")).isEqualTo("Edit Control - Edited after completion");
        assertThat(CompletedEdit.describe(null)).isEqualTo("Edit Control - Edited after completion");
        assertThat(CompletedEdit.isMarked("Attachment DETAILS - Edited after completion")).isTrue();
        assertThat(CompletedEdit.isMarked("Edit Control")).isFalse();
        assertThat(CompletedEdit.isMarked(null)).isFalse();

        List<String> fields = new ArrayList<>(List.of("PRP"));
        Map<String, String> newValues = new LinkedHashMap<>(Map.of("PRP", "New"));
        CompletedEdit.addReason(fields, newValues, " Typo ");
        assertThat(fields).containsExactly("PRP", "Reason");
        assertThat(newValues).containsEntry("Reason", "Typo");
    }
}
