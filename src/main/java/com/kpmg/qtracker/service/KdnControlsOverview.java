package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The KDN controls among the controls the user already sees (no query of its own, so the Action Centre's KDN card
 * and the list behind it count exactly what the user sees). A KDN control is {@link AccessPolicy#isKdnControl};
 * the card's counters are {@link ComponentControlsList.Counts}, as on the component cards.
 */
public final class KdnControlsOverview {

    /** The list of the KDN controls, where the card leads (the same list as a component's). */
    public static final String CONTROLS_HREF = "/component/" + ComponentControlsList.KDN_CODE;

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
