package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The control's history names a workflow status as the status badge does, never by its stored code (2026-11-10). */
class ControlHistoryServiceStatusNameTest {

    @Test
    void aStatusCode_readsAsItsName_anEarlierNameAsTodays() {
        assertThat(ControlHistoryService.statusName("SOQM_HEAD_REVIEW")).isEqualTo("SoQM Admin Team Review");
        assertThat(ControlHistoryService.statusName("PROCESS_OWNER_REVIEW")).isEqualTo("Process Owner Review");
        assertThat(ControlHistoryService.statusName("IN_PROGRESS")).isEqualTo("In Progress");
        assertThat(ControlHistoryService.statusName("SoQM Head Review")).isEqualTo("SoQM Admin Team Review");
        assertThat(ControlHistoryService.statusName("Something else")).isEqualTo("Something else");
        assertThat(ControlHistoryService.statusName("")).isEmpty();
        assertThat(ControlHistoryService.statusName(null)).isNull();
    }
}
