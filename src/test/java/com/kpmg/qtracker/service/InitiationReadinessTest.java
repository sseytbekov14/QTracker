package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InitiationReadinessTest {

    @Test
    void fullyAssignedControlWithFrequency_isReady() {
        List<InitiationReadiness.Item> items = InitiationReadiness.items(control("Monthly"), assignment());

        assertThat(items).extracting(InitiationReadiness.Item::label).containsExactly(
                "Facilitator", "Control Operator", "SoQM Team / Delegate", "Process Owner",
                "Control Operation Date", "Control Frequency");
        assertThat(items).allMatch(InitiationReadiness.Item::done);
        assertThat(InitiationReadiness.isReady(items)).isTrue();
    }

    @Test
    void blankAddressesAndMissingFrequency_areNotDone_andPointToTheirTab() {
        ControlAssignmentDTO assignment = assignment();
        assignment.setProcessOwner(List.of(" "));
        assignment.setControlOperationDate(null);

        List<InitiationReadiness.Item> items = InitiationReadiness.items(control(null), assignment);

        assertThat(items).filteredOn(item -> !item.done())
                .extracting(InitiationReadiness.Item::label, InitiationReadiness.Item::tab)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Process Owner", InitiationReadiness.TAB_ASSIGNMENT),
                        org.assertj.core.groups.Tuple.tuple("Control Operation Date", InitiationReadiness.TAB_ASSIGNMENT),
                        org.assertj.core.groups.Tuple.tuple("Control Frequency", InitiationReadiness.TAB_CONTROL));
        assertThat(InitiationReadiness.isReady(items)).isFalse();
    }

    @Test
    void noAssignmentAtAll_missesEveryAssignmentItem() {
        List<InitiationReadiness.Item> items = InitiationReadiness.items(control("Monthly"), null);

        assertThat(items).filteredOn(item -> !item.done()).hasSize(5);
    }

    private Control control(String frequency) {
        Control control = new Control();
        control.setControlFrequency(frequency);
        return control;
    }

    private ControlAssignmentDTO assignment() {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setFacilitator(List.of("fac@kpmg.kz"));
        assignment.setControlOperator(List.of("op@kpmg.kz"));
        assignment.setSoqmLead(List.of("soqm@kpmg.kz"));
        assignment.setProcessOwner(List.of("po@kpmg.kz"));
        assignment.setControlOperationDate(LocalDate.of(2026, 10, 15));
        return assignment;
    }
}
