package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ErrorResponse;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.AttachmentTypes;
import com.kpmg.qtracker.service.CompletedEdit;
import com.kpmg.qtracker.service.ControlAttachmentService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlRenameService;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
@Slf4j
public class FileAttachmentController {

    private final FileStorageService fileStorageService;
    private final ControlService controlService;
    private final ControlPermissionService controlPermissionService;
    private final AdminAuditService adminAuditService;
    private final ControlAttachmentService controlAttachmentService;
    private final PermissionService permissionService;
    private final ControlRenameService controlRenameService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_FILES_PER_TAB = 50;
    static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    static final String FILE_NOT_FOUND_MESSAGE = "File not found";

    @Value("${file.upload.max-file-size-mb:10}")
    private long maxFileSizeMb;

    /**
     * Upload files to a control's Attachment (Details)
     * POST /api/attachments/upload/{controlId}
     * The Documents tab is gone (V30, 2026-11-20): files a page opened before still sends as attachmentDocuments are
     * added to Attachment too, not dropped.
     * replace (2026-11-23): names of attached files the new files of the same name take the place of (the page's
     * "Replace"); allowed to whoever may delete that file, not on a completed control. Without it a file of an
     * attached name is kept beside it as "name (1)" ("Keep both").
     */
    @PostMapping("/upload/{controlId}")
    public ResponseEntity<Map<String, Object>> uploadFiles(
            @PathVariable Long controlId,
            @RequestParam(value = "attachmentDetails", required = false) MultipartFile[] detailsFiles,
            @RequestParam(value = "attachmentDocuments", required = false) MultipartFile[] documentsFiles,
            @RequestParam(value = "editReason", required = false) String editReason,
            @RequestParam(value = "replace", required = false) List<String> replace,
            HttpSession session) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            log.debug("Upload to control {}", controlId);
            
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found: " + controlId));
            User currentUser = getCurrentUser(session);
            if (currentUser == null) {
                response.put("success", false);
                response.put("message", "User not authenticated");
                return ResponseEntity.status(401).body(response);
            }
            
            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            if (!permission.canEdit()) {
                response.put("success", false);
                response.put("message", permission.editRefusal("You do not have permission to attach files to this control"));
                return ResponseEntity.status(403).body(response);
            }
            MultipartFile[] files = join(detailsFiles, documentsFiles);
            Optional<String> reasonRefusal = CompletedEdit.refusal(permission, countIncomingFiles(files) > 0, editReason);
            if (reasonRefusal.isPresent()) {
                response.put("success", false);
                response.put("message", reasonRefusal.get());
                return ResponseEntity.badRequest().body(response);
            }
            String completedEditReason = CompletedEdit.reasonOf(permission, editReason);
            
            String controlFolder = resolveControlFolder(control);

            // Validate everything before writing any file to disk
            List<String> errors = new ArrayList<>();
            errors.addAll(validateIncomingFiles(files, control.getAttachmentDetailsPath(), "Details"));
            if (!errors.isEmpty()) {
                response.put("success", false);
                response.put("message", String.join(" ", errors));
                response.put("errors", errors);
                return ResponseEntity.badRequest().body(response);
            }

