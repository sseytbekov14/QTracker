package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.FileStorageService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class FileAttachmentController {

    private final FileStorageService fileStorageService;
    private final ControlService controlService;
    private final ControlPermissionService controlPermissionService;
    private final AdminAuditService adminAuditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            List<String> addedDetails = new ArrayList<>();
            List<String> addedDocuments = new ArrayList<>();

            // Save details attachments (multiple files)
            if (detailsFiles != null && detailsFiles.length > 0) {
                StringBuilder filenames = new StringBuilder();
                String oldFiles = control.getAttachmentDetailsPath();

                int existingCount = countExistingFiles(oldFiles);
                int incomingCount = countIncomingFiles(detailsFiles);
                if (existingCount + incomingCount > 50) {
                    response.put("success", false);
                    response.put("message", "Maximum 50 files allowed for Details attachments.");
                    return ResponseEntity.badRequest().body(response);
                }
                
                for (MultipartFile file : detailsFiles) {
                    if (file != null && !file.isEmpty()) {
                        String filename = fileStorageService.saveFile(file, controlFolder);
                        if (filenames.length() > 0) {
                            filenames.append(";"); // Use semicolon as separator
                        }
                        filenames.append(filename);
                        if (filename != null && !filename.isBlank()) {
                            addedDetails.add(filename);
                        }
                        System.out.println("✅ Details file saved: " + filename);
                    }
                }
                
                // Append to existing files or replace
                String existingFiles = oldFiles != null && !oldFiles.isEmpty() ? oldFiles : "";
                String newFileList = existingFiles.isEmpty() ? filenames.toString() : existingFiles + ";" + filenames.toString();
                control.setAttachmentDetailsPath(newFileList);
                response.put("detailsFiles", filenames.toString());
            }

            // Save documents attachments (multiple files)
            if (documentsFiles != null && documentsFiles.length > 0) {
                StringBuilder filenames = new StringBuilder();
                String oldFiles = control.getAttachmentDocumentsPath();

                int existingCount = countExistingFiles(oldFiles);
                int incomingCount = countIncomingFiles(documentsFiles);
                if (existingCount + incomingCount > 50) {
                    response.put("success", false);
                    response.put("message", "Maximum 50 files allowed for Documents attachments.");
                    return ResponseEntity.badRequest().body(response);
                }
                
                for (MultipartFile file : documentsFiles) {
                    if (file != null && !file.isEmpty()) {
                        String filename = fileStorageService.saveFile(file, controlFolder);
                        if (filenames.length() > 0) {
                            filenames.append(";");
                        }
                        filenames.append(filename);
                        if (filename != null && !filename.isBlank()) {
                            addedDocuments.add(filename);
                        }
                        System.out.println("✅ Documents file saved: " + filename);
                    }
                }
                
                // Append to existing files or replace
                String existingFiles = oldFiles != null && !oldFiles.isEmpty() ? oldFiles : "";
                String newFileList = existingFiles.isEmpty() ? filenames.toString() : existingFiles + ";" + filenames.toString();
                control.setAttachmentDocumentsPath(newFileList);
                response.put("documentsFiles", filenames.toString());
            }

            // Update control in database
            controlService.updateControl(control);
            logAttachmentAdds(currentUser, control, "DETAILS", addedDetails);
            logAttachmentAdds(currentUser, control, "DOCUMENTS", addedDocuments);
            
            response.put("success", true);
            response.put("message", "Files uploaded successfully");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            System.err.println("❌ Upload error: " + e.getMessage());
            e.printStackTrace();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
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
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + decodedFilename + "\"")
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
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + decodedFilename + "\"")
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
            
            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            if (!permission.canEdit()) {
                response.put("success", false);
                response.put("message", "You do not have permission to delete files from this control");
                return ResponseEntity.status(403).body(response);
            }
            
            boolean removed = false;
            String tabLabel = "details".equalsIgnoreCase(type) ? "DETAILS" : "DOCUMENTS";

            String currentPath;
            if ("details".equalsIgnoreCase(type)) {
                currentPath = control.getAttachmentDetailsPath();
            } else {
                currentPath = control.getAttachmentDocumentsPath();
            }

            if (currentPath == null || currentPath.isBlank()) {
                response.put("success", false);
                response.put("message", "No files to delete");
                return ResponseEntity.badRequest().body(response);
            }

            // Remove the file from the semicolon-separated list
            String[] files = currentPath.split(";");
            StringBuilder updated = new StringBuilder();
            for (String f : files) {
                if (f.trim().isEmpty()) continue;
                if (f.trim().equals(decodedFilename.trim())) {
                    removed = true;
                    continue; // skip deleted
                }
                if (updated.length() > 0) updated.append(";");
                updated.append(f.trim());
            }

            String newPath = updated.length() > 0 ? updated.toString() : null;
            if ("details".equalsIgnoreCase(type)) {
                control.setAttachmentDetailsPath(newPath);
            } else {
                control.setAttachmentDocumentsPath(newPath);
            }
            controlService.updateControl(control);

            // Try to delete physical file
            try {
                String controlFolder = resolveControlFolder(control);
                fileStorageService.deleteFile(decodedFilename, controlFolder);
            } catch (Exception e) {
                System.out.println("⚠️ Could not delete physical file: " + e.getMessage());
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
