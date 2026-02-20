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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        
        // Обновляем валидацию
        validateUsersHaveRole(assignmentDTO.getControlOperator(), "CONTROL_OPERATOR",
                "User must have CONTROL_OPERATOR role to be assigned as Control Operator");
        validateUsersHaveRole(assignmentDTO.getSoqmLead(), "SOQM_LEAD",
                "User must have SOQM_LEAD role to be assigned as SOQM Lead");
        validateUsersHaveRole(assignmentDTO.getProcessOwner(), "PROCESS_OWNER",
                "User must have PROCESS_OWNER role to be assigned as Process Owner");

        Optional<ControlAssignment> existingAssignment = assignmentRepository.findByControlId(assignmentDTO.getControlId());
        ControlAssignment assignment = existingAssignment.orElse(new ControlAssignment());

        LocalDate operationDate = assignmentDTO.getControlOperationDate();
        if (operationDate == null && existingAssignment.isPresent()) {
            operationDate = existingAssignment.get().getControlOperationDate();
        }

        Optional<Control> controlOpt = controlRepository.findById(assignmentDTO.getControlId());
        String frequencyValue = controlOpt.map(Control::getControlFrequency).orElse(null);

        LocalDate deadline = null;
        LocalDate nextDate = null;
        if (operationDate != null) {
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

    // Методы проверки ролей через поле role в User
    public boolean isUserFacilitator(Long controlId, String userEmail) {
        Optional<User> user = userRepository.findByMail(userEmail);
        return user.isPresent() && "FACILITATOR".equals(user.get().getRole());
    }

    public boolean isUserControlOperator(Long controlId, String userEmail) {
        Optional<User> user = userRepository.findByMail(userEmail);
        return user.isPresent() && "CONTROL_OPERATOR".equals(user.get().getRole());
    }

    public boolean isUserSoqmLead(Long controlId, String userEmail) {
        Optional<User> user = userRepository.findByMail(userEmail);
        return user.isPresent() && "SOQM_LEAD".equals(user.get().getRole());
    }

    public boolean isUserProcessOwner(Long controlId, String userEmail) {
        Optional<User> user = userRepository.findByMail(userEmail);
        return user.isPresent() && "PROCESS_OWNER".equals(user.get().getRole());
    }

    public List<String> getUserRolesForControl(Long controlId, String userEmail) {
        List<String> roles = new ArrayList<>();
        Optional<User> user = userRepository.findByMail(userEmail);

        if (user.isPresent()) {
            String role = user.get().getRole();
            if (role != null) {
                roles.add(role);
            }
        }

        return roles;
    }

    // ★ ДОБАВИТЬ метод для получения пользователей по роли
    public List<User> getUsersByRole(String role) {
        return userRepository.findByRole(role);
    }

    // Обновленная валидация
    private void validateUsersHaveRole(List<String> userEmails, String requiredRole, String errorMessage) {
        if (userEmails == null || userEmails.isEmpty()) {
            return;
        }

        for (String email : userEmails) {
            Optional<User> user = userRepository.findByMail(email);
            boolean hasRole = user.isPresent() && requiredRole.equals(user.get().getRole());

            if (!hasRole) {
                throw new RuntimeException(errorMessage + ": " + email);
            }
        }
    }

    private String convertListToString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(",", list);
    }

    private List<String> convertStringToList(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(str.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
