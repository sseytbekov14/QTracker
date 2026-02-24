package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PerformanceService implements IPerformanceService {

    @Lazy
    private final IControlService controlService;
    private final UserService userService;
    private final ControlAssignmentService controlAssignmentService;

    /**
     * Build PerformanceDTO from Control + ControlAssignment (no separate performance table).
     */
    @Override
    public PerformanceDTO buildPerformanceDTO(Control control) {
        PerformanceDTO dto = new PerformanceDTO();
        if (control == null) {
            dto.setAssignedTo("Not assigned");
            return dto;
        }

        dto.setControlId(control.getId());
        dto.setSoqmYear(control.getSoqmYear());
        dto.setControlFrequency(control.getControlFrequency());
        dto.setPerformanceStatus(control.getPerformanceStatus() != null ? control.getPerformanceStatus() : "DRAFT");

        // Fill from assignment
        try {
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            if (assignment != null) {
                if (assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()) {
                    dto.setControlOperator(String.join(", ", assignment.getControlOperator()));
                }
                if (assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                    dto.setFacilitator(String.join(", ", assignment.getFacilitator()));
                }
                if (assignment.getControlOperationDate() != null) {
                    dto.setControlOperationDate(assignment.getControlOperationDate());
                }

                // Assigned To = first facilitator display name
                if (assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                    String email = assignment.getFacilitator().get(0);
                    Optional<User> user = userService.getUserByEmail(email);
                    dto.setAssignedTo(user.map(User::getDisplayName).orElse(email));
                } else if (assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()) {
                    String email = assignment.getControlOperator().get(0);
                    Optional<User> user = userService.getUserByEmail(email);
                    dto.setAssignedTo(user.map(User::getDisplayName).orElse(email));
                } else {
                    dto.setAssignedTo("Not assigned");
                }
            } else {
                dto.setAssignedTo("Not assigned");
            }
        } catch (Exception e) {
            dto.setAssignedTo("Not assigned");
        }

        // Actual operation date from control creation
        if (control.getCreatedAt() != null) {
            dto.setActualOperationDate(control.getCreatedAt().toLocalDate());
        }

        return dto;
    }

    /**
     * Save soqmYear directly into controls table.
     */
    @Override
    public void saveSoqmYear(Long controlId, String soqmYear) {
        Control control = controlService.getControlById(controlId)
                .orElseThrow(() -> new RuntimeException("Control not found: " + controlId));
        control.setSoqmYear(soqmYear);
        controlService.save(control);
    }

    @Override
    public String getPerformanceStatusByControlId(Long controlId) {
        if (controlId == null) {
            return "DRAFT";
        }
        return controlService.getControlById(controlId)
                .map(Control::getPerformanceStatus)
                .filter(status -> status != null && !status.isBlank())
                .orElse("DRAFT");
    }
}
