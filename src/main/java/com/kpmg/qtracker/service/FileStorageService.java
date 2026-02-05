package com.kpmg.qtracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.upload.dir}")
    private String uploadDir;

    /**
     * Saves uploaded file to disk and returns the unique filename
     */
    public String saveFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }

        // Create upload directory if it doesn't exist
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
            System.out.println("📁 Created upload directory: " + uploadPath);
        }

        // Generate unique filename: timestamp_uuid_originalname
        String originalFilename = file.getOriginalFilename();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String uniqueFilename = timestamp + "_" + uniqueId + "_" + sanitizeFilename(originalFilename);

        // Save file
        Path filePath = uploadPath.resolve(uniqueFilename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("✅ File saved: " + filePath);
        return uniqueFilename;
    }

    /**
     * Returns the file bytes for download
     */
    public byte[] downloadFile(String filename) throws IOException {
        Path filePath = Paths.get(uploadDir).resolve(filename);
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filename);
        }
        return Files.readAllBytes(filePath);
    }

    /**
     * Deletes a file from storage
     */
    public void deleteFile(String filename) throws IOException {
        if (filename == null || filename.isEmpty()) {
            return;
        }
        Path filePath = Paths.get(uploadDir).resolve(filename);
        Files.deleteIfExists(filePath);
        System.out.println("🗑️ File deleted: " + filePath);
    }

    /**
     * Gets MIME type for a file
     */
    public String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    /**
     * Sanitize filename to prevent path traversal
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) return "unknown";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
