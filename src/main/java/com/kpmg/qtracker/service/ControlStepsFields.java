package com.kpmg.qtracker.service;

import com.kpmg.qtracker.util.EmailList;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The two steps fields of a control's Details: "Control Steps Performed and Results", written by the Facilitator
 * in In Progress, and "Control Operator's Program", written by the Control Operator in Review. Both are shown to
 * everyone who sees the control, whoever holds the two roles; one person who is Facilitator and Control Operator
 * writes the first on the Facilitator's step and the second on their own.
 * <p>
 * Whether the Facilitator and the Control Operator are one person ({@link #onePerson}) decides only one thing:
 * whether Control Operator's Program is required before Submit to SoQM Team ({@link #operatorProgramRequired}).
 * Values are never cleared when the assignment changes; old data and imports have the steps field only, the
 * Program stays NULL.
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

    /**
     * Control Operator's Program is required before Submit to SoQM Team only when the Control Operator is not
     * also a Facilitator (different people). {@code true} makes it required on every control: the server
     * check, the "Your step" hint, the red mark and the Submit button all follow {@link #operatorProgramRequired}.
     */
    // TODO: BUSINESS CONFIRMATION: should Control Operator's Program be required always, one person too?
    public static final boolean OPERATOR_PROGRAM_ALWAYS_REQUIRED = false;

    private ControlStepsFields() {
    }

    /** The one rule for Control Operator's Program being required, given whether F and CO are different people. */
    public static boolean operatorProgramRequired(boolean differentPeople) {
        return OPERATOR_PROGRAM_ALWAYS_REQUIRED || differentPeople;
    }

    /** {@link #operatorProgramRequired(boolean)} for the stored assignment. */
    public static boolean operatorProgramRequired(String facilitators, String controlOperators) {
        return operatorProgramRequired(split(facilitators, controlOperators));
    }

    /**
     * One person: every address in Control Operator is also in Facilitator (ignoring case and spaces,
     * separators "," and ";", see {@link EmailList}). An empty Control Operator counts as one person:
     * there is nobody to write a second field.
     */
    public static boolean onePerson(String facilitators, String controlOperators) {
        for (String operator : EmailList.parse(controlOperators)) {
            if (!EmailList.contains(facilitators, operator)) {
                return false;
            }
        }
        return true;
    }

    /** The same rule for the lists of the assignment DTO; an element may itself hold several addresses. */
    public static boolean onePerson(List<String> facilitators, List<String> controlOperators) {
        return onePerson(join(facilitators), join(controlOperators));
    }

    /** Different people: some Control Operator is not listed as Facilitator. */
    public static boolean split(String facilitators, String controlOperators) {
        return !onePerson(facilitators, controlOperators);
    }

    public static boolean split(List<String> facilitators, List<String> controlOperators) {
        return !onePerson(facilitators, controlOperators);
    }

    private static String join(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(Objects::nonNull).collect(Collectors.joining(";"));
    }
}
