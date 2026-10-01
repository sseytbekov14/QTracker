package com.kpmg.qtracker.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Needs attention" rules for the SoQM dashboard: controls across the whole team that nobody is
 * moving or nobody can move. One row per control with all of its reasons. Overdue alone is not a
 * reason (the deadlines block lists it); it only labels and ranks rows. Controls where it is the
 * viewer's turn are left to "Awaiting my action".
 */
public final class NeedsAttention {
    /** A review step without a status change for this many calendar days (or more) is stalled. */
    public static final int STALLED_DAYS = 7;
    /** A draft is flagged once its deadline is this many days away or closer (or already passed). */
    public static final int NOT_INITIATED_WITHIN_DAYS = 3;

    // Forward order of the workflow; a move to an earlier step is a return
    private static final List<String> STEP_ORDER = List.of(
            "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");
    private static final Set<String> REVIEW_STEPS = Set.of("REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW");
    private static final Map<String, String> RETURNED_BY = Map.of(
            "REVIEW", "CO",
            "SOQM_HEAD_REVIEW", "SoQM",
            "PROCESS_OWNER_REVIEW", "PO");

    public enum Reason {
        UNASSIGNED("Unassigned"),
        NOT_INITIATED("Not initiated"),
        RETURNED("Returned"),
        STALLED("Stalled");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** What the dashboard already knows about a control. */
    public record Candidate(Long id,
                            String controlId,
                            String description,
                            String status,
                            LocalDate deadline,
                            List<String> facilitators,
                            List<String> controlOperators,
                            List<String> soqmLeads,
                            List<String> processOwners,
                            boolean completed,
                            boolean myTurn,
                            LastMove lastMove,
                            LocalDateTime updatedAt) {
    }

    /** The latest status change of a control in workflow_history. */
    public record LastMove(String fromStep, String toStep, LocalDateTime at) {
    }

    /** A reason with its row label, e.g. "No CO, PO" or "Returned by PO · 3d ago". */
    public record Flag(Reason reason, String label) {
    }

    public record Item(Long id,
                       String controlId,
                       String description,
                       String status,
                       LocalDate deadline,
                       long daysOverdue,
                       long idleDays,
                       List<Flag> flags) {

        public boolean isOverdue() {
            return daysOverdue > 0;
        }

        /** Space-separated reason names, for the client-side filter. */
        public String getReasonKeys() {
            return String.join(" ", flags.stream().map(flag -> flag.reason().name()).toList());
        }
    }

    /**
     * @param items          sorted: overdue first (most overdue on top), then nearest deadline, no deadline last;
     *                       ties: more reasons, then longer without movement
     * @param countByReason  controls per reason; a control with several reasons counts in each
     * @param inSoqmReview   controls at the SoQM step, the team queue behind /controls?status=SOQM_HEAD_REVIEW
     */
    public record Result(List<Item> items, Map<Reason, Long> countByReason, long inSoqmReview) {
    }

    private static final Comparator<Item> ORDER = Comparator
            .comparing(Item::isOverdue).reversed()
            .thenComparing(Item::deadline, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(item -> item.flags().size(), Comparator.reverseOrder())
            .thenComparing(Item::idleDays, Comparator.reverseOrder())
            .thenComparing(Item::controlId, Comparator.nullsLast(Comparator.naturalOrder()));

    private NeedsAttention() {
    }

    public static Result evaluate(List<Candidate> candidates, LocalDate today) {
        List<Item> items = new ArrayList<>();
        Map<Reason, Long> countByReason = new EnumMap<>(Reason.class);
        for (Reason reason : Reason.values()) {
            countByReason.put(reason, 0L);
        }
        long inSoqmReview = 0;

        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            if (candidate == null) {
                continue;
            }
            String status = normalize(candidate.status());
            if ("SOQM_HEAD_REVIEW".equals(status)) {
                inSoqmReview++;
            }
            if (candidate.completed() || "COMPLETED".equals(status) || candidate.myTurn()) {
                continue;
            }
            long idleDays = idleDays(candidate, status, today);
            List<Flag> flags = flags(candidate, status, idleDays, today);
            if (flags.isEmpty()) {
                continue;
            }
            for (Flag flag : flags) {
                countByReason.merge(flag.reason(), 1L, Long::sum);
            }
            items.add(new Item(
                    candidate.id(),
                    candidate.controlId(),
                    candidate.description(),
                    status,
                    candidate.deadline(),
                    DeadlineOverdue.daysOverdue(candidate.deadline(), today),
                    idleDays,
                    flags));
        }

        items.sort(ORDER);
        return new Result(items, countByReason, inSoqmReview);
    }

    private static List<Flag> flags(Candidate candidate, String status, long idleDays, LocalDate today) {
        List<Flag> flags = new ArrayList<>();

        List<String> missing = new ArrayList<>();
        if (isEmpty(candidate.facilitators())) missing.add("Facilitator");
        if (isEmpty(candidate.controlOperators())) missing.add("CO");
        if (isEmpty(candidate.soqmLeads())) missing.add("SoQM");
        if (isEmpty(candidate.processOwners())) missing.add("PO");
        if (!missing.isEmpty()) {
            flags.add(new Flag(Reason.UNASSIGNED, "No " + String.join(", ", missing)));
        }

        if ("DRAFT".equals(status)) {
            LocalDate deadline = candidate.deadline();
            if (deadline != null && !deadline.isAfter(today.plusDays(NOT_INITIATED_WITHIN_DAYS))) {
                flags.add(new Flag(Reason.NOT_INITIATED, "Not initiated"));
            }
            return flags;
        }

        LastMove move = candidate.lastMove();
        if (isCurrent(move, status) && isReturn(move)) {
            String by = RETURNED_BY.get(normalize(move.fromStep()));
            flags.add(new Flag(Reason.RETURNED,
                    "Returned" + (by != null ? " by " + by : "") + ago(move.at(), today)));
        }

        if (REVIEW_STEPS.contains(status) && idleDays >= STALLED_DAYS) {
            flags.add(new Flag(Reason.STALLED, "No movement " + idleDays + "d"));
        }
        return flags;
    }

    /** Calendar days the control has been at its current step; 0 for drafts or when unknown. */
    private static long idleDays(Candidate candidate, String status, LocalDate today) {
        if ("DRAFT".equals(status)) {
            return 0L;
        }
        LastMove move = candidate.lastMove();
        // Without a history row for the current step (legacy data) the last edit is the best guess
        LocalDateTime since = isCurrent(move, status) ? move.at() : candidate.updatedAt();
        if (since == null) {
            return 0L;
        }
        return Math.max(0L, ChronoUnit.DAYS.between(since.toLocalDate(), today));
    }

    // The last move only tells where the control is if it led to the current status;
    // a status changed without a history row leaves the move stale
    private static boolean isCurrent(LastMove move, String status) {
        return move != null && move.at() != null && status.equals(normalize(move.toStep()));
    }

    private static boolean isReturn(LastMove move) {
        int from = STEP_ORDER.indexOf(normalize(move.fromStep()));
        int to = STEP_ORDER.indexOf(normalize(move.toStep()));
        return from >= 0 && to >= 0 && to < from;
    }

    private static String ago(LocalDateTime at, LocalDate today) {
        if (at == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(at.toLocalDate(), today);
        return days <= 0 ? " · today" : " · " + days + "d ago";
    }

    private static boolean isEmpty(List<String> values) {
        if (values == null) {
            return true;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
