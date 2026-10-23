package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlStatusValueTest {

    @Test
    void theFourChoices_anyCaseOrLabel_nothingElse() {
        assertThat(ControlStatusValue.CODES).containsExactly("NOT_APPLICABLE", "ACTIVE", "DELETED", "SUPERSEDED");
        assertThat(ControlStatusValue.canonical(" superseded ")).hasValue("SUPERSEDED");
        assertThat(ControlStatusValue.canonical("Not Applicable")).hasValue("NOT_APPLICABLE");
        assertThat(ControlStatusValue.canonical("Active")).hasValue("ACTIVE");
        assertThat(ControlStatusValue.canonical("ARCHIVED")).isEmpty();
        assertThat(ControlStatusValue.canonical("")).isEmpty();
        assertThat(ControlStatusValue.canonical(null)).isEmpty();
    }

    @Test
    void deletedAndSuperseded_areRetired_withTheirBadge() {
        assertThat(ControlStatusValue.isRetired("DELETED")).isTrue();
        assertThat(ControlStatusValue.isRetired("superseded")).isTrue();
        assertThat(ControlStatusValue.isRetired("ACTIVE")).isFalse();
        assertThat(ControlStatusValue.isRetired(null)).isFalse();
        assertThat(ControlStatusValue.badge("deleted")).isEqualTo("Deleted");
        assertThat(ControlStatusValue.badge("SUPERSEDED")).isEqualTo("Superseded");
        assertThat(ControlStatusValue.badge("NOT_APPLICABLE")).isNull();
        assertThat(ControlStatusValue.invalidMessage())
                .isEqualTo("Control Status must be one of Not Applicable, Active, Deleted, Superseded");
    }
}