            // Replace: each new file of an attached name the page asked to replace takes that file's place
            Map<MultipartFile, String> replacing = new java.util.LinkedHashMap<>();
            List<MultipartFile> keeping = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String target = replaceTarget(control, file, replace);
                if (target == null) {
                    keeping.add(file);
                    continue;
                }
                if (permission.isCompletedEdit()) {
                    response.put("success", false);
                    response.put("message", "\"" + target + "\" cannot be replaced on a completed control: keep both,"
                            + " or hide the old file first.");
                    return ResponseEntity.badRequest().body(response);
                }
                if (!controlAttachmentService.canDelete(control, target, currentUser, permission)) {
                    response.put("success", false);
                    response.put("message", "You cannot replace \"" + target + "\": only the user who uploaded it"
                            + " (in the same workflow stage) or SoQM Admin Team can. Keep both instead.");
                    return ResponseEntity.status(403).body(response);
                }
                replacing.put(file, target);
            }
            files = keeping.toArray(new MultipartFile[0]);

            List<String> added = new ArrayList<>();
            List<String> replaced = new ArrayList<>();
            try {
                if (files.length > 0) {
                    saveFiles(files, controlFolder, listedNames(control), added);
                    response.put("detailsFiles", String.join(";", added));
                }
                for (Map.Entry<MultipartFile, String> entry : replacing.entrySet()) {
                    List<String> saved = new ArrayList<>();
                    saveFiles(new MultipartFile[] {entry.getKey()}, controlFolder, listedNames(control), saved);
                    fileStorageService.replaceFile(saved.get(0), entry.getValue(), controlFolder,
                            controlRenameService.attachmentFolders(control));
                    controlAttachmentService.recordReplace(control, entry.getValue(), currentUser);
                    replaced.add(entry.getValue());
                }
                if (!replaced.isEmpty()) {
                    response.put("replacedFiles", String.join(";", replaced));
                }

                // File list and upload records (author, stage) in one transaction
                controlAttachmentService.recordUpload(control, added, currentUser);
            } catch (Exception e) {
                // Don't leave orphaned files on disk when the upload fails midway
                deleteQuietly(added, controlFolder);
                throw e;
            }
            logAttachmentAdds(currentUser, control, "DETAILS", added, completedEditReason);
            for (String name : replaced) {
                logAttachmentChange(currentUser, control, "ATTACHMENT_REPLACED", "DETAILS", name, name, completedEditReason);
            }
            
            response.put("success", true);
            response.put("message", "Files uploaded successfully");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Upload to control {} failed", controlId, e);
            response.put("success", false);
            response.put("message", "Upload failed: " + e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /** The attached name this new file is to replace, when the page asked for it; null: kept beside. */
    private static String replaceTarget(Control control, MultipartFile file, List<String> replace) {
        if (replace == null || replace.isEmpty()) {
            return null;
        }
        String stored = FileStorageService.toStoredFilename(file.getOriginalFilename());
        String list = control.getAttachmentDetailsPath();
        if (list == null) {
            return null;
        }
        for (String part : list.split(";")) {
            String name = part.trim();
            if (!name.isEmpty() && name.equalsIgnoreCase(stored)
                    && replace.stream().anyMatch(r -> r != null && r.trim().equals(name))) {
                return name;
            }
        }
        return null;
    }

    /** The files of both form fields, the old Documents one after Attachment's. */
    private static MultipartFile[] join(MultipartFile[] first, MultipartFile[] second) {
        List<MultipartFile> all = new ArrayList<>();
        for (MultipartFile[] part : new MultipartFile[][] {first, second}) {
            if (part != null) {
                all.addAll(java.util.Arrays.asList(part));
            }
        }
        return all.toArray(new MultipartFile[0]);
    }

    /**
     * Validates files for one attachment tab: count limit, allowed type, size and duplicate names
     * (inside the selection and against files already attached to this tab).
     */
    private List<String> validateIncomingFiles(MultipartFile[] files, String existingList, String tabLabel) {
        List<String> errors = new ArrayList<>();
        if (files == null || files.length == 0) {
            return errors;
        }

        if (countExistingFiles(existingList) + countIncomingFiles(files) > MAX_FILES_PER_TAB) {
            errors.add("Maximum " + MAX_FILES_PER_TAB + " files allowed for " + tabLabel + " attachments.");
            return errors;
        }

        Set<String> existingNames = new HashSet<>();
        if (existingList != null) {
            for (String name : existingList.split(";")) {
                if (!name.isBlank()) {
                    existingNames.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        long maxBytes = maxFileSizeMb * 1024 * 1024;
        Set<String> incomingNames = new HashSet<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            String lower = original.toLowerCase(Locale.ROOT);

            // The types in one place (AttachmentTypes): the extension in any case, and for .zip / .msg their signature
            if (!AttachmentTypes.allowedName(lower)) {
                errors.add("File \"" + original + "\" has an unsupported type. Allowed: " + AttachmentTypes.label() + ".");
                continue;
            }
            if (!AttachmentTypes.contentMatches(lower, head(file))) {
                errors.add("File \"" + original + "\" is not a valid ." + AttachmentTypes.extension(lower) + " file.");
                continue;
            }
            if (file.getSize() > maxBytes) {
                errors.add("File \"" + original + "\" exceeds the maximum size of " + maxFileSizeMb + " MB.");
                continue;
            }

            // A name already attached is fine: the new file is saved under a free name, "name (1).pdf"
            // (FileStorageService.saveFile), the one there stays (2026-11-20: two roles, one name)
            String storedName = FileStorageService.toStoredFilename(original).toLowerCase(Locale.ROOT);
            if (!incomingNames.add(storedName)) {
                errors.add("File \"" + original + "\" is selected more than once for " + tabLabel + ".");
            }
        }
        return errors;
    }

    /** The names the control lists (both lists): a new file never takes one of them. */
    private static Set<String> listedNames(Control control) {
        Set<String> names = new HashSet<>();
        for (String list : new String[] {control.getAttachmentDetailsPath(), control.getAttachmentDocumentsPath()}) {
            if (list != null) {
                for (String name : list.split(";")) {
                    if (!name.isBlank()) {
                        names.add(name.trim());
                    }
                }
            }
        }
        return names;
    }

    /** The first bytes of an uploaded file, for its signature (nothing more is read; an archive is never opened). */
    private static byte[] head(MultipartFile file) {
        try (java.io.InputStream in = file.getInputStream()) {
            return in.readNBytes(AttachmentTypes.SIGNATURE_BYTES);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private void saveFiles(MultipartFile[] files, String controlFolder, Set<String> taken, List<String> saved)
            throws java.io.IOException {
        Set<String> takenNow = new HashSet<>(taken);
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                String filename = fileStorageService.saveFile(file, controlFolder, takenNow);
                takenNow.add(filename);
                if (filename != null && !filename.isBlank()) {
                    saved.add(filename);
                }
            }
        }
    }

    private void deleteQuietly(List<String> filenames, String controlFolder) {
        for (String filename : filenames) {
            try {
                fileStorageService.deleteFile(filename, controlFolder);
            } catch (Exception ignored) {
                // best effort cleanup
            }
        }
    }

    /**
     * Download a file
     * GET /api/attachments/download/{filename}
     * Anyone who may read the control downloads the files it lists. Errors are JSON (ErrorResponse): 400 without
     * controlId, 401, 403 "Access denied" and 404 "Control not found" (GlobalExceptionHandler), 404 "File not
     * found" for a file the control does not list or that is missing on disk.
     */
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<?> downloadFile(@PathVariable String filename,
                                          @RequestParam(value = "controlId", required = false) Long controlId,
                                          HttpSession session) {
        if (controlId == null) {
            return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "controlId is required");
        }
        Control control = permissionService.requireReadable(controlId, getCurrentUser(session));
        String decodedFilename;
        try {
            decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return fileNotFound();
        }
        // Only a file this control lists; the storage then refuses any name that would leave its folder
        if (!controlAttachmentService.isAttached(control, decodedFilename)) {
            return fileNotFound();
        }
        try {
            byte[] fileContent = fileStorageService.downloadFile(decodedFilename,
                    controlRenameService.attachmentFolders(control));
            String mimeType = fileStorageService.getMimeType(decodedFilename);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("attachment", decodedFilename))
                    .body(fileContent);
        } catch (NoSuchFileException e) {
            // Listed on the control but gone from the attachments folder
            log.warn("Attachment missing on disk: control {} (id {}), file {}",
                    control.getControlId(), control.getId(), decodedFilename);
            return fileNotFound();
        } catch (SecurityException e) {
            return fileNotFound();
        } catch (IOException e) {
            log.error("Attachment could not be read: control {} (id {}), file {} ({})",
                    control.getControlId(), control.getId(), decodedFilename, e.getClass().getSimpleName());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_UNREADABLE", "The file could not be read");
        }
    }

    private static ResponseEntity<ErrorResponse> fileNotFound() {
        return error(HttpStatus.NOT_FOUND, FILE_NOT_FOUND, FILE_NOT_FOUND_MESSAGE);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        String correlationId = MDC.get("correlationId");
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(code, message, correlationId == null ? "N/A" : correlationId));
    }

    /**
     * Get attachment info for a control
     * GET /api/attachments/info/{controlId}
     */
    @GetMapping("/info/{controlId}")
    public ResponseEntity<Map<String, Object>> getAttachmentInfo(@PathVariable Long controlId, HttpSession session) {
        User currentUser = getCurrentUser(session);
        Control control = permissionService.requireReadable(controlId, currentUser);
        try {
            Map<String, Object> info = new HashMap<>();
            info.put("controlId", controlId);
            info.put("attachmentDetailsPath", control.getAttachmentDetailsPath());

            // Lets the page show the delete button only where the delete endpoint would allow it
            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            info.put("deletableDetails", deletableFiles(control, control.getAttachmentDetailsPath(), currentUser,
                    permission));
            
            return ResponseEntity.ok(info);
            
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete a single file from a control's Attachment list
     * DELETE /api/attachments/delete/{controlId}
     * type: no longer read (only Attachment in Details since V30), kept for pages opened before.
     */
    @DeleteMapping("/delete/{controlId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable Long controlId,
            @RequestParam("filename") String filename,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "editReason", required = false) String editReason,
            HttpSession session) {

        Map<String, Object> response = new HashMap<>();
        try {
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found: " + controlId));
            User currentUser = getCurrentUser(session);
            if (currentUser == null) {
                response.put("success", false);
                response.put("message", "User not authenticated");
                return ResponseEntity.status(401).body(response);
            }
            
            String tabLabel = ControlAttachment.TAB_DETAILS;
            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            if (!controlAttachmentService.canDelete(control, decodedFilename.trim(), currentUser, permission)) {
                response.put("success", false);
                response.put("message", permission.editRefusal(
                        "Only the user who uploaded this file (in the same workflow stage) or SoQM Admin Team can delete it"));
                return ResponseEntity.status(403).body(response);
            }
            Optional<String> reasonRefusal = CompletedEdit.refusal(permission, true, editReason);
            if (reasonRefusal.isPresent()) {
                response.put("success", false);
                response.put("message", reasonRefusal.get());
                return ResponseEntity.badRequest().body(response);
            }

            String currentPath = control.getAttachmentDetailsPath();
            if (currentPath == null || currentPath.isBlank()) {
                response.put("success", false);
                response.put("message", "No files to delete");
                return ResponseEntity.badRequest().body(response);
            }

            boolean removed = controlAttachmentService.removeFromControl(control, decodedFilename);

            // A completed control changed in place only hides the file (AccessPolicy.editsAfterCompletion): off the
            // list and its upload record, so nobody sees or downloads it, but kept on disk (a later upload of the
            // same name gets another name, FileStorageService.saveFile)
            boolean hideOnly = permission.isCompletedEdit();
            if (removed && !hideOnly) {
                try {
                    fileStorageService.deleteFile(decodedFilename, controlRenameService.attachmentFolders(control));
                } catch (Exception e) {
                    log.warn("An attachment file of control {} could not be deleted from the disk ({})", control.getId(),
                            e.getClass().getSimpleName());
                }
            }

            if (removed) {
                logAttachmentChange(currentUser, control, hideOnly ? "ATTACHMENT_HIDDEN" : "ATTACHMENT_REMOVED",
                        tabLabel, decodedFilename, "", CompletedEdit.reasonOf(permission, editReason));
            }
            response.put("success", true);
            response.put("message", hideOnly ? "File hidden" : "File deleted");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private List<String> deletableFiles(Control control, String storedList, User user, ControlPermission permission) {
        List<String> deletable = new ArrayList<>();
        if (user == null || storedList == null || storedList.isBlank()) {
            return deletable;
        }
        for (String part : storedList.split(";")) {
            String name = part.trim();
            if (!name.isEmpty() && !deletable.contains(name)
                    && controlAttachmentService.canDelete(control, name, user, permission)) {
                deletable.add(name);
            }
        }
        return deletable;
    }

    private int countExistingFiles(String storedList) {
        if (storedList == null || storedList.isBlank()) {
            return 0;
        }
        String[] parts = storedList.split(";");
        int count = 0;
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int countIncomingFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return 0;
        }
        int count = 0;
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String resolveControlFolder(Control control) {
        return control == null ? null : FileStorageService.controlFolder(control.getControlId(), control.getId());
    }

    // RFC 6266 header with filename*=UTF-8'' so non-ASCII (e.g. Cyrillic) names download correctly
    private String contentDisposition(String type, String filename) {
        return ContentDisposition.builder(type).filename(filename, StandardCharsets.UTF_8).build().toString();
    }

    private User getCurrentUser(HttpSession session) {
        if (session == null) {
            return null;
        }
        return (User) session.getAttribute("currentUser");
    }

    private void logAttachmentAdds(User user, Control control, String tabLabel, List<String> filenames,
                                   String completedEditReason) {
        if (filenames == null || filenames.isEmpty()) {
            return;
        }
        for (String filename : filenames) {
            if (filename == null || filename.isBlank()) {
                continue;
            }
            logAttachmentChange(user, control, "ATTACHMENT_ADDED", tabLabel, "", filename, completedEditReason);
        }
    }

    /**
     * @param completedEditReason the reason of a change SoQM Team makes to a completed control in place
     *                            (CompletedEdit): the entry is marked "Edited after completion" and holds it; else null
     */
    private void logAttachmentChange(User user, Control control, String actionType, String tabLabel,
                                     String oldFileName, String newFileName, String completedEditReason) {
        if (user == null || user.getMail() == null || user.getMail().isBlank() || control == null) {
            return;
        }
        String fieldLabel = "Attachment (" + tabLabel + ")";
        List<String> changedFields = new ArrayList<>(List.of(fieldLabel));
        Map<String, String> previousValues = new LinkedHashMap<>();
        Map<String, String> newValues = new LinkedHashMap<>();
        if (oldFileName != null && !oldFileName.isBlank()) {
            previousValues.put(fieldLabel, oldFileName);
        }
        if (newFileName != null && !newFileName.isBlank()) {
            newValues.put(fieldLabel, newFileName);
        }
        String description = "Attachment " + tabLabel;
        if (completedEditReason != null) {
            description = CompletedEdit.describe(description);
            CompletedEdit.addReason(changedFields, newValues, completedEditReason);
        }
        try {
            String changedFieldsJson = objectMapper.writeValueAsString(changedFields);
            String previousJson = objectMapper.writeValueAsString(previousValues);
            String newJson = objectMapper.writeValueAsString(newValues);
            adminAuditService.logActionWithChanges(
                    user.getMail(),
                    user.getDisplayName(),
                    actionType,
                    control,
                    description,
                    changedFieldsJson,
                    previousJson,
                    newJson
            );
        } catch (Exception e) {
            log.error("The audit entry of an attachment change of control {} was not saved",
                    control == null ? null : control.getId(), e);
        }
    }
}
