package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.util.ValueList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Other Related Controls on the Details tab (business decision of 2026-10-19): Control IDs chosen from one list for
 * every control (related_control_ref, RelatedControlList), plain text - no link to any control. Stored in
 * controls.other_related_controls as ValueList writes it ("A; B", V18). A save keeps only IDs of the list, and those
 * the control already had (shown "not in list" once the list no longer has them). Who sees and changes them:
 * everyone who sees the control / ControlPermission.canEditAll (AccessPolicy.choosesRelatedControls).
 */
@Service
public class RelatedControls {

    /** The field's name, also in the Changelog. */
    public static final String LABEL = "Other Related Controls";
    /** The free text the field held before it took links; no longer used, only older Changelog entries name it. */
    public static final String EARLIER_TEXT_LABEL = "Other Related Controls (earlier text)";
    /** More Control IDs than this on one control are refused unless related-controls.max-per-control says otherwise. */
    public static final int DEFAULT_MAX = 50;

    private final RelatedControlList list;
    private final ControlDetailsRepository detailsRepository;
    private final int max;

    public RelatedControls(RelatedControlList list, ControlDetailsRepository detailsRepository,
                           @Value("${related-controls.max-per-control:" + DEFAULT_MAX + "}") int max) {
        this.list = list;
        this.detailsRepository = detailsRepository;
        this.max = max;
    }

    /** The control's Other Related Controls in their order, each marked when the list no longer has it. */
    @Transactional(readOnly = true)
    public List<ChosenValues.Shown> of(Long controlId) {
        String stored = controlId == null ? null : detailsRepository.findByControlId(controlId)
                .map(ControlDetails::getOtherRelatedControls).orElse(null);
        return ChosenValues.shown(stored, list.active(ValueList.parse(stored)));
    }

    /**
     * What a save of the field stores (incoming as ValueList writes it): Control IDs of the list, or ones the control
     * had before; or why it is refused ("Other Related Controls: ...").
     */
    @Transactional(readOnly = true)
    public ChosenValues.Checked check(String before, String incoming) {
        return ChosenValues.check(LABEL, "control", before, incoming, list.active(ValueList.parse(incoming)), max);
    }

    /** At most this many Control IDs on one control. */
    public int max() {
        return max;
    }
}
