package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAttachment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAttachmentService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlRenameService;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
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
    private static final List<String> ALLOWED_EXTENSIONS =
            List.of(".pdf", ".docx", ".doc", ".xlsx", ".xls", ".csv", ".png", ".jpg", ".jpeg");

    @Value("${file.upload.max-file-size-mb:10}")
    private long maxFileSizeMb;

    /**
     * Upload files for a control
     * POST /api/attachments/upload/{controlId}
     */
    @PostMapping("/upload/{controlId}")
    public ResponseEntity<Map<String, Object>> uploadFiles(
            @PathVariable Long controlId,
            @RequestParam(value = "attachmentDetails", required = false) MultipartFile[] detailsFiles,
            @RequestParam(value = "attachmentDocuments", required = false) MultipartFile[] documentsFiles,
            HttpSession session) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            System.out.println("📤 Upload request for control ID: " + controlId);
            
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
            
            String controlFolder = resolveControlFolder(control);

            // Validate everything before writing any file to disk
            List<String> errors = new ArrayList<>();
            errors.addAll(validateIncomingFiles(detailsFiles, control.getAttachmentDetailsPath(), "Details"));
            errors.addAll(validateIncomingFiles(documentsFiles, control.getAttachmentDocumentsPath(), "Documents"));
            if (!errors.isEmpty()) {
                response.put("success", false);
                response.put("message", String.join(" ", errors));
                response.put("errors", errors);
                return ResponseEntity.badRequest().body(response);
            }

            List<String> addedDetails = new ArrayList<>();
            List<String> addedDocuments = new ArrayList<>();
            try {
                if (detailsFiles != null && detailsFiles.length > 0) {
                    saveFiles(detailsFiles, controlFolder, addedDetails);
                    response.put("detailsFiles", String.join(";", addedDetails));
                }
                if (documentsFiles != null && documentsFiles.length > 0) {
                    saveFiles(documentsFiles, controlFolder, addedDocuments);
                    response.put("documentsFiles", String.join(";", addedDocuments));
                }

                // File lists and upload records (author, stage) in one transaction
                controlAttachmentService.recordUpload(control, addedDetails, addedDocuments, currentUser);
            } catch (Exception e) {
                // Don't leave orphaned files on disk when the upload fails midway
                deleteQuietly(addedDetails, controlFolder);
                deleteQuietly(addedDocuments, controlFolder);
                throw e;
            }
            logAttachmentAdds(currentUser, control, "DETAILS", addedDetails);
            logAttachmentAdds(currentUser, control, "DOCUMENTS", addedDocuments);
            
            response.put("success", true);
            response.put("message", "Files uploaded successfully");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            System.err.println("❌ Upload error: " + e.getMessage());
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "Upload failed: " + e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
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

            if (ALLOWED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
                errors.add("File \"" + original + "\" has an unsupported type. Allowed: PDF, DOCX, DOC, XLSX, XLS, CSV, PNG, JPG, JPEG.");
                continue;
            }
            if (file.getSize() > maxBytes) {
                errors.add("File \"" + original + "\" exceeds the maximum size of " + maxFileSizeMb + " MB.");
                continue;
            }

            String storedName = FileStorageService.toStoredFilename(original).toLowerCase(Locale.ROOT);
            if (existingNames.contains(storedName)) {
                errors.add("File \"" + original + "\" is already attached in " + tabLabel
                        + ". Delete the existing file or rename the new one.");
            } else if (!incomingNames.add(storedName)) {
                errors.add("File \"" + original + "\" is selected more than once for " + tabLabel + ".");
            }
        }
        return errors;
    }

    private void saveFiles(MultipartFile[] files, String controlFolder, List<String> saved) throws java.io.IOException {
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                String filename = fileStorageService.saveFile(file, controlFolder);
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
     */
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String filename,
                                               @RequestParam(value = "controlId", required = false) Long controlId,
                                               HttpSession session) {
        if (controlId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Control control = permissionService.requireReadable(controlId, getCurrentUser(session));
        try {
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8).trim();
            // Only a file this control lists; the storage then refuses any name that would leave its folder
            if (!controlAttachmentService.isAttached(control, decodedFilename)) {
                return ResponseEntity.notFound().build();
            }
            byte[] fileContent = fileStorageService.downloadFile(decodedFilename,
                    controlRenameService.attachmentFolders(control));
            String mimeType = fileStorageService.getMimeType(decodedFilename);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("attachment", decodedFilename))
                    .body(fileContent);
                    
        } catch (Exception e) {
            System.err.println("❌ Download error: " + e.getMessage());
            return ResponseEntity.notFound().build();
        }
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
            info.put("attachmentDocumentsPath", control.getAttachmentDocumentsPath());

            // Lets the page show the delete button only where the delete endpoint would allow it
            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            info.put("deletableDetails", deletableFiles(control, ControlAttachment.TAB_DETAILS,
                    control.getAttachmentDetailsPath(), currentUser, permission));
            info.put("deletableDocuments", deletableFiles(control, ControlAttachment.TAB_DOCUMENTS,
                    control.getAttachmentDocumentsPath(), currentUser, permission));
            
            return ResponseEntity.ok(info);
            
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete a single file from a control's attachment list
     * DELETE /api/attachments/delete/{controlId}
     */
    @DeleteMapping("/delete/{controlId}")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable Long controlId,
            @RequestParam("filename") String filename,
            @RequestParam("type") String type,
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
            
            String tabLabel = "details".equalsIgnoreCase(type) ? ControlAttachment.TAB_DETAILS : ControlAttachment.TAB_DOCUMENTS;
            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            if (!controlAttachmentService.canDelete(control, tabLabel, decodedFilename.trim(), currentUser, permission)) {
                response.put("success", false);
                response.put("message", permission.editRefusal(
                        "Only the user who uploaded this file (in the same workflow stage) or SoQM Team can delete it"));
                return ResponseEntity.status(403).body(response);
            }

            String currentPath = ControlAttachment.TAB_DETAILS.equals(tabLabel)
                    ? control.getAttachmentDetailsPath()
                    : control.getAttachmentDocumentsPath();
            if (currentPath == null || currentPath.isBlank()) {
                response.put("success", false);
                response.put("message", "No files to delete");
                return ResponseEntity.badRequest().body(response);
            }

            boolean removed = controlAttachmentService.removeFromControl(control, tabLabel, decodedFilename);

            // Both tabs share the control folder; keep the file on disk while the other tab still lists it
            String otherPath = ControlAttachment.TAB_DETAILS.equals(tabLabel)
                    ? control.getAttachmentDocumentsPath()
                    : control.getAttachmentDetailsPath();
            if (removed && !ControlAttachmentService.isListed(otherPath, decodedFilename.trim())) {
                try {
                    fileStorageService.deleteFile(decodedFilename, controlRenameService.attachmentFolders(control));
                } catch (Exception e) {
                    System.out.println("⚠️ Could not delete physical file: " + e.getMessage());
                }
            }

            if (removed) {
                logAttachmentChange(currentUser, control, "ATTACHMENT_REMOVED", tabLabel, decodedFilename, "");
            }
            response.put("success", true);
            response.put("message", "File deleted");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    private List<String> deletableFiles(Control control, String tab, String storedList,
                                        User user, ControlPermission permission) {
        List<String> deletable = new ArrayList<>();
        if (user == null || storedList == null || storedList.isBlank()) {
            return deletable;
        }
        for (String part : storedList.split(";")) {
            String name = part.trim();
            if (!name.isEmpty() && !deletable.contains(name)
                    && controlAttachmentService.canDelete(control, tab, name, user, permission)) {
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

    private void logAttachmentAdds(User user, Control control, String tabLabel, List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return;
        }
        for (String filename : filenames) {
            if (filename == null || filename.isBlank()) {
                continue;
            }
            logAttachmentChange(user, control, "ATTACHMENT_ADDED", tabLabel, "", filename);
        }
    }

    private void logAttachmentChange(User user, Control control, String actionType, String tabLabel,
                                     String oldFileName, String newFileName) {
        if (user == null || user.getMail() == null || user.getMail().isBlank() || control == null) {
            return;
        }
        String fieldLabel = "Attachment (" + tabLabel + ")";
        List<String> changedFields = List.of(fieldLabel);
        Map<String, String> previousValues = new LinkedHashMap<>();
        Map<String, String> newValues = new LinkedHashMap<>();
        if (oldFileName != null && !oldFileName.isBlank()) {
            previousValues.put(fieldLabel, oldFileName);
        }
        if (newFileName != null && !newFileName.isBlank()) {
            newValues.put(fieldLabel, newFileName);
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
                    "Attachment " + tabLabel,
                    changedFieldsJson,
                    previousJson,
                    newJson
            );
        } catch (Exception e) {
            System.out.println("⚠️ Failed to log attachment change: " + e.getMessage());
        }
    }
}
