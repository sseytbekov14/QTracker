package com.kpmg.qtracker.service;

import com.kpmg.qtracker.service.NeedsAttention.Candidate;
import com.kpmg.qtracker.service.NeedsAttention.Flag;
import com.kpmg.qtracker.service.NeedsAttention.Item;
import com.kpmg.qtracker.service.NeedsAttention.LastMove;
import com.kpmg.qtracker.service.NeedsAttention.Reason;
import com.kpmg.qtracker.service.NeedsAttention.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NeedsAttentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    // ===== Unassigned =====

    @Test
    void assignedAndMoving_isNotListed() {
        Result result = evaluate(control("OK").status("REVIEW").movedTo("IN_PROGRESS", "REVIEW", daysAgo(1)));

        assertThat(result.items()).isEmpty();
    }

    @Test
    void missingRoles_areListedByName_blankEntriesCountAsMissing() {
        Result result = evaluate(control("C1").operators().owners(" ", ""));

        assertThat(labels(only(result))).containsExactly("No CO, PO");
        assertThat(reasons(only(result))).containsExactly(Reason.UNASSIGNED);
    }

    @Test
    void missingSoqmLeadAndFacilitator_areUnassignedToo() {
        Result result = evaluate(control("C1").facilitators().soqmLeads());

        assertThat(labels(only(result))).containsExactly("No Facilitator, SoQM");
    }

    @Test
    void draftWithoutAssignees_isUnassigned_evenWithAFarDeadline() {
        Result result = evaluate(control("D1").status("DRAFT").deadline(TODAY.plusDays(60)).facilitators());

        assertThat(reasons(only(result))).containsExactly(Reason.UNASSIGNED);
    }

    // ===== Not initiated =====

    @Test
    void draftPastDeadline_isNotInitiated_andCarriesTheOverdueBadge() {
        Result result = evaluate(control("D1").status("DRAFT").deadline(TODAY.minusDays(4)));

        Item item = only(result);
        assertThat(labels(item)).containsExactly("Not initiated");
        assertThat(item.isOverdue()).isTrue();
        assertThat(item.daysOverdue()).isEqualTo(4L);
    }

    @Test
    void draftDueWithinThreeDays_isNotInitiated_fourDaysAway_isNot() {
        Result result = evaluate(
                control("D-3").status("DRAFT").deadline(TODAY.plusDays(NeedsAttention.NOT_INITIATED_WITHIN_DAYS)),
                control("D-4").status("DRAFT").deadline(TODAY.plusDays(NeedsAttention.NOT_INITIATED_WITHIN_DAYS + 1)),
                control("D-none").status("DRAFT").deadline(null),
                control("D-blank-status").status(" ").deadline(TODAY));

        assertThat(ids(result)).containsExactly("D-blank-status", "D-3");
        assertThat(result.countByReason().get(Reason.NOT_INITIATED)).isEqualTo(2L);
    }

    // ===== Returned =====

    @Test
    void lastMoveBackwards_isReturned_withWhoAndWhen() {
        Result result = evaluate(
                control("R1").status("REVIEW").movedTo("SOQM_HEAD_REVIEW", "REVIEW", daysAgo(3)),
                control("R2").status("IN_PROGRESS").movedTo("PROCESS_OWNER_REVIEW", "IN_PROGRESS", TODAY.atTime(8, 0)),
                control("R3").status("SOQM_HEAD_REVIEW").movedTo("PROCESS_OWNER_REVIEW", "SOQM_HEAD_REVIEW", daysAgo(1)));

        assertThat(result.items()).extracting(item -> labels(item).get(0)).containsExactlyInAnyOrder(
                "Returned by SoQM · 3d ago", "Returned by PO · today", "Returned by PO · 1d ago");
    }

    @Test
    void forwardMove_isNotReturned() {
        Result result = evaluate(control("F1").status("SOQM_HEAD_REVIEW").movedTo("REVIEW", "SOQM_HEAD_REVIEW", daysAgo(1)));

        assertThat(result.items()).isEmpty();
    }

    @Test
    void returnThatNoLongerMatchesTheStatus_isIgnored() {
        // Sent back to the operator, then the status changed without a history row
        Result result = evaluate(control("S1").status("SOQM_HEAD_REVIEW")
                .movedTo("SOQM_HEAD_REVIEW", "REVIEW", daysAgo(2))
                .updatedAt(daysAgo(1)));

        assertThat(result.items()).isEmpty();
    }

    // ===== Stalled =====

    @Test
    void reviewStep_exactlySevenDaysWithoutMovement_isStalled_sixIsNot() {
        Result result = evaluate(
                control("S7").status("REVIEW").movedTo("IN_PROGRESS", "REVIEW", daysAgo(NeedsAttention.STALLED_DAYS)),
                control("S6").status("REVIEW").movedTo("IN_PROGRESS", "REVIEW", daysAgo(NeedsAttention.STALLED_DAYS - 1)));

        Item item = only(result);
        assertThat(item.controlId()).isEqualTo("S7");
        assertThat(labels(item)).containsExactly("No movement 7d");
    }

    @Test
    void stalled_countsCalendarDays_notFullDays() {
        // 23:59 seven calendar days ago is less than 7 x 24h, still 7 days at the step
        LocalDateTime lateEvening = TODAY.minusDays(7).atTime(23, 59);
        Result result = evaluate(control("S1").status("PROCESS_OWNER_REVIEW")
                .movedTo("SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", lateEvening));

        assertThat(reasons(only(result))).containsExactly(Reason.STALLED);
    }

    @Test
    void allReviewSteps_canStall_facilitatorStepCannot() {
        Result result = evaluate(
                control("S-review").status("REVIEW").movedTo("IN_PROGRESS", "REVIEW", daysAgo(10)),
                control("S-soqm").status("SOQM_HEAD_REVIEW").movedTo("REVIEW", "SOQM_HEAD_REVIEW", daysAgo(10)),
                control("S-po").status("PROCESS_OWNER_REVIEW").movedTo("SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", daysAgo(10)),
                control("S-fac").status("IN_PROGRESS").movedTo(null, "IN_PROGRESS", daysAgo(30)));

        assertThat(ids(result)).containsExactlyInAnyOrder("S-review", "S-soqm", "S-po");
    }

    @Test
    void withoutHistoryForTheStep_lastEditIsUsed() {
        Result result = evaluate(
                control("Old").status("REVIEW").updatedAt(daysAgo(9)),
                control("Fresh").status("REVIEW").updatedAt(daysAgo(2)),
                control("Unknown").status("REVIEW").updatedAt(null));

        assertThat(ids(result)).containsExactly("Old");
        assertThat(labels(only(result))).containsExactly("No movement 9d");
    }

    // ===== Overdue is a badge, not a reason =====

    @Test
    void overdueAlone_isNotListed() {
        Result result = evaluate(control("O1").status("REVIEW")
                .deadline(TODAY.minusDays(10))
                .movedTo("IN_PROGRESS", "REVIEW", daysAgo(1)));

        assertThat(result.items()).isEmpty();
    }

    @Test
    void deadlineToday_isNotOverdue() {
        Result result = evaluate(control("T1").deadline(TODAY).operators());

        Item item = only(result);
        assertThat(item.isOverdue()).isFalse();
        assertThat(item.daysOverdue()).isZero();
    }

    // ===== Exclusions =====

    @Test
    void completedControls_andMyTurn_areLeftOut() {
        Result result = evaluate(
                control("Done-status").status("COMPLETED").operators(),
                control("Done-history").status("PROCESS_OWNER_REVIEW").completed().operators(),
                control("Mine").status("SOQM_HEAD_REVIEW").myTurn().movedTo("REVIEW", "SOQM_HEAD_REVIEW", daysAgo(20)));

        assertThat(result.items()).isEmpty();
        assertThat(result.countByReason()).containsOnlyKeys(Reason.values()).allSatisfy((reason, count) ->
                assertThat(count).isZero());
    }

    @Test
    void soqmReviewCounter_countsTheWholeQueue_includingMyTurn() {
        Result result = evaluate(
                control("Q1").status("SOQM_HEAD_REVIEW").myTurn(),
                control("Q2").status("soqm_head_review "),
                control("Q3").status("REVIEW"));

        assertThat(result.inSoqmReview()).isEqualTo(2L);
    }

    // ===== One row per control =====

    @Test
    void severalReasons_giveOneRow_countedUnderEachReason() {
        Result result = evaluate(
                control("Multi").status("REVIEW").owners()
                        .movedTo("SOQM_HEAD_REVIEW", "REVIEW", daysAgo(8)),
                control("Single").status("REVIEW").owners());

        assertThat(result.items()).hasSize(2);
        Item multi = result.items().get(0);
        assertThat(multi.controlId()).isEqualTo("Multi");
        assertThat(reasons(multi)).containsExactly(Reason.UNASSIGNED, Reason.RETURNED, Reason.STALLED);
        assertThat(labels(multi)).containsExactly("No PO", "Returned by SoQM · 8d ago", "No movement 8d");
        assertThat(multi.getReasonKeys()).isEqualTo("UNASSIGNED RETURNED STALLED");
        assertThat(result.countByReason()).containsEntry(Reason.UNASSIGNED, 2L)
                .containsEntry(Reason.RETURNED, 1L)
                .containsEntry(Reason.STALLED, 1L)
                .containsEntry(Reason.NOT_INITIATED, 0L);
    }

    // ===== Sorting =====

    @Test
    void sort_overdueFirstMostOverdueOnTop_thenNearestDeadline_noDeadlineLast() {
        Result result = evaluate(
                control("NoDeadline").deadline(null).operators(),
                control("DueIn5").deadline(TODAY.plusDays(5)).operators(),
                control("Overdue2").deadline(TODAY.minusDays(2)).operators(),
                control("DueToday").deadline(TODAY).operators(),
                control("Overdue9").deadline(TODAY.minusDays(9)).operators());

        assertThat(ids(result)).containsExactly("Overdue9", "Overdue2", "DueToday", "DueIn5", "NoDeadline");
    }

    @Test
    void sort_sameDeadline_moreReasonsFirst_thenLongerIdle() {
        LocalDate deadline = TODAY.plusDays(10);
        Result result = evaluate(
                control("Stalled8").status("REVIEW").deadline(deadline).movedTo("IN_PROGRESS", "REVIEW", daysAgo(8)),
                control("Stalled12").status("REVIEW").deadline(deadline).movedTo("IN_PROGRESS", "REVIEW", daysAgo(12)),
                control("TwoReasons").status("REVIEW").deadline(deadline).owners()
                        .movedTo("IN_PROGRESS", "REVIEW", daysAgo(7)));

        assertThat(ids(result)).containsExactly("TwoReasons", "Stalled12", "Stalled8");
    }

    // ===== helpers =====

    private static Result evaluate(Builder... builders) {
        return NeedsAttention.evaluate(Arrays.stream(builders).map(Builder::build).toList(), TODAY);
    }

    private static Item only(Result result) {
        assertThat(result.items()).hasSize(1);
        return result.items().get(0);
    }

    private static List<String> ids(Result result) {
        return result.items().stream().map(Item::controlId).toList();
    }

    private static List<String> labels(Item item) {
        return item.flags().stream().map(Flag::label).toList();
    }

    private static List<Reason> reasons(Item item) {
        return item.flags().stream().map(Flag::reason).toList();
    }

    private static LocalDateTime daysAgo(int days) {
        return TODAY.minusDays(days).atTime(10, 0);
    }

    private static Builder control(String controlId) {
        return new Builder(controlId);
    }

    /** An assigned, in-progress control edited today with a far deadline; tests change one thing at a time. */
    private static final class Builder {
        private static long nextId = 1;

        private final String controlId;
        private String status = "IN_PROGRESS";
        private LocalDate deadline = TODAY.plusDays(30);
        private List<String> facilitators = List.of("fac@example.test");
        private List<String> operators = List.of("op@example.test");
        private List<String> soqmLeads = List.of("soqm@example.test");
        private List<String> owners = List.of("po@example.test");
        private boolean completed;
        private boolean myTurn;
        private LastMove lastMove;
        private LocalDateTime updatedAt = TODAY.atTime(9, 0);

        private Builder(String controlId) {
            this.controlId = controlId;
        }

        Builder status(String value) {
            status = value;
            return this;
        }

        Builder deadline(LocalDate value) {
            deadline = value;
            return this;
        }

        Builder facilitators(String... values) {
            facilitators = List.of(values);
            return this;
        }

        Builder operators(String... values) {
            operators = List.of(values);
            return this;
        }

        Builder soqmLeads(String... values) {
            soqmLeads = List.of(values);
            return this;
        }

        Builder owners(String... values) {
            owners = List.of(values);
            return this;
        }

        Builder completed() {
            completed = true;
            return this;
        }

        Builder myTurn() {
            myTurn = true;
            return this;
        }

        Builder movedTo(String fromStep, String toStep, LocalDateTime at) {
            lastMove = new LastMove(fromStep, toStep, at);
            return this;
        }

        Builder updatedAt(LocalDateTime value) {
            updatedAt = value;
            return this;
        }

        Candidate build() {
            return new Candidate(nextId++, controlId, controlId + " description", status, deadline,
                    facilitators, operators, soqmLeads, owners, completed, myTurn, lastMove, updatedAt);
        }
    }
}
