package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The snapshot of a deactivated control as the Admin Panel shows it, line by line. */
class DeactivationSnapshotTest {

    @Test
    void theSnapshot_readsAsLines_inTheirOrder() {
        String json = "{\"id\":42,\"controlId\":\"HR-1/FY27\",\"description\":\"Test the Tuesday report\","
                + "\"component\":\"HR\",\"frequency\":\"Monthly\",\"performanceStatus\":\"SOQM_HEAD_REVIEW\","
                + "\"controlStatus\":\"ACTIVE\",\"soqmYear\":\"2026-2027\",\"operationDate\":\"2026-11-02\","
                + "\"deadline\":\"2026-11-16\",\"createdAt\":\"2026-10-01T09:30:12.345\",\"createdBy\":\"s@kpmg.kz\","
                + "\"roles\":{\"Facilitator\":[{\"email\":\"a@kpmg.kz\",\"name\":\"Anna Smith\"}],"
                + "\"Control Operator\":[{\"email\":\"b@kpmg.kz\"}],\"SoQM Admin Team\":[],"
                + "\"Process Owner\":[{\"email\":\"c@kpmg.kz\",\"name\":\"C\"},{\"email\":\"d@kpmg.kz\",\"name\":\"D\"}],"
                + "\"Control Shared With\":[]},\"files\":[\"a.pdf\",\"b.xlsx\"],\"deactivatedBy\":\"s@kpmg.kz\"}";

        assertThat(DeactivationSnapshot.lines(json)).containsExactly(
                "Control ID: HR-1/FY27",
                "Description: Test the Tuesday report",
                "Component: HR",
                "Frequency: Monthly",
                "Status: SoQM Head Review",
                "Control Status: ACTIVE",
                "SoQM Year: 2026-2027",
                "Control Operation Date: 2026-11-02",
                "Deadline: 2026-11-16",
                "Created: 2026-10-01 09:30",
                "Created by: s@kpmg.kz",
                "Database id: 42",
                "Facilitator: Anna Smith (a@kpmg.kz)",
                "Control Operator: b@kpmg.kz",
                "SoQM Admin Team: -",
                "Process Owner: C (c@kpmg.kz); D (d@kpmg.kz)",
                "Control Shared With: -",
                "Files: 2 (a.pdf, b.xlsx)");
    }

    @Test
    void emptyOrBrokenText_givesNoLinesOrOneNote_neverAnError() {
        assertThat(DeactivationSnapshot.lines(null)).isEmpty();
        assertThat(DeactivationSnapshot.lines("  ")).isEmpty();
        assertThat(DeactivationSnapshot.lines("{not json")).containsExactly("Snapshot: not readable");
        assertThat(DeactivationSnapshot.lines("[1,2]")).containsExactly("Snapshot: not readable");
        assertThat(DeactivationSnapshot.lines("{\"controlId\":\"X\",\"files\":[]}"))
                .containsExactly("Control ID: X", "Files: -");
    }
}
