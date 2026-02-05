package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class FileAttachmentController {

    private final FileStorageService fileStorageService;
    private final ControlService controlService;

    /**
     * Upload files for a control
     * POST /api/attachments/upload/{controlId}
     */
    @PostMapping("/upload/{controlId}")
    public ResponseEntity<Map<String, Object>> uploadFiles(
            @PathVariable Long controlId,
            @RequestParam(value = "attachmentDetails", required = false) MultipartFile[] detailsFiles,
            @RequestParam(value = "attachmentDocuments", required = false) MultipartFile[] documentsFiles) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            System.out.println("📤 Upload request for control ID: " + controlId);
            
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found: " + controlId));

            // Save details attachments (multiple files)
            if (detailsFiles != null && detailsFiles.length > 0) {
                StringBuilder filenames = new StringBuilder();
                String oldFiles = control.getAttachmentDetailsPath();
                
                for (MultipartFile file : detailsFiles) {
                    if (file != null && !file.isEmpty()) {
                        String filename = fileStorageService.saveFile(file);
                        if (filenames.length() > 0) {
                            filenames.append(";"); // Use semicolon as separator
                        }
                        filenames.append(filename);
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
                
                for (MultipartFile file : documentsFiles) {
                    if (file != null && !file.isEmpty()) {
                        String filename = fileStorageService.saveFile(file);
                        if (filenames.length() > 0) {
                            filenames.append(";");
                        }
                        filenames.append(filename);
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
    public ResponseEntity<byte[]> downloadFile(@PathVariable String filename) {
        try {
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            byte[] fileContent = fileStorageService.downloadFile(decodedFilename);
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
    public ResponseEntity<byte[]> viewFile(@PathVariable String filename) {
        try {
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            byte[] fileContent = fileStorageService.downloadFile(decodedFilename);
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
}
