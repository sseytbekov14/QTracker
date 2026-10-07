package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The "KDN" card of the Action Centre: the KDN controls among the controls the user already sees (no query of its
 * own, so it counts exactly what the user's Controls list behind the card shows). A KDN control is
 * {@link AccessPolicy#isKdnControl}; completed, overdue and active are {@link DeadlineOverdue#count}, as on the
 * component cards and the Controls counters.
 */
public final class KdnControlsOverview {

    /** The Controls list of the KDN controls, where the card leads. */
    public static final String CONTROLS_HREF = "/controls?kdn=1";

    private KdnControlsOverview() {
    }

    /** The KDN controls among {@code visible} (the controls the user sees, as the policy decided). */
    public static List<ControlResponseDTO> kdnControls(Collection<ControlResponseDTO> visible) {
        return visible == null ? List.of() : visible.stream()
                .filter(Objects::nonNull)
                .filter(control -> AccessPolicy.isKdnControl(control.getControlId()))
                .toList();
    }

    /** Each KDN control once: completed, overdue or active (drafts are active, as on the component cards). */
    public static DeadlineOverdue.Counts count(Collection<ControlResponseDTO> visible, LocalDate today) {
        return DeadlineOverdue.count(kdnControls(visible),
                ControlResponseDTO::getPerformanceStatus, ControlResponseDTO::getDeadline, today);
    }
}
