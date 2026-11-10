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
        assertThat(mapper.display("SOQM_HEAD_REVIEW")).isEqualTo("SoQM Admin Team Review");
        assertThat(mapper.display("PROCESS_OWNER_REVIEW")).isEqualTo("Process Owner Review");
        assertThat(mapper.display("COMPLETED")).isEqualTo("Completed");
    }

    @Test
    void aTextSavedUnderTheEarlierName_readsTodaysName() {
        assertThat(StatusDisplayMapper.withCurrentNames("Move to SoQM Head Review: Review -> SoQM Head Review"))
                .isEqualTo("Move to SoQM Admin Team Review: Review -> SoQM Admin Team Review");
        assertThat(StatusDisplayMapper.withCurrentNames("returned from soqm head review")).isEqualTo("returned from SoQM Admin Team Review");
        assertThat(StatusDisplayMapper.withCurrentNames("Edit Control")).isEqualTo("Edit Control");
        assertThat(StatusDisplayMapper.withCurrentNames("")).isEmpty();
        assertThat(StatusDisplayMapper.withCurrentNames(null)).isNull();
        // "SoQM Head" alone (a role, a comment) is not a status name: left as written
        assertThat(StatusDisplayMapper.withCurrentNames("SoQM Head approved")).isEqualTo("SoQM Head approved");
        // the earlier name as a status: today's name
        assertThat(mapper.display("SoQM Head Review")).isEqualTo("SoQM Admin Team Review");
    }
}


