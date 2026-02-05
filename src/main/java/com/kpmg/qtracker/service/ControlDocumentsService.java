package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlDocumentsDTO;
import com.kpmg.qtracker.entity.ControlDocuments;
import com.kpmg.qtracker.repository.ControlDocumentsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ControlDocumentsService {
    private final ControlDocumentsRepository documentsRepository;

    public ControlDocuments saveDocuments(ControlDocumentsDTO documentsDTO) {
        try {
            System.out.println("🔄 ControlDocumentsService.saveDocuments() called");
            System.out.println("   Control ID: " + documentsDTO.getControlId());
            System.out.println("   Link: " + documentsDTO.getLink());
            System.out.println("   Attachment: " + documentsDTO.getAttachment());
            System.out.println("   SOQM Materials: " + documentsDTO.getSoqmDevelopmentMaterials());

            ControlDocuments documents = documentsRepository.findByControlId(documentsDTO.getControlId())
                    .orElse(new ControlDocuments());

            documents.setControlId(documentsDTO.getControlId());
            documents.setLink(documentsDTO.getLink());
            documents.setAttachment(documentsDTO.getAttachment());
            documents.setSoqmDevelopmentMaterials(documentsDTO.getSoqmDevelopmentMaterials());

            ControlDocuments saved = documentsRepository.save(documents);
            System.out.println("✅ Document saved with ID: " + saved.getId());

            return saved;
        } catch (Exception e) {
            System.out.println("❌ ERROR in ControlDocumentsService.saveDocuments(): " + e.getMessage());
            e.printStackTrace();
            throw e; // Пробрасываем дальше
        }
    }

    public ControlDocumentsDTO getDocumentsByControlId(Long controlId) {
        return documentsRepository.findByControlId(controlId)
                .map(this::convertToDTO)
                .orElse(new ControlDocumentsDTO());
    }

    private ControlDocumentsDTO convertToDTO(ControlDocuments documents) {
        ControlDocumentsDTO dto = new ControlDocumentsDTO();
        dto.setControlId(documents.getControlId());
        dto.setLink(documents.getLink());
        dto.setAttachment(documents.getAttachment());
        dto.setSoqmDevelopmentMaterials(documents.getSoqmDevelopmentMaterials());
        return dto;
    }
}