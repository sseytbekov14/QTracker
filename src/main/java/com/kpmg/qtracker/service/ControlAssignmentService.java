package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ControlAssignmentService {
    private final ControlAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final ControlRepository controlRepository;

    // ★ ДОБАВИТЬ этот метод - его используют другие сервисы!
    public ControlAssignmentDTO getAssignmentByControlId(Long controlId) {
        Optional<ControlAssignment> found = assignmentRepository.findByControlId(controlId);
        if (found.isPresent()) {
            ControlAssignmentDTO dto = convertToDTO(found.get());
            System.out.println("📋 getAssignmentByControlId: controlId=" + controlId 
                    + ", facilitator=" + dto.getFacilitator() 
                    + ", operator=" + dto.getControlOperator()
                    + ", owner=" + dto.getProcessOwner()
                    + ", soqm=" + dto.getSoqmLead());
            return dto;
        } else {
            System.out.println("⚠️ getAssignmentByControlId: NO assignment found for controlId=" + controlId);
            return new ControlAssignmentDTO();
        }
    }

    @Transactional
    public ControlAssignment saveAssignment(ControlAssignmentDTO assignmentDTO) {
        System.out.println("=== SAVE ASSIGNMENT ===");
        System.out.println("Control ID: " + assignmentDTO.getControlId());
        System.out.println("Facilitators: " + assignmentDTO.getFacilitator());
        System.out.println("Control Operators: " + assignmentDTO.getControlOperator());
        System.out.println("Process Owners: " + assignmentDTO.getProcessOwner());
        System.out.println("SoQM Leads: " + assignmentDTO.getSoqmLead());
        System.out.println("==================");
        
        // Обновляем валидацию
        validateUsersHaveRole(assignmentDTO.getControlOperator(), "CONTROL_OPERATOR",
                "User must have CONTROL_OPERATOR role to be assigned as Control Operator");
        validateUsersHaveRole(assignmentDTO.getSoqmLead(), "SOQM_LEAD",
                "User must have SOQM_LEAD role to be assigned as SOQM Lead");
        validateUsersHaveRole(assignmentDTO.getProcessOwner(), "PROCESS_OWNER",
                "User must have PROCESS_OWNER role to be assigned as Process Owner");

        Optional<ControlAssignment> existingAssignment = assignmentRepository.findByControlId(assignmentDTO.getControlId());
        ControlAssignment assignment = existingAssignment.orElse(new ControlAssignment());

        assignment.setControlId(assignmentDTO.getControlId());
        assignment.setFacilitator(convertListToString(assignmentDTO.getFacilitator()));
        assignment.setControlOperator(convertListToString(assignmentDTO.getControlOperator()));
        assignment.setSoqmLead(convertListToString(assignmentDTO.getSoqmLead()));
        assignment.setProcessOwner(convertListToString(assignmentDTO.getProcessOwner()));
        assignment.setControlSharedWith(convertListToString(assignmentDTO.getControlSharedWith()));
        assignment.setControlOperationDate(assignmentDTO.getControlOperationDate());
        assignment.setControlOperationDeadline(assignmentDTO.getControlOperationDeadline());
        assignment.setNextControlOperationDate(normalizeNextControlOperationDate(
                assignmentDTO.getControlId(),
                assignmentDTO.getControlOperationDate(),
                assignmentDTO.getNextControlOperationDate()
        ));

        ControlAssignment saved = assignmentRepository.save(assignment);
        
        System.out.println("✅ Saved assignment ID: " + saved.getId());
        System.out.println("✅ Saved facilitators: " + saved.getFacilitator());
        System.out.println("✅ Saved processOwner: " + saved.getProcessOwner());

        // ★ Обновляем deadline в таблице control_controls
        if (assignmentDTO.getControlOperationDeadline() != null) {
            Optional<Control> controlOpt = controlRepository.findById(assignmentDTO.getControlId());
            if (controlOpt.isPresent()) {
                Control control = controlOpt.get();
                control.setDeadline(assignmentDTO.getControlOperationDeadline());
                controlRepository.save(control);
            }
        }

        return saved;
    }

    private LocalDate normalizeNextControlOperationDate(Long controlId,
                                                        LocalDate operationDate,
                                                        LocalDate providedNextDate) {
        if (operationDate == null) {
            return null;
        }
        if (providedNextDate != null && providedNextDate.isAfter(operationDate)) {
            return providedNextDate;
        }

        String frequency = controlRepository.findById(controlId)
                .map(Control::getControlFrequency)
                .orElse("");
        String normalized = frequency == null ? "" : frequency.trim().toLowerCase(Locale.ROOT);

        if (normalized.contains("semi")) {
            return operationDate.plusMonths(6);
        }
        if (normalized.contains("annual")) {
            return operationDate.plusMonths(12);
        }
        if (normalized.contains("quarter")) {
            return operationDate.plusMonths(3);
        }
        if (normalized.contains("monthly") || normalized.contains("recurr")
                || (normalized.contains("ad") && normalized.contains("hoc"))) {
            return operationDate.plusMonths(1);
        }
        return operationDate.plusMonths(1);
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
        return java.util.Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private ControlAssignmentDTO convertToDTO(ControlAssignment assignment) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlId(assignment.getControlId());
        
        // DEBUG: Log raw values from database
        System.out.println("🔍 Converting assignment for control " + assignment.getControlId());
        System.out.println("   Raw facilitator from DB: '" + assignment.getFacilitator() + "'");
        System.out.println("   Raw controlOperator from DB: '" + assignment.getControlOperator() + "'");
        System.out.println("   Raw processOwner from DB: '" + assignment.getProcessOwner() + "'");
        System.out.println("   Raw soqmLead from DB: '" + assignment.getSoqmLead() + "'");
        
        dto.setFacilitator(convertStringToList(assignment.getFacilitator()));
        dto.setControlOperator(convertStringToList(assignment.getControlOperator()));
        dto.setSoqmLead(convertStringToList(assignment.getSoqmLead()));
        dto.setProcessOwner(convertStringToList(assignment.getProcessOwner()));
        dto.setControlSharedWith(convertStringToList(assignment.getControlSharedWith()));
        
        System.out.println("   Converted facilitator list: " + dto.getFacilitator());
        System.out.println("   Converted operator list: " + dto.getControlOperator());
        
        dto.setControlOperationDate(assignment.getControlOperationDate());
        dto.setControlOperationDeadline(assignment.getControlOperationDeadline());
        dto.setNextControlOperationDate(assignment.getNextControlOperationDate());
        return dto;
    }
}
