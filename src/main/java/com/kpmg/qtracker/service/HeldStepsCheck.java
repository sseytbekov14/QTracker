package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.EmailList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Before the Admin Panel saves a change of a person's role, access or status (business decision 2026-11-26): the
 * controls not completed where the person stands as Facilitator, Control Operator or Process Owner and could act
 * before the change but not after it (Read Only, KDN on a control that is not KDN, deactivated). The page warns
 * with them; nothing is refused. The SoQM Admin Team field is not counted: any SoQM Admin Team member performs that
 * step, and the last active one cannot lose the role.
 */
@Service
@RequiredArgsConstructor
public class HeldStepsCheck {

    /** How many Control IDs of each list the answer names; the totals count all. */
    public static final int LISTED = 10;

    private final UserRepository userRepository;
    private final ControlRepository controlRepository;
    private final ControlAssignmentRepository assignmentRepository;

    /** One control: its ID, status and the person's field(s) there. */
    public record Held(Long id, String controlId, String status, List<String> places) {
    }

    /**
     * onStep: the controls waiting on the person now (their field holds the current step); later: the others not
     * completed (drafts included) where their step is still to come. Each list sorted by Control ID, at most
     * {@link #LISTED}; the totals count all.
     */
    public record Result(List<Held> onStep, int onStepTotal, List<Held> later, int laterTotal) {
        public static final Result NONE = new Result(List.of(), 0, List.of(), 0);

        public boolean isEmpty() {
            return onStepTotal == 0 && laterTotal == 0;
        }
    }

    private record Field(AccessPolicy.Slot slot, String status, java.util.function.Function<ControlAssignment, String> people) {
    }

    private static final List<Field> FIELDS = List.of(
            new Field(AccessPolicy.Slot.FACILITATOR, "IN_PROGRESS", ControlAssignment::getFacilitator),
            new Field(AccessPolicy.Slot.CONTROL_OPERATOR, "REVIEW", ControlAssignment::getControlOperator),
            new Field(AccessPolicy.Slot.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", ControlAssignment::getProcessOwner));

    /**
     * The values as the Admin Panel sends them to {@code /api/users/{id}/access}; a value not sent stays. A value
     * the save would refuse gives no warning here (the save says why).
     */
    @Transactional(readOnly = true)
    public Result check(Long userId, String role, String visibility, String access, Boolean enabled) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getMail() == null) {
            return Result.NONE;
        }
        AccessPolicy.Subject now = AccessPolicy.Subject.of(user);
        AccessPolicy.Subject next;
        try {
            AccessPolicy.Profile profile = UserService.profile(role, visibility, access, AccessPolicy.Profile.of(user));
            next = new AccessPolicy.Subject(profile.level(), profile.scope(),
                    enabled != null ? enabled : Boolean.TRUE.equals(user.getEnabled()), now.systemAdmin());
        } catch (IllegalArgumentException refused) {
            return Result.NONE;
        }
        if (!now.enabled()) {
            return Result.NONE;
        }
        String mail = user.getMail().trim().toLowerCase(Locale.ROOT);
        Map<Long, ControlAssignment> assignments = new HashMap<>();
        assignmentRepository.findAll().forEach(assignment -> assignments.put(assignment.getControlId(), assignment));
        List<Held> onStep = new ArrayList<>();
        List<Held> later = new ArrayList<>();
        for (Control control : controlRepository.findAll()) {
            ControlAssignment assignment = assignments.get(control.getId());
            String status = control.getPerformanceStatus() == null ? "" : control.getPerformanceStatus().trim().toUpperCase(Locale.ROOT);
            if (assignment == null || DeadlineOverdue.isCompleted(status)) {
                continue;
            }
            boolean kdnControl = AccessPolicy.isKdnControl(control.getControlId());
            List<String> places = new ArrayList<>();
            boolean holdsTheStep = false;
            for (Field field : FIELDS) {
                boolean there = EmailList.parse(field.people().apply(assignment)).stream()
                        .anyMatch(person -> person.trim().equalsIgnoreCase(mail));
                if (there && canAct(now, field.slot(), kdnControl) && !canAct(next, field.slot(), kdnControl)) {
                    places.add(field.slot().getLabel());
                    holdsTheStep |= field.status().equals(status);
                }
            }
            if (!places.isEmpty()) {
                (holdsTheStep ? onStep : later).add(new Held(control.getId(), control.getControlId(), status,
                        List.copyOf(places)));
            }
        }
        Comparator<Held> byId = Comparator.comparing(held -> String.valueOf(held.controlId()).toLowerCase(Locale.ROOT));
        onStep.sort(byId);
        later.sort(byId);
        return new Result(first(onStep), onStep.size(), first(later), later.size());
    }

    /** Whether the person performs the steps of this field: active, may stand there, and not read-only. */
    static boolean canAct(AccessPolicy.Subject subject, AccessPolicy.Slot slot, boolean kdnControl) {
        return subject.enabled()
                && AccessPolicy.assignmentRefusal(subject, slot, kdnControl).isEmpty()
                && (subject.level() == com.kpmg.qtracker.enums.AccessLevel.PARTICIPANT
                        || subject.level() == com.kpmg.qtracker.enums.AccessLevel.SOQM);
    }

    private static List<Held> first(List<Held> all) {
        return List.copyOf(all.subList(0, Math.min(LISTED, all.size())));
    }
}
