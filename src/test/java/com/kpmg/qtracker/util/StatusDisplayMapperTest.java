package com.kpmg.qtracker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusDisplayMapperTest {

    private final StatusDisplayMapper mapper = new StatusDisplayMapper();

    @Test
    void mapsInternalStatusToDisplayLabel() {
        assertThat(mapper.display("DRAFT")).isEqualTo("Draft");
        assertThat(mapper.display("IN_PROGRESS")).isEqualTo("In Progress");
        assertThat(mapper.display("REVIEW")).isEqualTo("Review");
        assertThat(mapper.display("SOQM_HEAD_REVIEW")).isEqualTo("SoQM Head Review");
        assertThat(mapper.display("PROCESS_OWNER_REVIEW")).isEqualTo("Process Owner Review");
        assertThat(mapper.display("COMPLETED")).isEqualTo("Completed");
    }
}

