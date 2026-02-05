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
        details.setControlId(detailsDTO.getControlId());
        details.setProcessName(detailsDTO.getProcessName());
        details.setHomogeneity(detailsDTO.getHomogeneity());
        details.setReferencesToControl(detailsDTO.getReferencesToControl());
        details.setDepartment(detailsDTO.getDepartment());
        details.setProcessActivities(detailsDTO.getProcessActivities());
        details.setControlOperatorsProgram(detailsDTO.getControlOperatorsProgram());
        details.setOtherRelatedControls(detailsDTO.getOtherRelatedControls());
        details.setItApplications(detailsDTO.getItApplications());
        details.setControlStepsPerformed(detailsDTO.getControlStepsPerformed());
        details.setSoqmHeadComments(detailsDTO.getSoqmHeadComments());
        details.setProcessOwnerComments(detailsDTO.getProcessOwnerComments());
        details.setAttachedFile(detailsDTO.getAttachedFile());

        return controlDetailsRepository.save(details);
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
        dto.setControlOperatorsProgram(details.getControlOperatorsProgram());
        dto.setOtherRelatedControls(details.getOtherRelatedControls());
        dto.setItApplications(details.getItApplications());
        dto.setControlStepsPerformed(details.getControlStepsPerformed());
        dto.setSoqmHeadComments(details.getSoqmHeadComments());
        dto.setProcessOwnerComments(details.getProcessOwnerComments());
        dto.setAttachedFile(details.getAttachedFile());
        return dto;
    }
}