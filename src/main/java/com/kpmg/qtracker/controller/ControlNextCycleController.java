package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.ControlCycleService;
import com.kpmg.qtracker.service.ControlCycleService.NextCycleCreated;
import com.kpmg.qtracker.service.ControlCycleService.NextCyclePlan;
import com.kpmg.qtracker.service.ControlCycleService.NextCycleRefused;
import com.kpmg.qtracker.service.ControlNextCycle;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlScheduleCalculator;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.util.EmailList;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Create Control on View Control (business decision of 2026-10-14): the preview for its confirmation (GET) and
 * the creation (POST) of the next cycle of a Monthly or Quarterly control. SoQM Admin Team only (403 for everyone
 * else, AccessPolicy.canCreateNextCycle), then the control itself (400, AccessPolicy.nextCycleRefusal). An answer
 * that is not 2xx carries {message, problems, existing}.
 */
@RestController
@RequestMapping("/api/controls/{id}/next-cycle")
@RequiredArgsConstructor
public class ControlNextCycleController {

    static final String NOT_ALLOWED = "Only SoQM Admin Team can create the next control";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final IControlService controlService;
    private final ControlPermissionService controlPermissionService;
    private final ControlCycleService controlCycleService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> preview(@PathVariable Long id, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        ResponseEntity<?> refused = refusedFor(id, currentUser);
        if (refused != null) {
            return refused;
        }
        try {
            NextCyclePlan plan = controlCycleService.previewNextCycle(id);
            Map<String, Object> out = describe(plan);
            // Notes, not refusals (business decision of 2026-10-21): the next cycle already made, the proposed ID taken
            out.put("existingNext", controlCycleService.existingNextCycle(plan).map(ControlNextCycleController::link)
                    .orElse(null));
            out.put("proposedTaken", controlCycleService.controlWithId(plan.proposedControlId())
                    .map(ControlNextCycleController::link).orElse(null));
            return ResponseEntity.ok(out);
        } catch (NextCycleRefused e) {
            return answer(e);
        }
    }

    /**
     * Whether a Control ID typed in the confirmation is already taken (ignoring case and surrounding spaces), for the
     * hint under the field: {taken, existing}. SoQM Admin Team only, as the rest of Create Control.
     */
    @GetMapping("/id-check")
    public ResponseEntity<?> idCheck(@PathVariable Long id, @RequestParam(required = false) String controlId,
                                     HttpSession session) {
        ResponseEntity<?> refused = refusedFor(id, (User) session.getAttribute("currentUser"));
        if (refused != null) {
            return refused;
        }
        Optional<Control> taken = controlCycleService.controlWithId(controlId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taken", taken.isPresent());
        out.put("existing", taken.map(ControlNextCycleController::link).orElse(null));
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> link(Control control) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", control.getId());
        out.put("controlId", control.getControlId());
        out.put("url", "/view-control/" + control.getId());
        return out;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                    HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        ResponseEntity<?> refused = refusedFor(id, currentUser);
        if (refused != null) {
            return refused;
        }
        String controlId = body == null ? null : body.get("controlId");
        try {
            NextCycleCreated created = controlCycleService.createNextCycle(id, controlId, currentUser);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("id", created.id());
            answer.put("controlId", created.controlId());
            answer.put("sourceControlId", created.sourceControlId());
            answer.put("sharedWithLeftOut", created.sharedWithLeftOut());
            answer.put("url", "/view-control/" + created.id());
            return ResponseEntity.ok(answer);
        } catch (NextCycleRefused e) {
            return answer(e);
        } catch (DataIntegrityViolationException e) {
            // Another request took the same Control ID at the same moment (the unique key of control_id)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                    "Control ID " + (controlId == null ? "" : controlId.trim()) + " already exists. Enter another Control ID.",
                    List.of(), null));
        }
    }

    /** 401 without a user, 404 without the control, 403 for anyone but SoQM Admin Team; null when allowed. */
    private ResponseEntity<?> refusedFor(Long id, User currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<Control> control = controlService.findById(id);
        if (control.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Control not found", List.of(), null));
        }
        if (!AccessPolicy.canCreateNextCycle(controlPermissionService.resolve(control.get(), currentUser))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(NOT_ALLOWED, List.of(), null));
        }
        return null;
    }

    private static ResponseEntity<?> answer(NextCycleRefused refused) {
        return ResponseEntity.status(refused.status()).body(error(refused.getMessage(), refused.problems(),
                refused.existing()));
    }

    private static Map<String, Object> error(String message, List<String> problems, Control existing) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("problems", problems);
        if (existing != null) {
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("id", existing.getId());
            other.put("controlId", existing.getControlId());
            other.put("url", "/view-control/" + existing.getId());
            body.put("existing", other);
        }
        return body;
    }

    /** What the confirmation shows: the new ID, the dates, the people, what is not copied, what stops it. */
    private Map<String, Object> describe(NextCyclePlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceId", plan.source().getId());
        out.put("sourceControlId", plan.source().getControlId());
        out.put("frequency", plan.source().getControlFrequency());
        out.put("proposedControlId", plan.proposedControlId());
        ControlNextCycle.Dates dates = plan.dates();
        out.put("operationDate", date(dates == null ? null : dates.operationDate()));
        out.put("deadline", date(dates == null ? null : dates.deadline()));
        out.put("deadlineHint", ControlScheduleCalculator.DEADLINE.hint());
        out.put("nextOperationDate", date(dates == null ? null : dates.nextOperationDate()));
        out.put("soqmYear", dates == null ? "" : dates.soqmYear());
        List<Map<String, Object>> people = new ArrayList<>();
        for (AccessPolicy.Slot slot : ControlNextCycle.REQUIRED_PEOPLE) {
            people.add(field(slot.getLabel(), stored(plan, slot), true));
        }
        people.add(field(AccessPolicy.Slot.SHARED_WITH.getLabel(), plan.people().sharedWith(), false));
        out.put("people", people);
        out.put("sharedWithLeftOut", plan.people().sharedWithLeftOut());
        out.put("notCopied", ControlNextCycle.NOT_COPIED);
        out.put("problems", plan.problems());
        return out;
    }

    private static String stored(NextCyclePlan plan, AccessPolicy.Slot slot) {
        return switch (slot) {
            case FACILITATOR -> plan.sourceAssignment().getFacilitator();
            case CONTROL_OPERATOR -> plan.sourceAssignment().getControlOperator();
            case PROCESS_OWNER -> plan.sourceAssignment().getProcessOwner();
            case SOQM_LEAD -> plan.sourceAssignment().getSoqmLead();
            case SHARED_WITH -> plan.sourceAssignment().getControlSharedWith();
        };
    }

    /**
     * One field of people: its label, whether the new control needs someone in it, and each person as "Name (e-mail)",
     * or the e-mail when there is no name.
     */
    private Map<String, Object> field(String label, String stored, boolean required) {
        List<String> names = new ArrayList<>();
        for (String mail : EmailList.parse(stored)) {
            names.add(userRepository.findByMail(mail)
                    .map(user -> user.getDisplayName() == null || user.getDisplayName().isBlank()
                            || user.getDisplayName().equalsIgnoreCase(mail)
                            ? mail : user.getDisplayName() + " (" + mail + ")")
                    .orElse(mail));
        }
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("label", label);
        field.put("required", required);
        field.put("people", names);
        return field;
    }

    private static String date(LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }
}
