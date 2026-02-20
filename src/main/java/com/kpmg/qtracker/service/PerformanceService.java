package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlPerformance;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.PerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PerformanceService implements IPerformanceService {
    private final PerformanceRepository performanceRepository;

    @Lazy // ✅ Ленивая загрузка
    private final IControlService controlService;

    private final UserService userService;

    @Override
    public Optional<ControlPerformance> findByControlId(Long controlId) {
        return performanceRepository.findByControlId(controlId);
    }

    @Override
    public PerformanceDTO convertToDTO(ControlPerformance performance, Control control) {
        PerformanceDTO dto = new PerformanceDTO();

        if (performance != null) {
            dto.setControlOperator(performance.getControlOperator());
            dto.setFacilitator(performance.getFacilitator());
            dto.setControlFrequency(performance.getControlFrequency());
            dto.setControlOperationDate(performance.getControlOperationDate());
            dto.setSoqmYear(performance.getSoqmYear());
            dto.setActualOperationDate(performance.getActualOperationDate());
            dto.setAssignedTo(performance.getAssignedTo());
        } else {
            dto.setAssignedTo("Not assigned");
        }

        // ✅ Логика для Assigned To
        if (dto.getAssignedTo() == null || dto.getAssignedTo().isEmpty() ||
                dto.getAssignedTo().equals("0") || dto.getAssignedTo().equals("Not assigned")) {

            if (dto.getFacilitator() != null && !dto.getFacilitator().isEmpty()) {
                String email = extractEmailFromFacilitator(dto.getFacilitator());
                if (email != null) {
                    Optional<User> user = userService.getUserByEmail(email);
                    if (user.isPresent()) {
                        dto.setAssignedTo(user.get().getDisplayName());
                    } else {
                        dto.setAssignedTo(email);
                    }
                }
            }
        }

        if (dto.getActualOperationDate() == null && control != null && control.getCreatedAt() != null) {
            dto.setActualOperationDate(control.getCreatedAt().toLocalDate());
        }

        if (dto.getControlFrequency() == null && control != null) {
            dto.setControlFrequency(control.getControlFrequency());
        }

        return dto;
    }

    private String extractEmailFromFacilitator(String facilitatorString) {
        if (facilitatorString == null || facilitatorString.isEmpty()) {
            return null;
        }

        if (facilitatorString.contains("@")) {
            String[] parts = facilitatorString.split(",");
            if (parts.length > 0) {
                return parts[0].trim();
            }
        }

        return null;
    }

    @Override
    public ControlPerformance savePerformance(PerformanceDTO performanceDTO, Control control) {
        System.out.println("=== SAVE PERFORMANCE SERVICE ===");
        System.out.println("Control ID: " + performanceDTO.getControlId());
        System.out.println("Actual Date in DTO: " + performanceDTO.getActualOperationDate());

        ControlPerformance performance = performanceRepository.findByControlId(performanceDTO.getControlId())
                .orElse(new ControlPerformance());

        System.out.println("Existing performance found: " + (performance.getId() != null));

        performance.setControlId(performanceDTO.getControlId());
        performance.setControlOperator(performanceDTO.getControlOperator());
        performance.setFacilitator(performanceDTO.getFacilitator());
        performance.setControlFrequency(performanceDTO.getControlFrequency());
        performance.setControlOperationDate(performanceDTO.getControlOperationDate());
        performance.setSoqmYear(performanceDTO.getSoqmYear());

        if (performanceDTO.getActualOperationDate() != null) {
            performance.setActualOperationDate(performanceDTO.getActualOperationDate());
            System.out.println("✅ Set actual date from DTO: " + performanceDTO.getActualOperationDate());
        }
        else if (control != null && control.getCreatedAt() != null) {
            performance.setActualOperationDate(control.getCreatedAt().toLocalDate());
            System.out.println("ℹ️ Set actual date from control creation: " + control.getCreatedAt().toLocalDate());
        }
        else {
            performance.setActualOperationDate(LocalDate.now());
            System.out.println("⚠️ Set actual date to current date: " + LocalDate.now());
        }

        performance.setAssignedTo(performanceDTO.getAssignedTo());
        performance.setUpdatedAt(LocalDate.now());

        if (performance.getId() == null) {
            performance.setCreatedAt(LocalDate.now());
        }

        ControlPerformance saved = performanceRepository.save(performance);
        System.out.println("✅ Saved performance ID: " + saved.getId());
        System.out.println("✅ Actual date in saved entity: " + saved.getActualOperationDate());
        System.out.println("=== END SAVE ===");

        return saved;
    }

    @Override
    public String getPerformanceStatusByControlId(Long controlId) {
        // Read workflow status from controls.performance_status
        if (controlId == null) {
            return "DRAFT";
        }
        return controlService.getControlById(controlId)
                .map(Control::getPerformanceStatus)
                .filter(status -> status != null && !status.isBlank())
                .orElse("DRAFT");
    }
}
