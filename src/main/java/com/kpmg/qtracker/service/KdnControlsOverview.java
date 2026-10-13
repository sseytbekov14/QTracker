package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The KDN controls among the controls the user already sees (no query of its own, so the KDN page counts exactly
 * what the user sees). A KDN control is {@link AccessPolicy#isKdnControl}; the page's counters are
 * {@link ComponentControlsList.Counts}, as on the component lists.
 */
public final class KdnControlsOverview {

    /** The KDN page (in the menu; /component/KDN leads here): the same list as a component's. */
    public static final String CONTROLS_HREF = "/kdn";

    private KdnControlsOverview() {
    }

    /** The KDN controls among {@code visible} (the controls the user sees, as the policy decided). */
    public static List<ControlResponseDTO> kdnControls(Collection<ControlResponseDTO> visible) {
        return visible == null ? List.of() : visible.stream()
                .filter(Objects::nonNull)
                .filter(control -> AccessPolicy.isKdnControl(control.getControlId()))
                .toList();
    }
}
