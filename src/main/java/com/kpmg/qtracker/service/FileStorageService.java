package com.kpmg.qtracker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
@Slf4j
public class FileStorageService {

    /** Default folder name, in the folder the app is started from, while no file.upload.dir is given. */
    static final String DEFAULT_FOLDER = "uploads";

    @Value("${file.upload.dir:}")
    private String uploadDir;

    /** The attachments folder as an absolute path, fixed at start. */
    private Path root;

    /**
     * Fixes the attachments folder once, creates it when missing and says in the log which folder it is. A
     * relative or missing setting depends on the folder the app is started from, so it is reported as a warning.
     */
    @PostConstruct
    void init() {
        root = resolveRoot(uploadDir);
        if (uploadDir == null || uploadDir.isBlank()) {
            log.warn("Attachments folder: {} (file.upload.dir / FILE_UPLOAD_DIR is not set: the \"{}\" folder of the"
                    + " start folder is used; set an absolute path so every start uses the same folder)", root, DEFAULT_FOLDER);
        } else if (!Paths.get(uploadDir.trim()).isAbsolute()) {
            log.warn("Attachments folder: {} (file.upload.dir \"{}\" is relative to the start folder; set an absolute"
                    + " path so every start uses the same folder)", root, uploadDir.trim());
        } else {
            log.info("Attachments folder: {}", root);
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("Attachments folder {} cannot be created: uploads and downloads will fail ({})", root, e.toString());
        }
    }

    /** The configured folder as an absolute path; without a setting the "uploads" folder of the start folder. */
    static Path resolveRoot(String configured) {
        String folder = configured == null || configured.isBlank() ? DEFAULT_FOLDER : configured.trim();
        return Paths.get(folder).toAbsolutePath().normalize();
    }

    private Path root() {
        return root != null ? root : resolveRoot(uploadDir);
    }

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
        return downloadFile(filename, List.of(controlFolder == null ? "" : controlFolder));
    }

    /**
     * Returns the file bytes for download from the first of the folders that has it (the control's folder,
     * then the folders of its earlier IDs), else from the upload root, where files uploaded before control
     * folders existed lie.
     */
    public byte[] downloadFile(String filename, List<String> controlFolders) throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (String folder : controlFolders) {
            if (hasFolder(folder)) {
                candidates.add(filePath(filename, folder));
            }
        }
        candidates.add(filePath(filename, null));
        for (Path filePath : candidates) {
            if (Files.isRegularFile(filePath)) {
                return Files.readAllBytes(filePath);
            }
        }
        throw new IOException("File not found: " + filename);
    }

    /**
     * Moves a control's files from the folder of its old ID to the folder of its new one (Rename ID). A file
     * whose name the new folder already has stays in the old folder, which is removed once empty.
     * Returns how many files were moved.
     */
    public int moveControlFolder(String fromFolder, String toFolder) throws IOException {
        if (!hasFolder(fromFolder) || !hasFolder(toFolder)) {
            return 0;
        }
        Path from = folderPath(fromFolder);
        Path to = folderPath(toFolder);
        if (from.equals(to) || !Files.isDirectory(from)) {
            return 0;
        }
        if (!Files.exists(to)) {
            int count;
            try (Stream<Path> files = Files.list(from)) {
                count = (int) files.count();
            }
            Files.move(from, to);
            return count;
        }
        int moved = 0;
        List<Path> files;
        try (Stream<Path> list = Files.list(from)) {
            files = list.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path target = to.resolve(file.getFileName());
            if (Files.exists(target)) {
                log.warn("Attachment {} stays in folder {}: folder {} already has a file of that name",
                        file.getFileName(), fromFolder, toFolder);
                continue;
            }
            Files.move(file, target);
            moved++;
        }
        try (Stream<Path> rest = Files.list(from)) {
            if (rest.findAny().isEmpty()) {
                Files.delete(from);
            }
        }
        return moved;
    }

    /**
     * Deletes a file from storage
     */
    public void deleteFile(String filename) throws IOException {
        deleteFile(filename, (String) null);
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

    /** Deletes the file from the first of the control's folders (its own, then its earlier IDs') that has it. */
    public void deleteFile(String filename, List<String> controlFolders) throws IOException {
        if (filename == null || filename.isEmpty()) {
            return;
        }
        for (String folder : controlFolders) {
            if (hasFolder(folder) && Files.isRegularFile(filePath(filename, folder))) {
                deleteFile(filename, folder);
                return;
            }
        }
    }

    /** The folder of a control's files: its Control ID, or its database id while it has no Control ID. */
    public static String controlFolder(String controlId, Long id) {
        if (controlId == null || controlId.isBlank()) {
            return id == null ? null : String.valueOf(id);
        }
        return controlId;
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
        Path basePath = root();
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
