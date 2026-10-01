package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;

import java.util.ArrayList;
import java.util.List;

/**
 * What a draft needs before it can be initiated. The Initiate page lists these items, each with the
 * View Control tab that holds the field; the Initiate endpoint refuses a control that misses any of
 * them or the SoQM Year.
 */
public final class InitiationReadiness {

    public static final String TAB_CONTROL = "control";
    public static final String TAB_ASSIGNMENT = "assignment";

    /**
     * One line of the checklist: the field (its name on View Control), its label, the View Control tab
     * it is on, and whether it is filled in.
     */
    public record Item(String field, String label, String tab, boolean done) {
    }

    private InitiationReadiness() {
    }

    public static List<Item> items(Control control, ControlAssignmentDTO assignment) {
        ControlAssignmentDTO a = assignment != null ? assignment : new ControlAssignmentDTO();
        return List.of(
                new Item("facilitator", "Facilitator", TAB_ASSIGNMENT, hasAny(a.getFacilitator())),
                new Item("controlOperator", "Control Operator", TAB_ASSIGNMENT, hasAny(a.getControlOperator())),
                new Item("soqmLead", "SoQM Team / Delegate", TAB_ASSIGNMENT, hasAny(a.getSoqmLead())),
                new Item("processOwner", "Process Owner", TAB_ASSIGNMENT, hasAny(a.getProcessOwner())),
                new Item("controlOperationDate", "Control Operation Date", TAB_ASSIGNMENT,
                        a.getControlOperationDate() != null),
                new Item("controlFrequency", "Control Frequency", TAB_CONTROL,
                        control != null && hasText(control.getControlFrequency())));
    }

    public static boolean isReady(List<Item> items) {
        return items.stream().allMatch(Item::done);
    }

    /** Labels of everything still missing, the SoQM Year last; empty when the control can be initiated. */
    public static List<String> missing(Control control, ControlAssignmentDTO assignment, String soqmYear) {
        List<String> missing = new ArrayList<>();
        for (Item item : items(control, assignment)) {
            if (!item.done()) {
                missing.add(item.label());
            }
        }
        if (!hasText(soqmYear)) {
            missing.add("SoQM Year");
        }
        return missing;
    }

    private static boolean hasAny(List<String> emails) {
        return emails != null && emails.stream().anyMatch(InitiationReadiness::hasText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
