package com.kpmg.qtracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class FileStorageService {

    @Value("${file.upload.dir}")
    private String uploadDir;

    /**
     * Saves uploaded file to disk and returns the unique filename
     */
    public String saveFile(MultipartFile file) throws IOException {
        return saveFile(file, null);
    }

    /**
     * Saves uploaded file to disk under a control-specific folder
     */
    public String saveFile(MultipartFile file, String controlFolder) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }

        // Create upload directory if it doesn't exist
        Path uploadPath = folderPath(controlFolder);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
            System.out.println("📁 Created upload directory: " + uploadPath);
        }

        String uniqueFilename = toStoredFilename(file.getOriginalFilename());
        String baseName = uniqueFilename;
        String extensionSuffix = "";
        int dotIndex = uniqueFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = uniqueFilename.substring(0, dotIndex);
            extensionSuffix = uniqueFilename.substring(dotIndex);
        }
        Path filePath = uploadPath.resolve(uniqueFilename);

        int counter = 1;
        while (Files.exists(filePath)) {
            uniqueFilename = baseName + " (" + counter + ")" + extensionSuffix;
            filePath = uploadPath.resolve(uniqueFilename);
            counter++;
        }

        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("✅ File saved: " + filePath);
        return uniqueFilename;
    }

    /**
     * Returns the file bytes for download
     */
    public byte[] downloadFile(String filename, String controlFolder) throws IOException {
        Path filePath = filePath(filename, controlFolder);
        // Files uploaded before control folders existed lie in the upload root
        if (!Files.exists(filePath) && hasFolder(controlFolder)) {
            filePath = filePath(filename, null);
        }
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filename);
        }
        return Files.readAllBytes(filePath);
    }

    /**
     * Deletes a file from storage
     */
    public void deleteFile(String filename) throws IOException {
        deleteFile(filename, null);
    }

    /**
     * Deletes a file from storage within a control folder
     */
    public void deleteFile(String filename, String controlFolder) throws IOException {
        if (filename == null || filename.isEmpty()) {
            return;
        }
        Path filePath = filePath(filename, controlFolder);
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
     * Name under which an uploaded file is stored (before " (n)" de-duplication).
     * Used both for saving and for duplicate-name validation, so they always agree.
     */
    public static String toStoredFilename(String originalFilename) {
        String safeFilename = sanitizeFilename(originalFilename);
        String baseName = safeFilename;
        String extension = "";
        int dotIndex = safeFilename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < safeFilename.length() - 1) {
            baseName = safeFilename.substring(0, dotIndex);
            extension = safeFilename.substring(dotIndex + 1);
        }
        if (baseName.isBlank()) {
            baseName = "file";
        }
        return extension.isBlank() ? baseName : baseName + "." + extension;
    }

    /**
     * Sanitize filename to prevent path traversal
     */
    private static String sanitizeFilename(String filename) {
        if (filename == null) return "unknown";
        // Keep letters/digits of any alphabet (e.g. Cyrillic) so different names don't collapse into "___.pdf"
        return filename.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String sanitizeFolderName(String folder) {
        if (folder == null) return null;
        return folder.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private boolean hasFolder(String controlFolder) {
        String safeFolder = sanitizeFolderName(controlFolder);
        return safeFolder != null && !safeFolder.isBlank();
    }

    /** The control's folder directly under the upload root, or the root itself without a folder. */
    private Path folderPath(String controlFolder) {
        Path basePath = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!hasFolder(controlFolder)) {
            return basePath;
        }
        Path folder = basePath.resolve(sanitizeFolderName(controlFolder)).normalize();
        if (!basePath.equals(folder.getParent())) {
            throw new SecurityException("Path traversal attempt detected!");
        }
        return folder;
    }

    /**
     * A stored file directly inside its folder. The name is checked as given, without relying on the
     * request firewall: separators, "." and "..", drive letters and control characters are refused.
     */
    private Path filePath(String filename, String controlFolder) {
        if (!isPlainFileName(filename)) {
            throw new SecurityException("Path traversal attempt detected!");
        }
        Path folder = folderPath(controlFolder);
        Path filePath = folder.resolve(filename).normalize();
        if (!folder.equals(filePath.getParent())) {
            throw new SecurityException("Path traversal attempt detected!");
        }
        return filePath;
    }

    static boolean isPlainFileName(String filename) {
        if (filename == null || filename.isBlank() || ".".equals(filename) || "..".equals(filename)) {
            return false;
        }
        return filename.chars().noneMatch(c -> c == '/' || c == '\\' || c == ':' || c < 0x20);
    }
}
