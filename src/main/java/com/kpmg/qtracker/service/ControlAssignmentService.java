package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.kpmg.qtracker.util.EmailList;

@Service
@RequiredArgsConstructor
@Slf4j
public class ControlAssignmentService {
    private final ControlAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final ControlRepository controlRepository;
    private final ControlScheduleCalculator scheduleCalculator;
    private final com.kpmg.qtracker.repository.DeletedUserRepository deletedUserRepository;

    // ★ ДОБАВИТЬ этот метод - его используют другие сервисы!
    public ControlAssignmentDTO getAssignmentByControlId(Long controlId) {
        Optional<ControlAssignment> found = assignmentRepository.findByControlId(controlId);
        if (found.isPresent()) {
            ControlAssignmentDTO dto = convertToDTO(found.get());
            log.debug("getAssignmentByControlId: controlId={}, facilitator={}, operator={}, owner={}, soqm={}",
                    controlId, dto.getFacilitator(), dto.getControlOperator(), dto.getProcessOwner(), dto.getSoqmLead());
            return dto;
        } else {
            log.debug("getAssignmentByControlId: no assignment found for controlId={}", controlId);
            return new ControlAssignmentDTO();
        }
    }

    @Transactional
    public ControlAssignment saveAssignment(ControlAssignmentDTO assignmentDTO) {
        return saveAssignment(assignmentDTO, false);
    }

    /**
     * @param keepSchedule keep the stored Control Operation Date, deadline and next date as they are, whatever the
     *                     request holds, and leave the control's deadline alone: a completed control changed in place
     *                     (AccessPolicy.COMPLETED_FIXED_FIELDS), whose people change but whose dates do not
     */
    public ControlAssignment saveAssignment(ControlAssignmentDTO assignmentDTO, boolean keepSchedule) {
        log.debug("saveAssignment: controlId={}, facilitators={}, operators={}, owners={}, soqm={}",
                assignmentDTO.getControlId(),
                assignmentDTO.getFacilitator(),
                assignmentDTO.getControlOperator(),
                assignmentDTO.getProcessOwner(),
                assignmentDTO.getSoqmLead());
        
        Optional<Control> controlOpt = controlRepository.findById(assignmentDTO.getControlId());
        boolean kdnControl = controlOpt.map(control -> AccessPolicy.isKdnControl(control.getControlId())).orElse(false);
        Optional<ControlAssignment> existingAssignment = assignmentRepository.findByControlId(assignmentDTO.getControlId());
        ControlAssignment before = existingAssignment.orElse(null);
        validateAssignees(assignmentDTO.getFacilitator(), AccessPolicy.Slot.FACILITATOR, kdnControl,
                before == null ? null : before.getFacilitator());
        validateAssignees(assignmentDTO.getControlOperator(), AccessPolicy.Slot.CONTROL_OPERATOR, kdnControl,
                before == null ? null : before.getControlOperator());
        validateAssignees(assignmentDTO.getSoqmLead(), AccessPolicy.Slot.SOQM_LEAD, kdnControl,
                before == null ? null : before.getSoqmLead());
        validateAssignees(assignmentDTO.getProcessOwner(), AccessPolicy.Slot.PROCESS_OWNER, kdnControl,
                before == null ? null : before.getProcessOwner());
        validateAssignees(assignmentDTO.getControlSharedWith(), AccessPolicy.Slot.SHARED_WITH, kdnControl,
                before == null ? null : before.getControlSharedWith());

        ControlAssignment assignment = existingAssignment.orElse(new ControlAssignment());

        LocalDate operationDate = assignmentDTO.getControlOperationDate();
        if (operationDate == null && existingAssignment.isPresent()) {
            operationDate = existingAssignment.get().getControlOperationDate();
        }

        String frequencyValue = controlOpt.map(Control::getControlFrequency).orElse(null);

        // A save that keeps the operation date keeps the stored schedule: a control set up under an earlier
        // deadline rule keeps its deadline until its date (or its frequency, recalculateSchedule) changes
        LocalDate deadline = null;
        LocalDate nextDate = null;
        ControlAssignment stored = existingAssignment.orElse(null);
        if (keepSchedule && stored != null) {
            operationDate = stored.getControlOperationDate();
            deadline = stored.getControlOperationDeadline();
            nextDate = stored.getNextControlOperationDate();
        } else if (operationDate != null && stored != null
                && operationDate.equals(stored.getControlOperationDate())
                && stored.getControlOperationDeadline() != null) {
            deadline = stored.getControlOperationDeadline();
            nextDate = stored.getNextControlOperationDate();
        } else if (operationDate != null) {
            ControlFrequency frequency = ControlFrequency.fromValue(frequencyValue);
            deadline = scheduleCalculator.calculateDeadline(frequency, operationDate);
            nextDate = scheduleCalculator.calculateNextDate(frequency, operationDate);
        }

        if (assignmentDTO.getControlId() != null) {
            assignment.setControlId(assignmentDTO.getControlId());
        }
        if (assignmentDTO.getFacilitator() != null) {
            assignment.setFacilitator(convertListToString(assignmentDTO.getFacilitator()));
        }
        if (assignmentDTO.getControlOperator() != null) {
            assignment.setControlOperator(convertListToString(assignmentDTO.getControlOperator()));
        }
        if (assignmentDTO.getSoqmLead() != null) {
            assignment.setSoqmLead(convertListToString(assignmentDTO.getSoqmLead()));
        }
        if (assignmentDTO.getProcessOwner() != null) {
            assignment.setProcessOwner(convertListToString(assignmentDTO.getProcessOwner()));
        }
        if (assignmentDTO.getControlSharedWith() != null) {
            assignment.setControlSharedWith(convertListToString(assignmentDTO.getControlSharedWith()));
        }
        assignment.setControlOperationDate(operationDate);
        assignment.setControlOperationDeadline(deadline);
        assignment.setNextControlOperationDate(nextDate);

        ControlAssignment saved = assignmentRepository.save(assignment);
        
        log.debug("saveAssignment: saved assignment id={}, facilitators={}, processOwner={}",
                saved.getControlId(), saved.getFacilitator(), saved.getProcessOwner());

        // ★ Обновляем deadline в таблице control_controls
        if (controlOpt.isPresent() && deadline != null && !keepSchedule) {
            Control control = controlOpt.get();
            control.setDeadline(deadline);
            controlRepository.save(control);
        }

        return saved;
    }

