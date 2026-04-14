package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControlAutoCreationService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final DateTimeFormatter MONTH_SUFFIX = DateTimeFormatter.ofPattern("MMM-yyyy", Locale.ENGLISH);
    private static final Pattern BASE_ID_PATTERN = Pattern.compile("^(.*)_\\d{4}-\\d{2}$");
    private static final Pattern BASE_ID_MONTH_PATTERN =
            Pattern.compile("^(.*)_[A-Za-z]{3}-\\d{4}( \\(\\d+\\))?$");
    private static final int MAX_ID_SUFFIX = 1000;

    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;
    private final ControlScheduleCalculator scheduleCalculator;
    private final ControlAssignmentService controlAssignmentService;
    private final NotificationService notificationService;
    private final ControlIdGeneratorService controlIdGeneratorService;

    @Transactional
    public void runDailyAutoCreation(LocalDate today) {
        if (today == null) {
            return;
        }
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime nextDayStart = today.plusDays(1).atStartOfDay();
        runDailyAutoCreation(dayStart, nextDayStart);
    }

    @Transactional
    public AutoCreationRunSummary runDailyAutoCreation(LocalDateTime dayStart, LocalDateTime nextDayStart) {
        if (dayStart == null || nextDayStart == null) {
            return new AutoCreationRunSummary(0, 0, 0);
        }
        LocalDate today = dayStart.toLocalDate();
        AutoCreationRunSummary monthlySummary = processDueAssignments(
                "monthly",
                assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart),
                today,
                dayStart,
                nextDayStart
        );
        AutoCreationRunSummary quarterlySummary = processDueAssignments(
                "quarterly",
                assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart),
                today,
                dayStart,
                nextDayStart
        );
        AutoCreationRunSummary recurringSummary = processDueAssignments(
                "recurring",
                assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart),
                today,
                dayStart,
                nextDayStart
        );
        AutoCreationRunSummary annualSummary = processDueAssignments(
                "annual",
                assignmentRepository.findAnnualByNextControlOperationDateRange(dayStart, nextDayStart),
                today,
                dayStart,
                nextDayStart
        );
        AutoCreationRunSummary semiAnnualSummary = processDueAssignments(
                "semi-annual",
                assignmentRepository.findSemiAnnualByNextControlOperationDateRange(dayStart, nextDayStart),
                today,
                dayStart,
                nextDayStart
        );

        int candidates = monthlySummary.candidates()
                + quarterlySummary.candidates()
                + recurringSummary.candidates()
                + annualSummary.candidates()
                + semiAnnualSummary.candidates();
        int created = monthlySummary.created()
                + quarterlySummary.created()
                + recurringSummary.created()
                + annualSummary.created()
                + semiAnnualSummary.created();
        int skippedDuplicates = monthlySummary.duplicatesSkipped()
                + quarterlySummary.duplicatesSkipped()
                + recurringSummary.duplicatesSkipped()
                + annualSummary.duplicatesSkipped()
                + semiAnnualSummary.duplicatesSkipped();
        log.info("Auto-create summary: candidates={}, created={}, duplicatesSkipped={}",
                candidates, created, skippedDuplicates);
        return new AutoCreationRunSummary(candidates, created, skippedDuplicates);
    }

    AutoCreationResult createNextOccurrenceIfDue(Control previousControl,
                                                 ControlAssignment previousAssignment,
                                                 LocalDate today) {
        if (previousControl == null || previousAssignment == null || today == null) {
            return new AutoCreationResult(false, null, null, false);
        }

        LocalDate nextOperationDate = previousAssignment.getNextControlOperationDate();
        if (nextOperationDate == null || !nextOperationDate.equals(today)) {
            return new AutoCreationResult(false, null, null, false);
        }

        ControlFrequency frequency;
        try {
            frequency = ControlFrequency.fromValue(previousControl.getControlFrequency());
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping auto-creation for control {} due to invalid frequency: {}",
                    previousControl.getId(), previousControl.getControlFrequency());
            return new AutoCreationResult(false, null, null, false);
        }
        if (frequency != ControlFrequency.MONTHLY
                && frequency != ControlFrequency.QUARTERLY
                && frequency != ControlFrequency.RECURRING
                && frequency != ControlFrequency.ANNUAL
                && frequency != ControlFrequency.SEMI_ANNUAL) {
            return new AutoCreationResult(false, null, null, false);
        }

        String baseControlId = controlIdGeneratorService.extractBaseId(previousControl.getControlId());
        String baseControlIdLike = escapeForLike(baseControlId);
        if (assignmentRepository.existsByBaseControlIdAndOperationDate(baseControlId, baseControlIdLike, today)) {
            return new AutoCreationResult(false, null, null, true);
        }

        String newControlId = controlIdGeneratorService.generateNextPeriodControlId(
                baseControlId, previousControl.getControlFrequency(), today);
        LocalDate deadline = scheduleCalculator.calculateDeadline(frequency, today);
        LocalDate nextDate = scheduleCalculator.calculateNextDate(frequency, today);

        Control newControl = buildNewControl(previousControl, newControlId, deadline);
        Control savedControl = controlRepository.save(newControl);

        ControlAssignment newAssignment = buildNewAssignment(previousAssignment, savedControl.getId(),
                today, deadline, nextDate);
        ControlAssignment savedAssignment = assignmentRepository.save(newAssignment);
        try {
            controlAssignmentService.recalculateSchedule(savedControl.getId());
        } catch (Exception ex) {
            log.warn("Auto-create: failed to recalculate schedule for control {}", savedControl.getId(), ex);
        }

        notifyAutoCreated(savedControl, today, previousControl.getControlFrequency());
        return new AutoCreationResult(true, savedControl, savedAssignment, false);
    }

    private Control buildNewControl(Control previousControl, String newControlId, LocalDate deadline) {
        Control control = new Control();
        control.setControlId(newControlId);
        control.setControlFrequency(previousControl.getControlFrequency());
        control.setControlCategory(previousControl.getControlCategory());
        control.setControlType(previousControl.getControlType());
        control.setComponent(previousControl.getComponent());
        control.setOperatedBy(previousControl.getOperatedBy());
        control.setReferencesToControl(previousControl.getReferencesToControl());
        control.setPriority(previousControl.getPriority());
        control.setNonAuditServicesApplicability(previousControl.getNonAuditServicesApplicability());
        control.setHomogeneity(previousControl.getHomogeneity());
        control.setControlStatus(previousControl.getControlStatus());
        control.setPerformanceStatus(STATUS_DRAFT);
        control.setControlDescription(previousControl.getControlDescription());
        control.setPrp(previousControl.getPrp());
        control.setCreatedBy(previousControl.getCreatedBy());
        control.setCreatedAt(LocalDateTime.now());
        control.setUpdatedAt(LocalDateTime.now());
        control.setDeadline(deadline);

        control.setSoqmHeadComments(null);
        control.setProcessOwnerComments(null);
        control.setAttachmentDetailsPath(null);
        control.setAttachmentDocumentsPath(null);

        return control;
    }

    private ControlAssignment buildNewAssignment(ControlAssignment previousAssignment,
                                                 Long newControlId,
                                                 LocalDate operationDate,
                                                 LocalDate deadline,
                                                 LocalDate nextDate) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(newControlId);
        assignment.setFacilitator(previousAssignment.getFacilitator());
        assignment.setControlOperator(previousAssignment.getControlOperator());
        assignment.setSoqmLead(previousAssignment.getSoqmLead());
        assignment.setProcessOwner(previousAssignment.getProcessOwner());
        assignment.setControlSharedWith(previousAssignment.getControlSharedWith());
        assignment.setControlOperationDate(operationDate);
        assignment.setControlOperationDeadline(deadline);
        assignment.setNextControlOperationDate(nextDate);
        return assignment;
    }

    private String resolveBaseControlId(String controlId) {
        if (controlId == null || controlId.isBlank()) {
            return "AUTO";
        }
        String trimmed = controlId.trim();
        Matcher matcher = BASE_ID_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            String base = matcher.group(1);
            if (base != null && !base.isBlank()) {
                return base.trim();
            }
        }
        Matcher monthMatcher = BASE_ID_MONTH_PATTERN.matcher(trimmed);
        if (monthMatcher.matches()) {
            String base = monthMatcher.group(1);
            if (base != null && !base.isBlank()) {
                return base.trim();
            }
        }
        return trimmed;
    }

    private String generateUniqueControlId(String baseId, LocalDate operationDate) {
        String safeBase = (baseId == null || baseId.isBlank()) ? "AUTO" : baseId.trim();
        String datePart = operationDate.format(MONTH_SUFFIX);
        String candidate = safeBase + "_" + datePart;
        if (!controlRepository.existsByControlId(candidate)) {
            return candidate;
        }
        for (int suffix = 1; suffix <= MAX_ID_SUFFIX; suffix++) {
            String withSuffix = candidate + " (" + suffix + ")";
            if (!controlRepository.existsByControlId(withSuffix)) {
                return withSuffix;
            }
        }
        throw new IllegalStateException("Unable to generate unique controlId for baseId=" + safeBase
                + " and date=" + datePart);
    }

    private String escapeForLike(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private void notifyAutoCreated(Control control, LocalDate operationDate, String frequencyLabel) {
        if (control == null || operationDate == null) {
            return;
        }
        String recipientEmail = null;
        try {
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            if (assignment != null && assignment.getSoqmLead() != null) {
                for (String email : assignment.getSoqmLead()) {
                    if (email != null && !email.isBlank()) {
                        recipientEmail = email.trim();
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Auto-create: failed to resolve SoQM assignee for control {}", control.getId(), ex);
        }
        if (recipientEmail == null && control.getCreatedBy() != null) {
            String creatorRole = control.getCreatedBy().getRole();
            String creatorEmail = control.getCreatedBy().getMail();
            if ("SOQM_TEAM".equals(creatorRole) && creatorEmail != null && !creatorEmail.isBlank()) {
                recipientEmail = creatorEmail.trim();
            }
        }
        if (recipientEmail == null) {
            log.info("Auto-create: no SoQM recipient for control {}", control.getId());
            return;
        }
        notificationService.sendAutoCreatedNotification(control, recipientEmail, frequencyLabel, operationDate);
    }

    private AutoCreationRunSummary processDueAssignments(String frequencyLabel,
                                                         List<ControlAssignment> dueAssignments,
                                                         LocalDate today,
                                                         LocalDateTime dayStart,
                                                         LocalDateTime nextDayStart) {
        if (dueAssignments == null || dueAssignments.isEmpty()) {
            log.info("Auto-create: no {} candidates between {} and {}", frequencyLabel, dayStart, nextDayStart);
            return new AutoCreationRunSummary(0, 0, 0);
        }
        log.info("Auto-create: {} candidates found between {} and {}: {}",
                frequencyLabel, dayStart, nextDayStart, dueAssignments.size());
        int candidates = dueAssignments.size();
        int created = 0;
        int skippedDuplicates = 0;
        for (ControlAssignment assignment : dueAssignments) {
            if (assignment == null) {
                continue;
            }
            try {
                Control previousControl = controlRepository.findById(assignment.getControlId()).orElse(null);
                if (previousControl == null) {
                    log.warn("Auto-create: missing control for assignment controlId={} (frequency={})",
                            assignment.getControlId(), frequencyLabel);
                    continue;
                }
                AutoCreationResult result = createNextOccurrenceIfDue(previousControl, assignment, today);
                if (!result.created()) {
                    if (result.duplicateSkipped()) {
                        skippedDuplicates++;
                    }
                    continue;
                }
                created++;
            } catch (Exception ex) {
                log.error("Auto-create failed for assignment controlId={} (frequency={})",
                        assignment.getControlId(), frequencyLabel, ex);
            }
        }
        return new AutoCreationRunSummary(candidates, created, skippedDuplicates);
    }

    public record AutoCreationResult(boolean created,
                                     Control newControl,
                                     ControlAssignment newAssignment,
                                     boolean duplicateSkipped) {
    }

    public record AutoCreationRunSummary(int candidates,
                                         int created,
                                         int duplicatesSkipped) {
    }
}
