package com.kpmg.qtracker.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A change SoQM Team makes to a completed control without returning it ({@link AccessPolicy#editsAfterCompletion}):
 * every save that changes something needs a reason, and the audit trail and the Changelog mark the entry
 * "Edited after completion" with that reason next to the author and the values before and after.
 * TODO: BUSINESS CONFIRMATION: is the reason required, and is the Process Owner told of such a change? No
 * notification is sent for now.
 */
public final class CompletedEdit {

    /** The mark on the audit entry (its description) and on the Changelog entry. */
    public static final String MARK = "Edited after completion";

    /** The field of the audit entry that holds the reason (shown as such in the Admin Panel audit trail). */
    public static final String REASON_FIELD = "Reason";

    public static final int REASON_MAX_LENGTH = 500;

    public static final String REASON_REQUIRED = "Give a reason for changing a completed control";

    public static final String REASON_TOO_LONG =
            "The reason for changing a completed control is at most " + REASON_MAX_LENGTH + " characters";

    private CompletedEdit() {
    }

    /** Why the reason sent with a change of a completed control is not enough, or empty when it is. */
    public static Optional<String> reasonRefusal(String reason) {
        String cleaned = clean(reason);
        if (cleaned == null) {
            return Optional.of(REASON_REQUIRED);
        }
        if (cleaned.length() > REASON_MAX_LENGTH) {
            return Optional.of(REASON_TOO_LONG);
        }
        return Optional.empty();
    }

    /**
     * Why a save is refused for its reason: only a save that changes a completed control in place needs one
     * ({@link ControlPermission#isCompletedEdit}); a save that changes nothing does not.
     */
    public static Optional<String> refusal(ControlPermission permission, boolean changes, String reason) {
        if (permission == null || !permission.isCompletedEdit() || !changes) {
            return Optional.empty();
        }
        return reasonRefusal(reason);
    }

    /** The reason to record with a save: null unless it changes a completed control in place. */
    public static String reasonOf(ControlPermission permission, String reason) {
        return permission != null && permission.isCompletedEdit() ? clean(reason) : null;
    }

    /** The reason as it is stored: trimmed, null when blank. */
    public static String clean(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    /** The description of the audit entry of such a change: "Edit Control - Edited after completion". */
    public static String describe(String description) {
        return (description == null || description.isBlank() ? "Edit Control" : description) + " - " + MARK;
    }

    /** Whether an audit entry is one of such a change. */
    public static boolean isMarked(String description) {
        return description != null && description.endsWith(" - " + MARK);
    }

    /** The reason as the last field of the audit entry (no value before it). */
    public static void addReason(List<String> changedFields, Map<String, String> newValues, String reason) {
        changedFields.add(REASON_FIELD);
        newValues.put(REASON_FIELD, clean(reason));
    }
}
