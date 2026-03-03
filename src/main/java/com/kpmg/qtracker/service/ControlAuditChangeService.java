package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import jakarta.persistence.Transient;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ControlAuditChangeService {

    private static final Set<String> EXCLUDED_FIELDS = Set.of(
            "id",
            "createdAt",
            "updatedAt",
            "createdBy"
    );

    public Control snapshot(Control source) {
        if (source == null) {
            return null;
        }
        Control snapshot = new Control();
        BeanUtils.copyProperties(source, snapshot);
        return snapshot;
    }

    public ControlAuditChangeSet diff(Control before, Control after) {
        List<String> changedFields = new ArrayList<>();
        Map<String, String> previousValues = new LinkedHashMap<>();
        Map<String, String> newValues = new LinkedHashMap<>();

        if (before == null || after == null) {
            return new ControlAuditChangeSet(changedFields, previousValues, newValues);
        }

        for (Field field : Control.class.getDeclaredFields()) {
            if (!isTrackedField(field)) {
                continue;
            }
            field.setAccessible(true);

            Object beforeValue = getFieldValue(field, before);
            Object afterValue = getFieldValue(field, after);

            String beforeString = stringify(beforeValue);
            String afterString = stringify(afterValue);

            if (!Objects.equals(normalize(beforeString), normalize(afterString))) {
                String fieldKey = resolveFieldKey(field);
                changedFields.add(fieldKey);
                previousValues.put(fieldKey, beforeString);
                newValues.put(fieldKey, afterString);
            }
        }

        return new ControlAuditChangeSet(changedFields, previousValues, newValues);
    }

    private boolean isTrackedField(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
            return false;
        }
        if (field.getAnnotation(Transient.class) != null) {
            return false;
        }
        return !EXCLUDED_FIELDS.contains(field.getName());
    }

    private Object getFieldValue(Field field, Control control) {
        try {
            return field.get(control);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to read control field: " + field.getName(), e);
        }
    }

    private String resolveFieldKey(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && column.name() != null && !column.name().isBlank()) {
            return column.name();
        }
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        if (joinColumn != null && joinColumn.name() != null && !joinColumn.name().isBlank()) {
            return joinColumn.name();
        }
        return toSnakeCase(field.getName());
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String toSnakeCase(String name) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (Character.isUpperCase(current) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }

    public static final class ControlAuditChangeSet {
        private final List<String> changedFields;
        private final Map<String, String> previousValues;
        private final Map<String, String> newValues;

        public ControlAuditChangeSet(List<String> changedFields,
                                     Map<String, String> previousValues,
                                     Map<String, String> newValues) {
            this.changedFields = changedFields;
            this.previousValues = previousValues;
            this.newValues = newValues;
        }

        public boolean hasChanges() {
            return !changedFields.isEmpty();
        }

        public List<String> getChangedFields() {
            return changedFields;
        }

        public Map<String, String> getPreviousValues() {
            return previousValues;
        }

        public Map<String, String> getNewValues() {
            return newValues;
        }
    }
}