    @Transactional
    public void recalculateSchedule(Long controlId) {
        Optional<ControlAssignment> assignmentOpt = assignmentRepository.findByControlId(controlId);
        if (assignmentOpt.isEmpty()) {
            return;
        }

        ControlAssignment assignment = assignmentOpt.get();
        LocalDate operationDate = assignment.getControlOperationDate();
        if (operationDate == null) {
            return;
        }

        String frequencyValue = controlRepository.findById(controlId)
                .map(Control::getControlFrequency)
                .orElse(null);
        ControlFrequency frequency = ControlFrequency.fromValue(frequencyValue);

        LocalDate deadline = scheduleCalculator.calculateDeadline(frequency, operationDate);
        LocalDate nextDate = scheduleCalculator.calculateNextDate(frequency, operationDate);

        assignment.setControlOperationDeadline(deadline);
        assignment.setNextControlOperationDate(nextDate);
        assignmentRepository.save(assignment);

        controlRepository.findById(controlId).ifPresent(control -> {
            control.setDeadline(deadline);
            controlRepository.save(control);
        });
    }

    /**
     * Everyone put in an assignment field must be allowed there ({@link AccessPolicy#assignmentRefusal}):
     * an existing user, a participant in Facilitator / Control Operator / Process Owner, a SoQM user in
     * SoQM Team / Delegate, nobody read-only except in Shared With, a KDN-scope user only on a KDN control.
     */
    /**
     * @param stored the field as stored before this save: a deleted person already there stays
     *               (AccessPolicy.assignmentRefusal with deletedAndAlreadyThere)
     */
    private void validateAssignees(List<String> userEmails, AccessPolicy.Slot slot, boolean kdnControl, String stored) {
        if (userEmails == null) {
            return;
        }
        java.util.Set<String> before = new java.util.HashSet<>();
        for (String mail : EmailList.parse(stored)) {
            before.add(mail.trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (String email : userEmails) {
            if (email == null || email.isBlank()) {
                continue;
            }
            AccessPolicy.Subject candidate = userRepository.findByMail(email.trim())
                    .map(AccessPolicy.Subject::of)
                    .orElse(null);
            String lower = email.trim().toLowerCase(java.util.Locale.ROOT);
            boolean deletedAndThere = candidate == null && before.contains(lower) && deletedUserRepository != null
                    && !deletedUserRepository.findByMailLowerIn(List.of(lower)).isEmpty();
            AccessPolicy.assignmentRefusal(candidate, slot, kdnControl, deletedAndThere).ifPresent(reason -> {
                throw new IllegalArgumentException(slot.getLabel() + ": " + email.trim() + " " + reason);
            });
        }
    }

    private String convertListToString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(",", list);
    }

    private List<String> convertStringToList(String str) {
        return new ArrayList<>(EmailList.parse(str));
    }

    /** The assignments of these controls in one query, by control id; a control without one is left out. */
    public Map<Long, ControlAssignmentDTO> getAssignmentsByControlIds(Collection<Long> controlIds) {
        Map<Long, ControlAssignmentDTO> assignments = new HashMap<>();
        if (controlIds == null || controlIds.isEmpty()) {
            return assignments;
        }
        for (ControlAssignment assignment : assignmentRepository.findAllById(controlIds)) {
            assignments.put(assignment.getControlId(), convertToDTO(assignment));
        }
        return assignments;
    }

    private ControlAssignmentDTO convertToDTO(ControlAssignment assignment) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlId(assignment.getControlId());
        
        log.debug("convertToDTO: controlId={}, facilitatorRaw='{}', operatorRaw='{}', processOwnerRaw='{}', soqmRaw='{}'",
                assignment.getControlId(),
                assignment.getFacilitator(),
                assignment.getControlOperator(),
                assignment.getProcessOwner(),
                assignment.getSoqmLead());
        
        dto.setFacilitator(convertStringToList(assignment.getFacilitator()));
        dto.setControlOperator(convertStringToList(assignment.getControlOperator()));
        dto.setSoqmLead(convertStringToList(assignment.getSoqmLead()));
        dto.setProcessOwner(convertStringToList(assignment.getProcessOwner()));
        dto.setControlSharedWith(convertStringToList(assignment.getControlSharedWith()));
        
        log.debug("convertToDTO: facilitatorList={}, operatorList={}", dto.getFacilitator(), dto.getControlOperator());
        
        dto.setControlOperationDate(assignment.getControlOperationDate());
        dto.setControlOperationDeadline(assignment.getControlOperationDeadline());
        dto.setNextControlOperationDate(assignment.getNextControlOperationDate());
        return dto;
    }
}
