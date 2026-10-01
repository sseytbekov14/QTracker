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
import com.kpmg.qtracker.service.FileStorageService;
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
                response.put("message", "You do not have permission to attach files to this control");
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
        try {
            if (controlId == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Control control = controlService.findById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            if (!controlPermissionService.resolve(control, currentUser).canView()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            String controlFolder = resolveControlFolder(controlId);
            byte[] fileContent = controlFolder == null
                    ? fileStorageService.downloadFile(decodedFilename)
                    : fileStorageService.downloadFile(decodedFilename, controlFolder);
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
     * View a file in browser (for images, PDFs)
     * GET /api/attachments/view/{filename}
     */
    @GetMapping("/view/{filename:.+}")
    public ResponseEntity<byte[]> viewFile(@PathVariable String filename,
                                           @RequestParam(value = "controlId", required = false) Long controlId,
                                           HttpSession session) {
        try {
            if (controlId == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Control control = controlService.findById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            if (!controlPermissionService.resolve(control, currentUser).canView()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            String controlFolder = resolveControlFolder(controlId);
            byte[] fileContent = controlFolder == null
                    ? fileStorageService.downloadFile(decodedFilename)
                    : fileStorageService.downloadFile(decodedFilename, controlFolder);
            String mimeType = fileStorageService.getMimeType(decodedFilename);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition("inline", decodedFilename))
                    .body(fileContent);
                    
        } catch (Exception e) {
            System.err.println("❌ View error: " + e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get attachment info for a control
     * GET /api/attachments/info/{controlId}
     */
    @GetMapping("/info/{controlId}")
    public ResponseEntity<Map<String, Object>> getAttachmentInfo(@PathVariable Long controlId) {
        try {
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found: " + controlId));
            
            Map<String, Object> info = new HashMap<>();
            info.put("controlId", controlId);
            info.put("attachmentDetailsPath", control.getAttachmentDetailsPath());
            info.put("attachmentDocumentsPath", control.getAttachmentDocumentsPath());
            
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
                response.put("message", "Only the user who uploaded this file (in the same workflow stage) or SoQM Team can delete it");
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
            if (removed && !hasAttachment(otherPath, decodedFilename.trim())) {
                try {
                    String controlFolder = resolveControlFolder(control);
                    fileStorageService.deleteFile(decodedFilename, controlFolder);
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

    private boolean hasAttachment(String storedList, String filename) {
        if (storedList == null || storedList.isBlank()) {
            return false;
        }
        for (String part : storedList.split(";")) {
            if (part.trim().equals(filename)) {
                return true;
            }
        }
        return false;
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
        if (control == null) {
            return null;
        }
        String controlCode = control.getControlId();
        if (controlCode == null || controlCode.isBlank()) {
            return String.valueOf(control.getId());
        }
        return controlCode;
    }

    private String resolveControlFolder(Long controlId) {
        if (controlId == null) {
            return null;
        }
        try {
            return controlService.getControlById(controlId)
                    .map(this::resolveControlFolder)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
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
