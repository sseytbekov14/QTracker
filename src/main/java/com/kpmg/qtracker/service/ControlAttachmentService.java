package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps the attachment list on the control and its upload records (author, stage) in step, and decides who may delete
 * a file. Since V30 (2026-11-20) a control has one list, Attachment in Details: the Documents tab is gone, its files
 * were copied into Details; attachment_documents_path and the DOCUMENTS records are kept as they were and no longer
 * read for showing, downloading or deleting.
 */
@Service
@RequiredArgsConstructor
public class ControlAttachmentService {

    private final ControlAttachmentRepository attachmentRepository;
    private final IControlService controlService;
    /** The file lists live on the control row: one change of them at a time (two uploads at once kept one) */
    private final ControlRowLock controlRowLock;
    private final ControlRepository controlRepository;

    /** Appends uploaded file names to the control's Attachment list and records who uploaded them in which stage. */
    @Transactional
    public Control recordUpload(Control control, List<String> files, User uploader) {
        control = controlRowLock.lockedFresh(control);
        if (!files.isEmpty()) {
            // Only adds: the list read under the lock, the new names after it
            writeList(control, appendToList(control.getAttachmentDetailsPath(), files));
        }
        Control saved = controlService.updateControl(control);

        String stage = normalizeStatus(control.getPerformanceStatus());
        LocalDateTime now = LocalDateTime.now(Notification.ZONE);
        saveRecords(control.getId(), ControlAttachment.TAB_DETAILS, files, uploader, stage, now);
        return saved;
    }

    /**
     * SoQM Team and admins may delete any file. Others only a file they uploaded themselves, while the
     * control is still in the stage it was uploaded in and they can edit it; files without an upload
     * record, author or stage (attached before V5) are left to SoQM Team.
     */
    public boolean canDelete(Control control, String fileName, User user, ControlPermission permission) {
        if (permission == null || !permission.canView()) {
            return false;
        }
        if (permission.canEditAll()) {
            return true;
        }
        if (!permission.canEdit() || user == null || user.getMail() == null) {
            return false;
        }
        Optional<ControlAttachment> record = attachmentRepository.findByControlIdAndTabAndFileName(control.getId(),
                ControlAttachment.TAB_DETAILS, fileName);
        return record
                .filter(r -> r.getUploadedByEmail() != null
                        && r.getUploadedByEmail().trim().equalsIgnoreCase(user.getMail().trim()))
                .filter(r -> r.getUploadedStage() != null
                        && r.getUploadedStage().equals(normalizeStatus(control.getPerformanceStatus())))
                .isPresent();
    }

    /**
     * Whether the file belongs to the control: on its Attachment list or in its Attachment upload records. A name only
     * on the old Documents list (kept since V30, all copied into Details) is not: a file deleted from Details stays
     * deleted.
     */
    public boolean isAttached(Control control, String fileName) {
        if (control == null || fileName == null || fileName.isBlank()) {
            return false;
        }
        String name = fileName.trim();
        return isListed(control.getAttachmentDetailsPath(), name)
                || (control.getId() != null && attachmentRepository.existsByControlIdAndTabAndFileName(control.getId(),
                ControlAttachment.TAB_DETAILS, name));
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

    /** Removes the file name from the control's Attachment list and its upload record; returns whether it was listed. */
    @Transactional
    public boolean removeFromControl(Control control, String fileName) {
        control = controlRowLock.lockedFresh(control);
        String tab = ControlAttachment.TAB_DETAILS;
        String currentPath = control.getAttachmentDetailsPath();
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
        writeList(control, newPath);
        controlService.updateControl(control);
        attachmentRepository.deleteByControlIdAndTabAndFileName(control.getId(), tab, fileName.trim());
        return removed;
    }

    /**
     * An attached file replaced by a new one under the same name (Replace): the name stays on the list, its upload
     * record now says who replaced it, in which stage and when.
     */
    @Transactional
    public void recordReplace(Control control, String fileName, User uploader) {
        control = controlRowLock.lockedFresh(control);
        if (!isListed(control.getAttachmentDetailsPath(), fileName)) {
            writeList(control, appendToList(control.getAttachmentDetailsPath(), List.of(fileName)));
        }
        saveRecords(control.getId(), ControlAttachment.TAB_DETAILS, List.of(fileName), uploader,
                normalizeStatus(control.getPerformanceStatus()), LocalDateTime.now(Notification.ZONE));
    }

    /** The list in the row and on the entity in hand (whose own save does not write it). */
    private void writeList(Control control, String list) {
        controlRepository.setAttachmentList(control.getId(), list);
        control.setAttachmentDetailsPath(list);
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
