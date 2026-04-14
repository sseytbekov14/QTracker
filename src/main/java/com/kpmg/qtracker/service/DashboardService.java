package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.DashboardCalendarEventDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.dto.DashboardDeadlineCountdownItemDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final DateTimeFormatter TREND_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final String COMPLETED_SQL = """
            (
                COALESCE(UPPER(TRIM(c.performance_status)), 'DRAFT') = 'COMPLETED'
                OR EXISTS (
                    SELECT 1
                    FROM workflow_steps ws
                    WHERE ws.control_id = c.id
                      AND ws.completed_at IS NOT NULL
                )
                OR EXISTS (
                    SELECT 1
                    FROM workflow_history wh
                    WHERE wh.control_id = c.id
                      AND (
                          (wh.to_step IS NOT NULL AND UPPER(TRIM(wh.to_step)) = 'COMPLETED')
                          OR (
                              wh.action_type = 'APPROVE'
                              AND wh.from_step IS NOT NULL
                              AND UPPER(TRIM(wh.from_step)) = 'PROCESS_OWNER_REVIEW'
                          )
                      )
                )
            )
            """;

    private final ControlRepository controlRepository;
    private final IControlService controlService;
    private final ControlAssignmentService controlAssignmentService;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowHistoryRepository workflowHistoryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public DashboardChartDataDTO getStatusBreakdown() {
        DashboardKpiCounts counts = loadKpiCounts();
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

    public DashboardKpiCounts getKpiCounts() {
        return loadKpiCounts();
    }

    public DashboardChartDataDTO getMyFrequencyBreakdown(User currentUser) {
        return buildFrequencyBreakdown(findMyScopedNonDraftControls(currentUser));
    }

    public DashboardChartDataDTO getMyComponentBreakdown(User currentUser) {
        return buildComponentBreakdown(findMyScopedNonDraftControls(currentUser));
    }

    public DashboardChartDataDTO getMyOverdueTrend(User currentUser) {
        List<Control> visibleControls = findMyScopedNonDraftControls(currentUser);
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(29);
        Map<LocalDate, Long> grouped = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            grouped.put(date, 0L);
        }

        Map<Long, LocalDateTime> completionByControlId = resolveCompletionTimes(visibleControls);
        for (Control control : visibleControls) {
            LocalDate deadline = control.getDeadline();
            if (deadline == null || deadline.isBefore(startDate) || !deadline.isBefore(today)) {
                continue;
            }
            if (isCompletedForDashboard(control, completionByControlId)) {
                continue;
            }
            grouped.computeIfPresent(deadline, (key, value) -> value + 1);
        }

        return toTrendChartData(grouped);
    }

    public DashboardChartDataDTO getFrequencyBreakdown() {
        return buildFrequencyBreakdown(controlRepository.findAll());
    }

    public DashboardChartDataDTO getOverdueTrend() {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(29);

        Map<LocalDate, Long> grouped = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            grouped.put(date, 0L);
        }

        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT c.control_operation_deadline, COUNT(*)
                FROM controls c
                WHERE c.control_operation_deadline >= :startDate
                  AND c.control_operation_deadline < CURRENT_DATE
                  AND NOT %s
                GROUP BY c.control_operation_deadline
                ORDER BY c.control_operation_deadline
                """.formatted(COMPLETED_SQL))
                .setParameter("startDate", startDate)
                .getResultList();

        for (Object[] row : rows) {
            LocalDate deadline = toLocalDate(row[0]);
            if (deadline == null) {
                continue;
            }
            grouped.computeIfPresent(deadline, (key, value) -> value + toLong(row[1]));
        }

        return toTrendChartData(grouped);
    }

    public List<DashboardDeadlineCountdownItemDTO> getDeadlineCountdown(User currentUser, int days, int limit) {
        if (currentUser == null) {
            return Collections.emptyList();
        }
        DeadlineScopeSql scope = buildDeadlineScope(currentUser);
        if (scope.blocked()) {
            return Collections.emptyList();
        }

        LocalDate startDate = LocalDate.now();
        LocalDate endDate = startDate.plusDays(Math.max(days, 0));
        int safeLimit = Math.max(1, limit);

        String sql = """
                SELECT c.id,
                       c.control_id,
                       c.control_description,
                       c.control_operation_deadline,
                       COALESCE(UPPER(TRIM(c.performance_status)), 'DRAFT') AS effective_status
                FROM controls c
                %2$s
                  AND COALESCE(UPPER(TRIM(c.performance_status)), 'DRAFT') <> 'DRAFT'
                  AND NOT %1$s
                  AND c.control_operation_deadline >= :startDate
                  AND c.control_operation_deadline <= :endDate
                ORDER BY c.control_operation_deadline ASC, c.id ASC
                """.formatted(COMPLETED_SQL, scope.whereClause());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = applyScopeParameters(entityManager.createNativeQuery(sql), scope)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setMaxResults(safeLimit)
                .getResultList();

        List<DashboardDeadlineCountdownItemDTO> items = new ArrayList<>();
        for (Object[] row : rows) {
            LocalDateTime deadline = toDeadlineDateTime(row[3]);
            if (row == null || row.length < 5 || row[0] == null || deadline == null) {
                continue;
            }
            Long id = ((Number) row[0]).longValue();
            String controlId = row[1] != null ? row[1].toString() : "Control";
            String name = shortenText(row[2] != null ? row[2].toString() : "Untitled Control", 48);
            String status = row[4] != null ? row[4].toString() : "IN_PROGRESS";
            items.add(new DashboardDeadlineCountdownItemDTO(
                    id,
                    controlId,
                    name,
                    deadline,
                    status,
                    "/view-control/" + id
            ));
        }
        return items;
    }

    public List<DashboardCalendarEventDTO> getDeadlineCalendar(User currentUser, LocalDate start, LocalDate end) {
        if (currentUser == null || start == null || end == null || !end.isAfter(start)) {
            return Collections.emptyList();
        }
        DeadlineScopeSql scope = buildDeadlineScope(currentUser);
        if (scope.blocked()) {
            return Collections.emptyList();
        }

        String sql = """
                SELECT c.id,
                       c.control_id,
                       DATE(c.control_operation_deadline) AS event_start,
                       CASE
                           WHEN %1$s THEN '#9BA3B5'
                           WHEN c.control_operation_deadline < CURRENT_DATE THEN '#C8102E'
                           WHEN c.control_operation_deadline <= :dueSoonDate THEN '#D4A843'
                           ELSE '#005EB8'
                       END AS event_color
                FROM controls c
                %2$s
                  AND c.control_operation_deadline >= :startDate
                  AND c.control_operation_deadline < :endDate
                ORDER BY c.control_operation_deadline ASC, c.id ASC
                """.formatted(COMPLETED_SQL, scope.whereClause());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = applyScopeParameters(entityManager.createNativeQuery(sql), scope)
                .setParameter("startDate", start)
                .setParameter("endDate", end)
                .setParameter("dueSoonDate", LocalDate.now().plusDays(3))
                .getResultList();

        List<DashboardCalendarEventDTO> events = new ArrayList<>();
        for (Object[] row : rows) {
            LocalDate deadline = toLocalDate(row[2]);
            if (row == null || row.length < 4 || row[0] == null || deadline == null) {
                continue;
            }
            Long id = ((Number) row[0]).longValue();
            String controlId = row[1] != null ? row[1].toString() : "Control";
            String color = row[3] != null ? row[3].toString() : "#005EB8";
            events.add(new DashboardCalendarEventDTO(
                    controlId,
                    deadline.toString(),
                    "/view-control/" + id,
                    color
                ));
        }
        return events;
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

    private DashboardKpiCounts loadKpiCounts() {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT
                    COUNT(*) AS total_count,
                    COALESCE(SUM(CASE WHEN %1$s THEN 1 ELSE 0 END), 0) AS completed_count,
                    COALESCE(SUM(CASE
                        WHEN NOT %1$s
                         AND c.control_operation_deadline < CURRENT_DATE
                        THEN 1 ELSE 0 END), 0) AS overdue_count,
                    COALESCE(SUM(CASE
                        WHEN NOT %1$s
                         AND (c.control_operation_deadline IS NULL OR c.control_operation_deadline >= CURRENT_DATE)
                        THEN 1 ELSE 0 END), 0) AS active_count
                FROM controls c
                """.formatted(COMPLETED_SQL))
                .getSingleResult();

        DashboardKpiCounts rawCounts = new DashboardKpiCounts(
                toLong(row[0]),
                toLong(row[3]),
                toLong(row[1]),
                toLong(row[2])
        );

        if (rawCounts.active() + rawCounts.completed() + rawCounts.overdue() == rawCounts.total()) {
            return rawCounts;
        }

        long adjustedCompleted = Math.min(Math.max(0L, rawCounts.completed()), rawCounts.total());
        long remainingAfterCompleted = Math.max(0L, rawCounts.total() - adjustedCompleted);
        long adjustedOverdue = Math.min(Math.max(0L, rawCounts.overdue()), remainingAfterCompleted);
        long adjustedActive = remainingAfterCompleted - adjustedOverdue;

        log.warn(
                "Dashboard KPI count drift detected. Raw counts total={}, active={}, completed={}, overdue={}. Using adjusted values active={}, completed={}, overdue={}.",
                rawCounts.total(),
                rawCounts.active(),
                rawCounts.completed(),
                rawCounts.overdue(),
                adjustedActive,
                adjustedCompleted,
                adjustedOverdue
        );

        return new DashboardKpiCounts(rawCounts.total(), adjustedActive, adjustedCompleted, adjustedOverdue);
    }

    private DeadlineScopeSql buildDeadlineScope(User currentUser) {
        if (currentUser == null) {
            return new DeadlineScopeSql("WHERE 1 = 0", Collections.emptyMap(), true);
        }

        StringBuilder where = new StringBuilder("WHERE c.control_operation_deadline IS NOT NULL");
        Map<String, Object> parameters = new LinkedHashMap<>();

        if (!isSoqmLead(currentUser)) {
            String email = currentUser.getMail();
            if (email == null || email.isBlank()) {
                return new DeadlineScopeSql("WHERE 1 = 0", Collections.emptyMap(), true);
            }

            where.append(" AND COALESCE(UPPER(TRIM(c.performance_status)), 'DRAFT') <> 'DRAFT'");
            where.append(" AND (");
            where.append(buildDelimitedMatchCondition("c.facilitator", "scopeEmailToken", "scopeIdToken"));
            where.append(" OR ");
            where.append(buildDelimitedMatchCondition("c.control_operator", "scopeEmailToken", "scopeIdToken"));
            where.append(" OR ");
            where.append(buildDelimitedMatchCondition("c.process_owner", "scopeEmailToken", "scopeIdToken"));
            where.append(" OR ");
            where.append(buildDelimitedMatchCondition("c.control_shared_with", "scopeEmailToken", "scopeIdToken"));
            where.append(")");

            String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
            String normalizedId = currentUser.getId() != null
                    ? String.valueOf(currentUser.getId()).trim().toLowerCase(Locale.ROOT)
                    : "__no_match__";
            parameters.put("scopeEmailToken", "%," + normalizedEmail + ",%");
            parameters.put("scopeIdToken", "%," + normalizedId + ",%");
        }

        return new DeadlineScopeSql(where.toString(), parameters, false);
    }

    private List<Control> findMyScopedControls(User currentUser) {
        if (currentUser == null || currentUser.getMail() == null || currentUser.getMail().isBlank()) {
            return Collections.emptyList();
        }
        List<Control> candidates = controlService.findVisibleControlsForUser(currentUser.getMail(), currentUser.getRole());
        Predicate<Control> predicate = buildMyScopePredicate(currentUser);
        return safeControls(candidates).stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    private String buildDelimitedMatchCondition(String columnName, String emailParam, String idParam) {
        String normalizedColumn = "LOWER(CONCAT(',', REPLACE(REPLACE(REPLACE(COALESCE(" + columnName + ", ''), ';', ','), ' ', ''), ',,', ','), ','))";
        return "(" + normalizedColumn + " LIKE :" + emailParam + " OR " + normalizedColumn + " LIKE :" + idParam + ")";
    }

    private jakarta.persistence.Query applyScopeParameters(jakarta.persistence.Query query, DeadlineScopeSql scope) {
        if (query == null || scope == null || scope.parameters() == null) {
            return query;
        }
        for (Map.Entry<String, Object> entry : scope.parameters().entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        return query;
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

    private Map<Long, LocalDateTime> resolveCompletionTimes(List<Control> controls) {
        List<Long> controlIds = safeControls(controls).stream()
                .map(Control::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (controlIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, LocalDateTime> completionByControlId = new HashMap<>();
        mergeCompletionRows(completionByControlId, workflowStepRepository.findLatestCompletedAtByControlIds(controlIds));
        mergeCompletionRows(completionByControlId, workflowHistoryRepository.findLatestCompletionTimestampByControlIds(controlIds));
        return completionByControlId;
    }

    private void mergeCompletionRows(Map<Long, LocalDateTime> target, List<Object[]> rows) {
        if (target == null || rows == null || rows.isEmpty()) {
            return;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Number) || !(row[1] instanceof LocalDateTime)) {
                continue;
            }
            Long controlId = ((Number) row[0]).longValue();
            LocalDateTime completionTime = (LocalDateTime) row[1];
            LocalDateTime existing = target.get(controlId);
            if (existing == null || completionTime.isAfter(existing)) {
                target.put(controlId, completionTime);
            }
        }
    }

    private boolean isCompletedForDashboard(Control control, Map<Long, LocalDateTime> completionByControlId) {
        if (control == null) {
            return false;
        }
        if ("COMPLETED".equals(normalizeStatus(control.getPerformanceStatus()))) {
            return true;
        }
        return control.getId() != null
                && completionByControlId != null
                && completionByControlId.containsKey(control.getId());
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isSoqmLead(User currentUser) {
        if (currentUser == null || currentUser.getRole() == null) {
            return false;
        }
        String normalized = currentUser.getRole().trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return "SOQM_TEAM".equals(normalized);
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

    private LocalDateTime toDeadlineDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.withSecond(0).withNano(0);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().withSecond(0).withNano(0);
        }
        LocalDate localDate = toLocalDate(value);
        return localDate != null ? localDate.atTime(23, 59) : null;
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

    public record DashboardKpiCounts(long total, long active, long completed, long overdue) {
    }

    private record DeadlineScopeSql(String whereClause, Map<String, Object> parameters, boolean blocked) {
    }
}
