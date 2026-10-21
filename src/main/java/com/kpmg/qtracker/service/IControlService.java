package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import java.util.List;
import java.util.Optional;

public interface IControlService {
    List<Control> getAllControls();
    List<Control> findVisibleControlsForUser(User user);
    boolean isControlIdUnique(String controlId);
    Control createControl(Control control);
    Control save(Control control);
    Optional<Control> getControlById(Long id);
    Control updateControl(Control control);
    String getControlFrequency(Long controlId);
    ControlResponseDTO convertToResponseDTO(Control control);

    /** The rows of a list: the same as convertToResponseDTO for each, with the people read in one query. */
    List<ControlResponseDTO> convertToResponseDTOs(List<Control> controls);
    Optional<Control> findById(Long id);

}
