package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.DeletedUserRepository;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Create Control: the next cycle of a Monthly or Quarterly control, made by hand from View Control by SoQM Admin Team
 * (business decision of 2026-10-14). The plan and its preview, the checks, and the creation with its audit entries;
 * what is copied and what is not is in ControlNextCycle, the proposed ID in ControlIdGeneratorService.nextPeriodId.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlCycleService {

    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;
    private final ControlScheduleCalculator scheduleCalculator;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlIdGeneratorService controlIdGeneratorService;
    private final ControlDetailsRepository detailsRepository;
    private final UserRepository userRepository;
    private final DeletedUserRepository deletedUserRepository;
    private final ControlRowLock controlRowLock;
    private final ControlInitiation controlInitiation;
    private final AdminAuditService adminAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The audit entry of the new control: who made it with Create Control, from which control, under which ID. */
    public static final String AUDIT_CREATED_FROM = "CREATED_FROM";
    /** The audit entry of the source: its next control was made, under which ID. */
    public static final String AUDIT_NEXT_CREATED = "NEXT_CONTROL_CREATED";
    public static final String FIELD_CREATED_FROM = "Created from";
    public static final String FIELD_CONTROL_ID = "Control ID";
    public static final String FIELD_NEXT_CONTROL = "Next control";

    /**
     * Why Create Control does not make the next cycle: the HTTP status, the message, the lines to fix on the source's
     * Assignment tab, and the control already there (409). Thrown inside the transaction, so nothing is kept.
     */
    public static final class NextCycleRefused extends RuntimeException {
        private final int status;
        private final List<String> problems;
        private final transient Control existing;

        NextCycleRefused(int status, String message, List<String> problems, Control existing) {
            super(message);
            this.status = status;
            this.problems = problems == null ? List.of() : List.copyOf(problems);
            this.existing = existing;
        }

        public int status() {
            return status;
        }

        public List<String> problems() {
            return problems;
        }

        public Control existing() {
            return existing;
        }
    }

    /**
     * The next cycle as Create Control would make it from the source, nothing saved: the ID proposed (empty when the
     * source's ID is not in a form that moves on), the dates and the people, with whatever stops it.
     */
    public record NextCyclePlan(Control source, ControlAssignment sourceAssignment, ControlFrequency frequency,
                                String proposedControlId, ControlNextCycle.Dates dates, ControlNextCycle.People people,
                                List<String> problems) {
    }

    /** The cycle made: its id and Control ID, the source's Control ID, and who was left out of Shared With. */
    public record NextCycleCreated(Long id, String controlId, String sourceControlId, List<String> sharedWithLeftOut) {
    }

    /**
     * The plan of the next cycle of the source. Problems, to be fixed on the source's Assignment tab: the four people
     * (ControlNextCycle.people) and the Next Control Operation Date, which becomes the new cycle's operation date.
     *
     * @param newControlId the ID the new cycle gets, when already typed (it decides whether it is a KDN control);
     *                     blank: the one proposed, else the source's
     */
    public NextCyclePlan planNextCycle(Control source, String newControlId) {
        ControlAssignment sourceAssignment = assignmentRepository.findByControlId(source.getId())
                .orElseGet(ControlAssignment::new);
        ControlFrequency frequency = ControlFrequency.tryFromValue(source.getControlFrequency()).orElse(null);
        String proposed = ControlIdGeneratorService.nextPeriodId(source.getControlId(), frequency).orElse("");
        ControlNextCycle.Dates dates = frequency == null ? null
                : ControlNextCycle.dates(scheduleCalculator, frequency, sourceAssignment.getNextControlOperationDate());
        String idForKdn = newControlId != null && !newControlId.isBlank() ? newControlId
                : !proposed.isEmpty() ? proposed : source.getControlId();
        ControlNextCycle.People people = ControlNextCycle.people(sourceAssignment, AccessPolicy.isKdnControl(idForKdn),
                userRepository::findByMail,
                mail -> !deletedUserRepository.findByMailLowerIn(List.of(mail.trim().toLowerCase(Locale.ROOT))).isEmpty());
        List<String> problems = new ArrayList<>(people.problems());
        if (sourceAssignment.getNextControlOperationDate() == null) {
            problems.add("Next Control Operation Date: empty (it becomes the Control Operation Date of the new control)");
        }
        return new NextCyclePlan(source, sourceAssignment, frequency, proposed, dates, people, List.copyOf(problems));
    }

    /**
     * The preview of Create Control for its confirmation, nothing saved: 400 when the source makes no next cycle
     * (AccessPolicy.nextCycleRefusal); whatever else stops it is in the plan's problems.
     */
    @Transactional(readOnly = true)
    public NextCyclePlan previewNextCycle(Long sourceId) {
        Control source = controlRepository.findById(sourceId)
                .orElseThrow(() -> new NextCycleRefused(404, "Control not found", null, null));
        refuseUnlessMakesNextCycle(source);
        NextCyclePlan plan = planNextCycle(source, null);
        refuseIfNextCycleExists(plan);
        return plan;
    }

    /**
     * 409 when the next cycle is already there: a control made from this one with Create Control (created_from, whatever
     * its ID), or a control of the same base ID on the new operation date (ControlAssignmentRepository
     * .CYCLE_OF_BASE_ON_DAY: a cycle without created_from, made before Create Control, by New Control or by the
     * import). A double click, or two people at once, get the one control the first request made.
     */
    private void refuseIfNextCycleExists(NextCyclePlan plan) {
        Control source = plan.source();
        controlRepository.findByCreatedFromControlIdOrderByIdAsc(source.getId()).stream().findFirst()
                .ifPresent(existing -> {
                    throw new NextCycleRefused(409, "The next control of " + source.getControlId()
                            + " already exists: " + existing.getControlId(), null, existing);
                });
        if (plan.dates() == null) {
            return;
        }
        String baseControlId = controlIdGeneratorService.extractBaseId(source.getControlId());
        assignmentRepository.findIdsByBaseControlIdAndOperationDate(baseControlId, escapeForLike(baseControlId),
                        plan.dates().operationDate()).stream()
                .filter(id -> !id.equals(source.getId()))
                .findFirst()
                .flatMap(controlRepository::findById)
                .ifPresent(existing -> {
                    throw new NextCycleRefused(409, "The next control of " + source.getControlId()
                            + " already exists: " + existing.getControlId(), null, existing);
                });
    }

    private static void refuseUnlessMakesNextCycle(Control source) {
        AccessPolicy.nextCycleRefusal(source.getControlFrequency(), source.getPerformanceStatus(),
                source.getControlStatus()).ifPresent(reason -> {
                    throw new NextCycleRefused(400, reason, null, null);
                });
    }

    /**
     * Create Control: the next cycle of the source, made by the person who pressed it and initiated at once (In
     * Progress, as Initiate does it: workflow steps, "Initiated" in the history by that person, Control Initiated to
     * the Facilitators). One transaction: the source's row is locked first, so a second request for the same source
     * waits and then finds what the first one did; every check runs before anything is written, and a failure after
     * that leaves nothing behind.
     *
     * @param requestedControlId the ID typed in the confirmation; blank takes the one proposed
     */
    @Transactional
    public NextCycleCreated createNextCycle(Long sourceId, String requestedControlId, User actor) {
        Control source = controlRepository.findById(sourceId)
                .orElseThrow(() -> new NextCycleRefused(404, "Control not found", null, null));
        source = controlRowLock.lockedFresh(source);

        String typed = requestedControlId == null ? "" : requestedControlId.trim();
        refuseUnlessMakesNextCycle(source);
        NextCyclePlan plan = planNextCycle(source, typed);
        refuseIfNextCycleExists(plan);
        if (!plan.problems().isEmpty()) {
            throw new NextCycleRefused(400, "The next control cannot be created. Fix this on the Assignment tab of "
                    + source.getControlId() + ":", plan.problems(), null);
        }
        String newControlId = !typed.isEmpty() ? typed : plan.proposedControlId();
        if (newControlId.isEmpty()) {
            throw new NextCycleRefused(400, "Enter the Control ID of the new control", null, null);
        }
        List<Control> sameId = controlRepository.findByControlIdIgnoringCase(newControlId);
        if (!sameId.isEmpty()) {
            throw new NextCycleRefused(409, "Control ID " + sameId.get(0).getControlId()
                    + " is already used. Enter another Control ID.", null, sameId.get(0));
        }

        LocalDateTime now = LocalDateTime.now(Notification.ZONE);
        Control saved = controlRepository.save(ControlNextCycle.control(source, newControlId, plan.dates(), actor, now));
        assignmentRepository.save(ControlNextCycle.assignment(plan.sourceAssignment(), saved.getId(), plan.dates(),
                plan.people().sharedWith()));
        ControlDetails sourceDetails = detailsRepository.findByControlId(source.getId()).orElse(null);
        detailsRepository.save(ControlNextCycle.details(sourceDetails, saved.getId()));

        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(saved.getId());
        List<String> missing = ControlInitiation.missing(saved, assignment, null);
        if (!missing.isEmpty()) {
            throw new NextCycleRefused(400, "The next control cannot be initiated. Missing: "
                    + String.join(", ", missing), missing, null);
        }
        // Who made it, from which control, under which ID: on both controls, before anyone is told
        audit(saved, actor, AUDIT_CREATED_FROM, "Created from " + source.getControlId() + " with Create Control",
                Map.of(), ordered(FIELD_CREATED_FROM, source.getControlId(), FIELD_CONTROL_ID, saved.getControlId()));
        audit(source, actor, AUDIT_NEXT_CREATED, "Next control " + saved.getControlId() + " created with Create Control",
                Map.of(), ordered(FIELD_NEXT_CONTROL, saved.getControlId()));
        controlInitiation.start(saved, assignment, null, actor);
        log.info("Create Control: {} created from {} by {}", saved.getControlId(), source.getControlId(),
                actor != null ? actor.getMail() : "unknown");
        return new NextCycleCreated(saved.getId(), saved.getControlId(), source.getControlId(),
                plan.people().sharedWithLeftOut());
    }

    private static Map<String, Object> ordered(String... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** An admin_audit_log entry, shown in the control's Changelog; without it nothing is kept (the creation rolls back). */
    private void audit(Control control, User actor, String action, String description, Map<String, Object> previous,
                       Map<String, Object> next) {
        AdminAuditLog entry;
        try {
            entry = adminAuditService.logActionWithChanges(actor != null ? actor.getMail() : null,
                    actor != null ? actor.getDisplayName() : null, action, control, description,
                    objectMapper.writeValueAsString(List.copyOf(next.keySet())),
                    objectMapper.writeValueAsString(previous), objectMapper.writeValueAsString(next));
        } catch (JsonProcessingException e) {
            entry = null;
        }
        if (entry == null) {
            throw new IllegalStateException("Create Control could not be recorded in the audit log and was not saved");
        }
    }

    private static String escapeForLike(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
