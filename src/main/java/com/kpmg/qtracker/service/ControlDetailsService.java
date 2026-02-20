package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ControlDetailsService {
    private final ControlDetailsRepository controlDetailsRepository;

    public ControlDetails saveDetails(ControlDetailsDTO detailsDTO) {
        ControlDetails details = controlDetailsRepository.findByControlId(detailsDTO.getControlId())
                .orElse(new ControlDetails());

        // Map DTO to Entity
        if (detailsDTO.getControlId() != null) {
            details.setControlId(detailsDTO.getControlId());
        }
        updateIfPresent(detailsDTO.getProcessName(), details::setProcessName);
        updateIfPresent(detailsDTO.getHomogeneity(), details::setHomogeneity);
        updateIfPresent(detailsDTO.getReferencesToControl(), details::setReferencesToControl);
        updateIfPresent(detailsDTO.getDepartment(), details::setDepartment);
        updateIfPresent(detailsDTO.getProcessActivities(), details::setProcessActivities);
        updateIfPresent(detailsDTO.getOtherRelatedControls(), details::setOtherRelatedControls);
        updateIfPresent(detailsDTO.getItApplications(), details::setItApplications);
        updateIfPresent(detailsDTO.getControlStepsPerformed(), details::setControlStepsPerformed);
        updateIfPresent(detailsDTO.getSoqmHeadComments(), details::setSoqmHeadComments);
        updateIfPresent(detailsDTO.getProcessOwnerComments(), details::setProcessOwnerComments);

        return controlDetailsRepository.save(details);
    }

    private void updateIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    public ControlDetailsDTO getDetailsByControlId(Long controlId) {
        return controlDetailsRepository.findByControlId(controlId)
                .map(this::convertToDTO)
                .orElse(new ControlDetailsDTO());
    }

    private ControlDetailsDTO convertToDTO(ControlDetails details) {
        ControlDetailsDTO dto = new ControlDetailsDTO();
        dto.setControlId(details.getControlId());
        dto.setProcessName(details.getProcessName());
        dto.setHomogeneity(details.getHomogeneity());
        dto.setReferencesToControl(details.getReferencesToControl());
        dto.setDepartment(details.getDepartment());
        dto.setProcessActivities(details.getProcessActivities());
        dto.setOtherRelatedControls(details.getOtherRelatedControls());
        dto.setItApplications(details.getItApplications());
        dto.setControlStepsPerformed(details.getControlStepsPerformed());
        dto.setSoqmHeadComments(details.getSoqmHeadComments());
        dto.setProcessOwnerComments(details.getProcessOwnerComments());
        return dto;
    }
}
