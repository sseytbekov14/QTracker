package com.kpmg.qtracker.service;

/**
 * The names of the two steps fields of a control's Details: "Control Steps Performed and Results", written by the
 * Facilitator in In Progress, and "Control Operator's Program". Both are shown to everyone who sees the control,
 * whoever holds the two roles; Control Operator's Program is required by no step. Values are never cleared when
 * the assignment changes; old data and imports have the steps field only, the Program stays NULL.
 */
public final class ControlStepsFields {

    // TODO: BUSINESS CONFIRMATION: the names of both fields (View Control, Excel, history, error messages)
    public static final String STEPS_LABEL = "Control Steps Performed and Results";
    /** The Control Operator's field (column control_operator_review), named so everywhere a person sees it. */
    public static final String OPERATOR_PROGRAM_LABEL = "Control Operator's Program";
    /**
     * The field's name until 2026-10-07. Changelog entries written before then keep it in the database; the
     * Changelog shows them under {@link #OPERATOR_PROGRAM_LABEL} ({@code ControlHistoryService}).
     */
    public static final String FORMER_OPERATOR_REVIEW_LABEL = "Control Operator Review and Results";

    private ControlStepsFields() {
    }
}
