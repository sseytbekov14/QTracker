package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The "KDN controls" block of the Action Centre: its counters and its list, worked out in one pass over the
 * controls the user already sees (no query of its own, so it shows exactly what the user's Controls list shows).
 * A KDN control is {@link AccessPolicy#isKdnControl}; overdue is {@link DeadlineOverdue}, as on the tiles and the
 * Overdue filter of Controls.
 */
public final class KdnControlsOverview {

    /** Rows shown in the block; the rest are behind "Show all N". */
    public static final int LIST_LIMIT = 8;

    /** The Controls list of the KDN controls (the block's "View all KDN controls"). */
    public static final String CONTROLS_HREF = "/controls?kdn=1";

    /** The statuses counted as "In review"; status=IN_REVIEW on Controls filters the same ones. */
    public static final Set<String> REVIEW_STATUSES = Set.of("REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW");

    private KdnControlsOverview() {
    }

    /**
     * Counters and every KDN control in display order: overdue first, then by the nearest deadline (no deadline
     * last), then by Control ID. Drafts count in {@code total} only; overdue may also be in progress or in review.
     */
    public record Overview(long total, long drafts, long inProgress, long inReview, long completed, long overdue,
                           List<ControlResponseDTO> controls) {

        /** The first {@link #LIST_LIMIT} rows. */
        public List<ControlResponseDTO> shown() {
            return controls.subList(0, Math.min(LIST_LIMIT, controls.size()));
        }

        /** Rows not shown until "Show all N". */
        public List<ControlResponseDTO> more() {
            return controls.subList(Math.min(LIST_LIMIT, controls.size()), controls.size());
        }

        public boolean empty() {
            return total == 0;
        }
    }

    /** A blank status is a draft, as everywhere else. */
    static String statusOf(ControlResponseDTO control) {
        String status = control.getPerformanceStatus();
        return status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean inReview(String status) {
        return status != null && REVIEW_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    /** Overdue first, then the nearest deadline, then Control ID. */
    static Comparator<ControlResponseDTO> order(LocalDate today) {
        return Comparator.comparing((ControlResponseDTO control) ->
                        !DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), today))
                .thenComparing(ControlResponseDTO::getDeadline, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ControlResponseDTO::getControlId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * The KDN controls among {@code visible} (the controls the user sees, as the policy decided), each marked
     * overdue or not for {@code today}.
     */
    public static Overview of(Collection<ControlResponseDTO> visible, LocalDate today) {
        List<ControlResponseDTO> kdn = visible == null ? List.of() : visible.stream()
                .filter(Objects::nonNull)
                .filter(control -> AccessPolicy.isKdnControl(control.getControlId()))
                .sorted(order(today))
                .toList();
        long drafts = 0;
        long inProgress = 0;
        long inReview = 0;
        long completed = 0;
        long overdue = 0;
        for (ControlResponseDTO control : kdn) {
            String status = statusOf(control);
            boolean late = DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), today);
            control.setOverdue(late);
            if (late) {
                overdue++;
            }
            if ("DRAFT".equals(status)) {
                drafts++;
            } else if ("IN_PROGRESS".equals(status)) {
                inProgress++;
            } else if (inReview(status)) {
                inReview++;
            } else if (DeadlineOverdue.isCompleted(status)) {
                completed++;
            }
        }
        return new Overview(kdn.size(), drafts, inProgress, inReview, completed, overdue, kdn);
    }
}
