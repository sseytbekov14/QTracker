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
        log.debug("saveAssignment: controlId={}, facilitators={}, operators={}, owners={}, soqm={}",
                assignmentDTO.getControlId(),
                assignmentDTO.getFacilitator(),
                assignmentDTO.getControlOperator(),
                assignmentDTO.getProcessOwner(),
                assignmentDTO.getSoqmLead());
        
        Optional<Control> controlOpt = controlRepository.findById(assignmentDTO.getControlId());
        boolean kdnControl = controlOpt.map(control -> AccessPolicy.isKdnControl(control.getControlId())).orElse(false);
        validateAssignees(assignmentDTO.getFacilitator(), AccessPolicy.Slot.FACILITATOR, kdnControl);
        validateAssignees(assignmentDTO.getControlOperator(), AccessPolicy.Slot.CONTROL_OPERATOR, kdnControl);
        validateAssignees(assignmentDTO.getSoqmLead(), AccessPolicy.Slot.SOQM_LEAD, kdnControl);
        validateAssignees(assignmentDTO.getProcessOwner(), AccessPolicy.Slot.PROCESS_OWNER, kdnControl);
        validateAssignees(assignmentDTO.getControlSharedWith(), AccessPolicy.Slot.SHARED_WITH, kdnControl);

        Optional<ControlAssignment> existingAssignment = assignmentRepository.findByControlId(assignmentDTO.getControlId());
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
        if (operationDate != null && stored != null
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
        if (controlOpt.isPresent() && deadline != null) {
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
    private void validateAssignees(List<String> userEmails, AccessPolicy.Slot slot, boolean kdnControl) {
        if (userEmails == null) {
            return;
        }
        for (String email : userEmails) {
            if (email == null || email.isBlank()) {
                continue;
            }
            AccessPolicy.Subject candidate = userRepository.findByMail(email.trim())
                    .map(AccessPolicy.Subject::of)
                    .orElse(null);
            AccessPolicy.assignmentRefusal(candidate, slot, kdnControl).ifPresent(reason -> {
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
