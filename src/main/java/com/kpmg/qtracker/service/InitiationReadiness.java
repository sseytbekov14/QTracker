package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;

import java.util.List;

/**
 * What a draft needs before it can be initiated. View Control lists these items above the tabs,
 * each with the tab that holds the field.
 */
public final class InitiationReadiness {

    public static final String TAB_CONTROL = "control";
    public static final String TAB_ASSIGNMENT = "assignment";

    /** One line of the checklist: the field, the View Control tab it is on, and whether it is filled in. */
    public record Item(String label, String tab, boolean done) {
    }

    private InitiationReadiness() {
    }

    public static List<Item> items(Control control, ControlAssignmentDTO assignment) {
        ControlAssignmentDTO a = assignment != null ? assignment : new ControlAssignmentDTO();
        return List.of(
                new Item("Facilitator", TAB_ASSIGNMENT, hasAny(a.getFacilitator())),
                new Item("Control Operator", TAB_ASSIGNMENT, hasAny(a.getControlOperator())),
                new Item("SoQM Team / Delegate", TAB_ASSIGNMENT, hasAny(a.getSoqmLead())),
                new Item("Process Owner", TAB_ASSIGNMENT, hasAny(a.getProcessOwner())),
                new Item("Control Operation Date", TAB_ASSIGNMENT, a.getControlOperationDate() != null),
                new Item("Control Frequency", TAB_CONTROL, control != null && hasText(control.getControlFrequency())));
    }

    public static boolean isReady(List<Item> items) {
        return items.stream().allMatch(Item::done);
    }

    private static boolean hasAny(List<String> emails) {
        return emails != null && emails.stream().anyMatch(InitiationReadiness::hasText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
