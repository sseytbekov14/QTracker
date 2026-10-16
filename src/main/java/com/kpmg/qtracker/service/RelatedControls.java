package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.ControlRelatedControl;
import com.kpmg.qtracker.repository.ControlRelatedControlRepository;
import com.kpmg.qtracker.repository.ControlRelatedControlRepository.ControlSummary;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Other Related Controls on the Details tab (business decision of 2026-10-15): the other controls of QTracker SoQM
 * Admin Team link from a control, in the order chosen, each one a link to its View Control. Stored by controls.id
 * (control_related_controls, V15), so a Rename ID keeps them; shown with the current Control ID. Who sees and changes
 * them: everyone who sees the control / ControlPermission.canEditAll (AccessPolicy.choosesRelatedControls).
 */
@Service
@RequiredArgsConstructor
public class RelatedControls {

    /** The field's name, also in the Changelog. */
    public static final String LABEL = "Other Related Controls";
    /** The free text the field held before it took links: kept, shown under the links while it is not empty. */
    public static final String EARLIER_TEXT_LABEL = "Other Related Controls (earlier text)";
    /** More links than this on one control are refused. */
    public static final int MAX_LINKS = 50;
    /** A control's description in the picker is cut to this many characters. */
    private static final int DESCRIPTION_LENGTH = 140;

    private final ControlRelatedControlRepository repository;
    private final StatusDisplayMapper statusDisplayMapper;

    /** A linked control as View Control shows it: its current Control ID, its status and the address of its page. */
    public record Related(Long id, String controlId, String status, String href) {
    }

    /** A control the picker offers. */
    public record Option(Long id, String controlId, String status, String description) {
    }

    /** The controls linked from the control, in their order; a link to a control that is gone is left out. */
    @Transactional(readOnly = true)
    public List<Related> of(Long controlId) {
        List<Long> ids = ids(controlId);
        Map<Long, ControlSummary> controls = summaries(ids);
        List<Related> related = new ArrayList<>();
        for (Long id : ids) {
            ControlSummary control = controls.get(id);
            if (control != null) {
                related.add(new Related(id, control.getControlId(), status(control), "/view-control/" + id));
            }
        }
        return related;
    }

    /** The linked controls' ids, in their order. */
    @Transactional(readOnly = true)
    public List<Long> ids(Long controlId) {
        if (controlId == null) {
            return List.of();
        }
        return repository.findByControlIdOrderBySortOrderAsc(controlId).stream()
                .map(ControlRelatedControl::getRelatedControlId)
                .toList();
    }

    /** The Control IDs of the linked controls, in their order (the Excel of a completed control, the API). */
    @Transactional(readOnly = true)
    public List<String> controlIds(Long controlId) {
        return of(controlId).stream().map(Related::controlId).toList();
    }

    /** The list a save asks for: each control once, in the order given, without empty entries. */
    public static List<Long> normalize(List<Long> ids) {
        if (ids == null) {
            return null;
        }
        return List.copyOf(ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /** Why the control may not link these controls (normalized), or empty. */
    @Transactional(readOnly = true)
    public Optional<String> refusal(Long controlId, List<Long> ids) {
        if (ids.contains(controlId)) {
            return Optional.of(LABEL + ": a control cannot be related to itself");
        }
        if (ids.size() > MAX_LINKS) {
            return Optional.of(LABEL + ": at most " + MAX_LINKS + " controls");
        }
        Map<Long, ControlSummary> controls = summaries(ids);
        List<String> missing = ids.stream().filter(id -> !controls.containsKey(id)).map(String::valueOf).toList();
        if (!missing.isEmpty()) {
            return Optional.of(LABEL + ": " + (missing.size() == 1 ? "a chosen control no longer exists"
                    : missing.size() + " chosen controls no longer exist") + ". Reload the page and choose again.");
        }
        return Optional.empty();
    }

    /** "HR-CTRL-MF-5/FY27/Central/OCT, EP-CTRL-MF-108/FY27/Central": the Control IDs, for the Changelog. */
    @Transactional(readOnly = true)
    public String describe(List<Long> ids) {
        Map<Long, ControlSummary> controls = summaries(ids);
        return ids.stream()
                .map(id -> controls.containsKey(id) ? controls.get(id).getControlId() : "#" + id)
                .collect(Collectors.joining(", "));
    }

    /** The control links these controls (normalized, checked with {@link #refusal}), in this order, and no others. */
    @Transactional
    public void replace(Long controlId, List<Long> ids) {
        repository.deleteByControlId(controlId);
        List<ControlRelatedControl> rows = new ArrayList<>();
        for (Long id : ids) {
            rows.add(new ControlRelatedControl(controlId, id, rows.size()));
        }
        repository.saveAll(rows);
    }

    /** Create Control: the next cycle links the same controls as the one it is created from. */
    @Transactional
    public void copy(Long fromControlId, Long toControlId) {
        List<Long> ids = ids(fromControlId).stream().filter(id -> !id.equals(toControlId)).toList();
        if (!ids.isEmpty()) {
            replace(toControlId, ids);
        }
    }

    /** Every control but this one, by Control ID: what the picker offers. */
    @Transactional(readOnly = true)
    public List<Option> options(Long controlId) {
        return repository.findAllSummaries().stream()
                .filter(control -> !control.getId().equals(controlId))
                .sorted(Comparator.comparing((ControlSummary control) -> Objects.toString(control.getControlId(), "")
                        .toLowerCase(Locale.ROOT)).thenComparing(ControlSummary::getId))
                .map(control -> new Option(control.getId(), control.getControlId(), status(control),
                        shortDescription(control.getControlDescription())))
                .toList();
    }

    private Map<Long, ControlSummary> summaries(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findSummariesByIds(ids).stream()
                .collect(Collectors.toMap(ControlSummary::getId, Function.identity()));
    }

    /** "In Progress", "Completed · Superseded": the performance status, and a Deleted or Superseded control says so. */
    private String status(ControlSummary control) {
        String performance = statusDisplayMapper.display(
                control.getPerformanceStatus() == null || control.getPerformanceStatus().isBlank()
                        ? "DRAFT" : control.getPerformanceStatus());
        String retired = ControlStatusValue.badge(control.getControlStatus());
        return retired == null ? performance : performance + " · " + retired;
    }

    private static String shortDescription(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String oneLine = description.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= DESCRIPTION_LENGTH ? oneLine : oneLine.substring(0, DESCRIPTION_LENGTH - 1) + "…";
    }
}
