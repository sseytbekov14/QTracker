package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps the attachment lists on the control and their upload records (author, stage) in step,
 * and decides who may delete a file.
 */
@Service
@RequiredArgsConstructor
public class ControlAttachmentService {

    private final ControlAttachmentRepository attachmentRepository;
    private final IControlService controlService;

    /** Appends uploaded file names to the control and records who uploaded them in which stage. */
    @Transactional
    public Control recordUpload(Control control, List<String> detailsFiles, List<String> documentsFiles, User uploader) {
        if (!detailsFiles.isEmpty()) {
            control.setAttachmentDetailsPath(appendToList(control.getAttachmentDetailsPath(), detailsFiles));
        }
        if (!documentsFiles.isEmpty()) {
            control.setAttachmentDocumentsPath(appendToList(control.getAttachmentDocumentsPath(), documentsFiles));
        }
        Control saved = controlService.updateControl(control);

        String stage = normalizeStatus(control.getPerformanceStatus());
        LocalDateTime now = LocalDateTime.now(Notification.ZONE);
        saveRecords(control.getId(), ControlAttachment.TAB_DETAILS, detailsFiles, uploader, stage, now);
        saveRecords(control.getId(), ControlAttachment.TAB_DOCUMENTS, documentsFiles, uploader, stage, now);
        return saved;
    }

    /**
     * SoQM Team and admins may delete any file. Others only a file they uploaded themselves, while the
     * control is still in the stage it was uploaded in and they can edit it; files without an upload
     * record, author or stage (attached before V5) are left to SoQM Team.
     */
    public boolean canDelete(Control control, String tab, String fileName, User user, ControlPermission permission) {
        if (permission == null || !permission.canView()) {
            return false;
        }
        if (permission.canEditAll()) {
            return true;
        }
        if (!permission.canEdit() || user == null || user.getMail() == null) {
            return false;
        }
        Optional<ControlAttachment> record = attachmentRepository.findByControlIdAndTabAndFileName(control.getId(), tab, fileName);
        return record
                .filter(r -> r.getUploadedByEmail() != null
                        && r.getUploadedByEmail().trim().equalsIgnoreCase(user.getMail().trim()))
                .filter(r -> r.getUploadedStage() != null
                        && r.getUploadedStage().equals(normalizeStatus(control.getPerformanceStatus())))
                .isPresent();
    }

    /** Whether the file belongs to the control: on one of its two attachment lists or in its upload records. */
    public boolean isAttached(Control control, String fileName) {
        if (control == null || fileName == null || fileName.isBlank()) {
            return false;
        }
        String name = fileName.trim();
        return isListed(control.getAttachmentDetailsPath(), name)
                || isListed(control.getAttachmentDocumentsPath(), name)
                || (control.getId() != null && attachmentRepository.existsByControlIdAndFileName(control.getId(), name));
    }

    /** Whether a ';'-separated attachment list holds exactly this file name. */
    public static boolean isListed(String storedList, String fileName) {
        if (storedList == null || storedList.isBlank() || fileName == null) {
            return false;
        }
        for (String part : storedList.split(";")) {
            if (part.trim().equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    /** Removes the file name from the control's list and its upload record; returns whether it was listed. */
    @Transactional
    public boolean removeFromControl(Control control, String tab, String fileName) {
        boolean details = ControlAttachment.TAB_DETAILS.equals(tab);
        String currentPath = details ? control.getAttachmentDetailsPath() : control.getAttachmentDocumentsPath();
        boolean removed = false;
        StringBuilder updated = new StringBuilder();
        for (String f : currentPath == null ? new String[0] : currentPath.split(";")) {
            if (f.trim().isEmpty()) continue;
            if (f.trim().equals(fileName.trim())) {
                removed = true;
                continue;
            }
            if (updated.length() > 0) updated.append(";");
            updated.append(f.trim());
        }
        String newPath = updated.length() > 0 ? updated.toString() : null;
        if (details) {
            control.setAttachmentDetailsPath(newPath);
        } else {
            control.setAttachmentDocumentsPath(newPath);
        }
        controlService.updateControl(control);
        attachmentRepository.deleteByControlIdAndTabAndFileName(control.getId(), tab, fileName.trim());
        return removed;
    }

    private void saveRecords(Long controlId, String tab, List<String> fileNames, User uploader,
                             String stage, LocalDateTime uploadedAt) {
        for (String fileName : fileNames) {
            ControlAttachment record = attachmentRepository.findByControlIdAndTabAndFileName(controlId, tab, fileName)
                    .orElseGet(ControlAttachment::new);
            record.setControlId(controlId);
            record.setTab(tab);
            record.setFileName(fileName);
            record.setUploadedByEmail(uploader != null ? uploader.getMail() : null);
            record.setUploadedStage(stage);
            record.setUploadedAt(uploadedAt);
            attachmentRepository.save(record);
        }
    }

    private String appendToList(String existing, List<String> added) {
        String addedList = String.join(";", added);
        if (existing == null || existing.isBlank()) {
            return addedList;
        }
        return addedList.isEmpty() ? existing : existing + ";" + addedList;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
