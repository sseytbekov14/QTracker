package com.kpmg.qtracker.service;

import com.kpmg.qtracker.util.EmailList;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * "Control Steps Performed and Results" as one field or two, decided by the assignment. The server
 * (rights, required fields, Excel), the View Control page and view-control.js take the mode from here.
 * <ul>
 *   <li>One person: every Control Operator of the control is also listed as Facilitator. One field, as
 *   before: the Facilitator writes it in In Progress, the Control Operator in Review.</li>
 *   <li>Different people: two fields. The Facilitator writes the steps field in In Progress, the Control
 *   Operator writes the review field in Review; each is read-only to everyone else.</li>
 * </ul>
 * Values are never cleared when the mode changes (a new assignment only changes what is shown and
 * required); old data and imports have the steps field only, the review field stays NULL.
 */
public final class ControlStepsFields {

    // TODO: BUSINESS CONFIRMATION: the names of both fields (View Control, Excel, history, error messages)
    public static final String STEPS_LABEL = "Control Steps Performed and Results";
    public static final String OPERATOR_REVIEW_LABEL = "Control Operator Review and Results";

    private ControlStepsFields() {
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

    /** Different people: the control has the second field (Control Operator Review and Results). */
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
