package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface IControlService {
    List<Control> getAllControls();
    List<Control> findVisibleControlsForUser(String userEmail, String userRole);
    List<Control> getUserControls(String userEmail);
    List<Control> getAllUserControls(String userEmail);
    List<Control> getControlsByComponent(String component);
    List<Control> getFacilitatorControls(String userEmail);
    List<Control> getControlOperatorControls(String userEmail);
    List<Control> getProcessOwnerControls(String userEmail);
    List<Control> getSoqmLeadControls(String userEmail);
    boolean isControlIdUnique(String controlId);
    Control renameControlId(Long controlId, String newControlId);
    List<ControlResponseDTO> getUserControlsDTO(String userEmail);
    List<ControlResponseDTO> getFacilitatorControlsDTO(String userEmail);
    Control createControl(Control control);
    Control save(Control control);
    boolean isControlComplete(Control control);
    Optional<Control> getControlById(Long id);
    void deleteControl(Long id);
    Map<String, Long> getComponentStatistics();
    Control updateControl(Control control);
    String getControlFrequency(Long controlId);
    ControlResponseDTO convertToResponseDTO(Control control);
    Optional<Control> findById(Long id);
    List<String> getFacilitatorsForControl(Long controlId);
    boolean hasReachedUserStage(Long controlId, String userRole);

}
