package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.RelatedControlRef;
import com.kpmg.qtracker.repository.RelatedControlRefRepository;
import com.kpmg.qtracker.util.ValueList;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The list Other Related Controls is chosen from (related_control_ref, V17): the active Control IDs, one list for every
 * control, filled by --import-related-controls (RelatedControlsImport).
 */
@Service
@RequiredArgsConstructor
public class RelatedControlList {

    /** How many Control IDs a search gives when it does not say, and at most. */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final RelatedControlRefRepository repository;

    /** The active Control IDs holding the text (any case), by Control ID; at most limit of them (1 to MAX_LIMIT). */
    @Transactional(readOnly = true)
    public List<String> search(String query, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return repository.searchActive(pattern, PageRequest.of(0, size));
    }

    /** The values that are active Control IDs of the list: their key (ValueList.key) to the ID as the list has it. */
    @Transactional(readOnly = true)
    public Map<String, String> active(Collection<String> values) {
        Map<String, String> active = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return active;
        }
        List<String> keys = values.stream().map(ValueList::key).distinct().toList();
        for (RelatedControlRef ref : repository.findByActiveTrueAndControlKeyIn(keys)) {
            active.put(ref.getControlKey(), ref.getControlId());
        }
        return active;
    }

    /** No Control ID to choose yet. */
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return !repository.existsByActiveTrue();
    }
}
