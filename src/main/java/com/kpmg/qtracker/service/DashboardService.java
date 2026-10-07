package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.DashboardCalendarEventDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.dto.DashboardDeadlineCountdownDTO;
import com.kpmg.qtracker.dto.DashboardDeadlineCountdownItemDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private static final DateTimeFormatter TREND_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final int CALENDAR_DUE_SOON_DAYS = 3;
    private static final Comparator<DeadlineRow> BY_DEADLINE = Comparator
            .comparing(DeadlineRow::deadline, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DeadlineRow::id);

    private final ControlRepository controlRepository;
    private final IControlService controlService;
    private final ControlAssignmentService controlAssignmentService;

    @PersistenceContext
    private EntityManager entityManager;

    public DashboardChartDataDTO getStatusBreakdown() {
        DeadlineOverdue.Counts counts = DeadlineOverdue.count(
                toDeadlineRows(controlRepository.findAll()), DeadlineRow::status, DeadlineRow::deadline, today());
        Map<String, Long> chartCounts = new LinkedHashMap<>();
        chartCounts.put("Active", counts.active());
        chartCounts.put("Completed", counts.completed());
        chartCounts.put("Overdue", counts.overdue());
        return toChartData(chartCounts);
    }

    public DashboardChartDataDTO getComponentBreakdown() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("HR", 0L);
        counts.put("A&C", 0L);
        counts.put("EP", 0L);
        counts.put("INTR", 0L);
        counts.put("I&C", 0L);
        counts.put("RER", 0L);
        counts.put("M&R", 0L);
        counts.put("GOV", 0L);
        counts.put("TECHR", 0L);
        counts.put("RAP", 0L);

        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT bucket, COUNT(*)
                FROM (
                    SELECT CASE
                        WHEN c.component IS NULL OR TRIM(c.component) = '' THEN NULL
                        WHEN UPPER(TRIM(c.component)) = 'HR' THEN 'HR'
                        WHEN UPPER(TRIM(c.component)) = 'A&C' THEN 'A&C'
                        WHEN UPPER(TRIM(c.component)) = 'EP' THEN 'EP'
                        WHEN UPPER(TRIM(c.component)) = 'INTR' THEN 'INTR'
                        WHEN UPPER(TRIM(c.component)) = 'I&C' THEN 'I&C'
                        WHEN UPPER(TRIM(c.component)) = 'RER' THEN 'RER'
                        WHEN UPPER(TRIM(c.component)) = 'M&R' THEN 'M&R'
                        WHEN UPPER(TRIM(c.component)) = 'GOV' THEN 'GOV'
                        WHEN UPPER(TRIM(c.component)) = 'TECHR' THEN 'TECHR'
                        WHEN UPPER(TRIM(c.component)) = 'RAP' THEN 'RAP'
                        ELSE NULL
                    END AS bucket
                    FROM controls c
                ) grouped_components
                WHERE bucket IS NOT NULL
                GROUP BY bucket
                """)
                .getResultList();

        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            String label = row[0].toString();
            if (counts.containsKey(label)) {
                counts.put(label, toLong(row[1]));
            }
        }

        return toChartData(counts);
    }

    public DashboardChartDataDTO getMyFrequencyBreakdown(User currentUser) {
        return buildFrequencyBreakdown(findMyScopedNonDraftControls(currentUser));
    }

    public DashboardChartDataDTO getMyComponentBreakdown(User currentUser) {
        return buildComponentBreakdown(findMyScopedNonDraftControls(currentUser));
    }

    public DashboardChartDataDTO getMyOverdueTrend(User currentUser) {
        return buildOverdueTrend(toDeadlineRows(findMyScopedNonDraftControls(currentUser)));
    }

    public DashboardChartDataDTO getFrequencyBreakdown() {
        return buildFrequencyBreakdown(controlRepository.findAll());
    }

    public DashboardChartDataDTO getOverdueTrend() {
        return buildOverdueTrend(toDeadlineRows(controlRepository.findAll()));
    }

    // Controls still overdue, by deadline day, over the last 30 days
    private DashboardChartDataDTO buildOverdueTrend(List<DeadlineRow> rows) {
        LocalDate today = today();
        LocalDate startDate = today.minusDays(29);
        Map<LocalDate, Long> grouped = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            grouped.put(date, 0L);
        }
        for (DeadlineRow row : rows) {
            if (DeadlineOverdue.isOverdue(row.status(), row.deadline(), today)) {
                grouped.computeIfPresent(row.deadline(), (key, value) -> value + 1);
            }
        }
        return toTrendChartData(grouped);
    }

    public DashboardDeadlineCountdownDTO getDeadlineCountdown(User currentUser, int days, int limit) {
        LocalDate today = today();
        int safeLimit = Math.max(1, limit);
        List<DeadlineRow> rows = visibleDeadlineRows(currentUser);

        List<DeadlineRow> overdue = rows.stream()
                .filter(row -> DeadlineOverdue.isOverdue(row.status(), row.deadline(), today))
                .sorted(BY_DEADLINE)
                .collect(Collectors.toList());
        List<DeadlineRow> upcoming = rows.stream()
                .filter(row -> DeadlineOverdue.isDueSoon(row.status(), row.deadline(), today, days))
                .sorted(BY_DEADLINE)
                .limit(safeLimit)
                .collect(Collectors.toList());

        return new DashboardDeadlineCountdownDTO(
                toDeadlineItems(overdue.stream().limit(safeLimit).collect(Collectors.toList()), today),
                overdue.size(),
                toDeadlineItems(upcoming, today)
        );
    }

    private List<DashboardDeadlineCountdownItemDTO> toDeadlineItems(List<DeadlineRow> rows, LocalDate today) {
        List<DashboardDeadlineCountdownItemDTO> items = new ArrayList<>();
        for (DeadlineRow row : rows) {
            items.add(new DashboardDeadlineCountdownItemDTO(
                    row.id(),
                    row.controlId() != null ? row.controlId() : "Control",
                    shortenText(row.description(), 48),
                    DeadlineOverdue.endOfDay(row.deadline()),
                    normalizeStatus(row.status()),
                    controlPage(row),
                    DeadlineOverdue.isOverdue(row.status(), row.deadline(), today),
                    DeadlineOverdue.daysOverdue(row.status(), row.deadline(), today)
            ));
        }
        return items;
    }

    public List<DashboardCalendarEventDTO> getDeadlineCalendar(User currentUser, LocalDate start, LocalDate end) {
        if (currentUser == null || start == null || end == null || !end.isAfter(start)) {
            return Collections.emptyList();
        }
        LocalDate today = today();
        return visibleDeadlineRows(currentUser).stream()
                .filter(row -> row.deadline() != null && !row.deadline().isBefore(start) && row.deadline().isBefore(end))
                .sorted(BY_DEADLINE)
                .map(row -> new DashboardCalendarEventDTO(
                        row.controlId() != null ? row.controlId() : "Control",
                        row.deadline().toString(),
                        controlPage(row),
                        calendarColor(row, today)))
                .collect(Collectors.toList());
    }

    // A draft opens on its Initiate page (which sends anyone who may not initiate it on to View Control)
    private String controlPage(DeadlineRow row) {
        return "DRAFT".equals(normalizeStatus(row.status())) ? "/initiate/" + row.id() : "/view-control/" + row.id();
    }

    private String calendarColor(DeadlineRow row, LocalDate today) {
        if (DeadlineOverdue.isCompleted(row.status())) {
            return "#9BA3B5";
        }
        if (DeadlineOverdue.isOverdue(row.status(), row.deadline(), today)) {
            return "#C8102E";
        }
        if (DeadlineOverdue.isDueSoon(row.status(), row.deadline(), today, CALENDAR_DUE_SOON_DAYS)) {
            return "#D4A843";
        }
        return "#005EB8";
    }

    private DashboardChartDataDTO buildFrequencyBreakdown(List<Control> controls) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("Monthly", 0L);
        counts.put("Quarterly", 0L);
        counts.put("Recurring", 0L);
        counts.put("Annual", 0L);
        counts.put("Semi Annual", 0L);
        counts.put("Ad-Hoc", 0L);
        counts.put("Unspecified", 0L);

        for (Control control : safeControls(controls)) {
            String label = normalizeFrequency(control.getControlFrequency());
            counts.compute(label, (key, value) -> value == null ? 1L : value + 1);
        }

        counts.entrySet().removeIf(entry -> entry.getValue() == 0L);
        return toChartData(counts);
    }

    private DashboardChartDataDTO buildComponentBreakdown(List<Control> controls) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("HR", 0L);
        counts.put("A&C", 0L);
        counts.put("EP", 0L);
        counts.put("INTR", 0L);
        counts.put("I&C", 0L);
        counts.put("RER", 0L);
        counts.put("M&R", 0L);
        counts.put("GOV", 0L);
        counts.put("TECHR", 0L);
        counts.put("RAP", 0L);

        for (Control control : safeControls(controls)) {
            String label = normalizeComponent(control.getComponent());
            if (label == null) {
                continue;
            }
            counts.computeIfPresent(label, (key, value) -> value + 1);
        }

        counts.entrySet().removeIf(entry -> entry.getValue() == 0L);
        return toChartData(counts);
    }

    private DashboardChartDataDTO toChartData(Map<String, Long> counts) {
        return new DashboardChartDataDTO(
                new ArrayList<>(counts.keySet()),
                new ArrayList<>(counts.values())
        );
    }

    private DashboardChartDataDTO toTrendChartData(Map<LocalDate, Long> grouped) {
        List<String> labels = new ArrayList<>(grouped.size());
        List<Long> values = new ArrayList<>(grouped.size());
        for (Map.Entry<LocalDate, Long> entry : grouped.entrySet()) {
            labels.add(entry.getKey().format(TREND_LABEL_FORMAT));
            values.add(entry.getValue());
        }
        return new DashboardChartDataDTO(labels, values);
    }

    /**
     * The controls the user's tiles and Controls list cover, with their deadlines: the controls they see,
     * drafts included as far as they see them ({@link AccessPolicy#canView}).
     */
    private List<DeadlineRow> visibleDeadlineRows(User currentUser) {
        if (currentUser == null || currentUser.getMail() == null || currentUser.getMail().isBlank()) {
            return Collections.emptyList();
        }
        return toDeadlineRows(safeControls(controlService.findVisibleControlsForUser(currentUser)));
    }

    private List<DeadlineRow> toDeadlineRows(List<Control> controls) {
        Map<Long, Control> byId = new LinkedHashMap<>();
        for (Control control : safeControls(controls)) {
            if (control.getId() != null) {
                byId.putIfAbsent(control.getId(), control);
            }
        }
        Map<Long, LocalDate> operationDeadlines = findOperationDeadlines(byId.keySet());
        List<DeadlineRow> rows = new ArrayList<>(byId.size());
        for (Control control : byId.values()) {
            rows.add(new DeadlineRow(
                    control.getId(),
                    control.getControlId(),
                    control.getControlDescription(),
                    control.getPerformanceStatus(),
                    DeadlineOverdue.deadlineOf(operationDeadlines.get(control.getId()), control.getDeadline())));
        }
        return rows;
    }

    // The assignment's operation deadline is on the same controls row (mapped by ControlAssignment)
    private Map<Long, LocalDate> findOperationDeadlines(Collection<Long> controlIds) {
        if (controlIds.isEmpty()) {
            return Collections.emptyMap();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT c.id, c.control_operation_deadline
                FROM controls c
                WHERE c.id IN (:controlIds)
                  AND c.control_operation_deadline IS NOT NULL
                """)
                .setParameter("controlIds", new ArrayList<>(controlIds))
                .getResultList();
        Map<Long, LocalDate> deadlines = new HashMap<>();
        for (Object[] row : rows) {
            LocalDate deadline = toLocalDate(row[1]);
            if (row[0] instanceof Number id && deadline != null) {
                deadlines.put(id.longValue(), deadline);
            }
        }
        return deadlines;
    }

    private List<Control> findMyScopedControls(User currentUser) {
        if (currentUser == null || currentUser.getMail() == null || currentUser.getMail().isBlank()) {
            return Collections.emptyList();
        }
        List<Control> candidates = controlService.findVisibleControlsForUser(currentUser);
        if (AccessPolicy.chartsCoverEveryVisibleControl(AccessPolicy.Subject.of(currentUser))) {
            // KDN: every KDN control, as their tiles and Controls list
            return safeControls(candidates);
        }
        Predicate<Control> predicate = buildMyScopePredicate(currentUser);
        return safeControls(candidates).stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    private List<Control> findMyScopedNonDraftControls(User currentUser) {
        return findMyScopedControls(currentUser).stream()
                .filter(control -> !"DRAFT".equals(normalizeStatus(control.getPerformanceStatus())))
                .collect(Collectors.toList());
    }

    private Predicate<Control> buildMyScopePredicate(User currentUser) {
        Set<String> identities = resolveUserIdentities(currentUser);
        return control -> {
            if (control == null || control.getId() == null || identities.isEmpty()) {
                return false;
            }
            try {
                ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                if (assignment == null) {
                    return false;
                }
                return containsIdentity(assignment.getControlOperator(), identities)
                        || containsIdentity(assignment.getFacilitator(), identities)
                        || containsIdentity(assignment.getProcessOwner(), identities)
                        || containsIdentity(assignment.getControlSharedWith(), identities);
            } catch (Exception ex) {
                return false;
            }
        };
    }

    private Set<String> resolveUserIdentities(User currentUser) {
        Set<String> identities = new LinkedHashSet<>();
        if (currentUser == null) {
            return identities;
        }
        if (currentUser.getMail() != null && !currentUser.getMail().isBlank()) {
            identities.add(currentUser.getMail().trim().toLowerCase(Locale.ROOT));
        }
        if (currentUser.getId() != null) {
            identities.add(String.valueOf(currentUser.getId()).trim().toLowerCase(Locale.ROOT));
        }
        return identities;
    }

    private boolean containsIdentity(List<String> values, Set<String> identities) {
        if (values == null || values.isEmpty() || identities == null || identities.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (identities.contains(value.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<Control> safeControls(List<Control> controls) {
        if (controls == null || controls.isEmpty()) {
            return Collections.emptyList();
        }
        return controls.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeFrequency(String frequency) {
        if (frequency == null || frequency.isBlank()) {
            return "Unspecified";
        }

        String normalized = frequency.trim()
                .replace('_', ' ')
                .replace('-', ' ')
                .toLowerCase(Locale.ROOT);

        if ("monthly".equals(normalized)) {
            return "Monthly";
        }
        if ("quarterly".equals(normalized)) {
            return "Quarterly";
        }
        if ("recurring".equals(normalized)) {
            return "Recurring";
        }
        if ("annual".equals(normalized) || "annually".equals(normalized)) {
            return "Annual";
        }
        if ("semi annual".equals(normalized)
                || "semi annually".equals(normalized)
                || "semiannually".equals(normalized)
                || "semiannual".equals(normalized)) {
            return "Semi Annual";
        }
        if ("ad hoc".equals(normalized) || "adhoc".equals(normalized)) {
            return "Ad-Hoc";
        }

        return toTitleCase(normalized);
    }

    private String normalizeComponent(String component) {
        if (component == null || component.isBlank()) {
            return null;
        }
        String normalized = component.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "HR", "A&C", "EP", "INTR", "I&C", "RER", "M&R", "GOV", "TECHR", "RAP" -> normalized;
            default -> null;
        };
    }

    private String toTitleCase(String value) {
        String[] parts = value.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.length() == 0 ? "Unspecified" : builder.toString();
    }

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return null;
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String shortenText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "Untitled Control";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private LocalDate today() {
        return DeadlineOverdue.today(Instant.now());
    }

    /** What the deadline views need of a control; {@code deadline} is already resolved by DeadlineOverdue.deadlineOf. */
    private record DeadlineRow(Long id, String controlId, String description, String status, LocalDate deadline) {
    }
}
