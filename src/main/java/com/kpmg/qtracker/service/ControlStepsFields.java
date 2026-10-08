package com.kpmg.qtracker.service;

/**
 * The names of the two steps fields of a control's Details: "Control Steps Performed and Results", one field for
 * both steps as in the old system (the Facilitator in In Progress, the Control Operator in Review), and "Control
 * Operator's Program", which the Control Operator sends once and SoQM Team puts in (no one else writes it, no
 * step requires it). Both are shown to everyone who sees the control. Values are never cleared when the
 * assignment changes; old data and imports have the steps field only, the Program stays NULL.
 */
public final class ControlStepsFields {

    // TODO: BUSINESS CONFIRMATION: the names of both fields (View Control, Excel, history, error messages)
    public static final String STEPS_LABEL = "Control Steps Performed and Results";
    /** Column control_operator_review, written by SoQM Team only; named so everywhere a person sees it. */
    public static final String OPERATOR_PROGRAM_LABEL = "Control Operator's Program";
    /**
     * The field's name until 2026-10-07. Changelog entries written before then keep it in the database; the
     * Changelog shows them under {@link #OPERATOR_PROGRAM_LABEL} ({@code ControlHistoryService}).
     */
    public static final String FORMER_OPERATOR_REVIEW_LABEL = "Control Operator Review and Results";

    private ControlStepsFields() {
    }
}
