package com.kpmg.qtracker.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
        return saveFile(file, controlFolder, java.util.Set.of());
    }

    /**
     * Saves the file under a name free both on the disk and among the names the control already lists (taken, any
     * case): "name.pdf", else "name (1).pdf", ... The file is created only if the name is still free at that moment
     * (CREATE_NEW), so two uploads of one name at the same time get two names and neither overwrites the other
     * (2026-11-20: the check and the copy were two steps, and a parallel upload of the same name failed or replaced).
     */
    public String saveFile(MultipartFile file, String controlFolder, java.util.Set<String> taken) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }

        // Create upload directory if it doesn't exist
        Path uploadPath = folderPath(controlFolder);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
            log.debug("Attachment folder created: {}", uploadPath);
        }

        String uniqueFilename = toStoredFilename(file.getOriginalFilename());
        String baseName = uniqueFilename;
        String extensionSuffix = "";
        int dotIndex = uniqueFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = uniqueFilename.substring(0, dotIndex);
            extensionSuffix = uniqueFilename.substring(dotIndex);
        }
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (String name : taken == null ? java.util.Set.<String>of() : taken) {
            listed.add(name.trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (int counter = 1; counter < 10_000; counter++) {
            Path filePath = uploadPath.resolve(uniqueFilename);
            if (!listed.contains(uniqueFilename.toLowerCase(java.util.Locale.ROOT)) && !Files.exists(filePath)) {
                try (java.io.InputStream in = file.getInputStream();
                     java.io.OutputStream out = Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                    log.debug("Attachment saved in {}", uploadPath);
                    return uniqueFilename;
                } catch (java.nio.file.FileAlreadyExistsException takenMeanwhile) {
                    // Another upload took the name a moment ago: the next one
                }
            }
            uniqueFilename = baseName + " (" + counter + ")" + extensionSuffix;
        }
        throw new IOException("No free file name for the attachment");
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
     * folders existed lie. NoSuchFileException when none has it; SecurityException for a name that would
     * leave its folder.
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
        throw new NoSuchFileException(filename);
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
     * Moves one file from a folder to another (Rename ID gathering files left under an earlier ID). Returns
     * false when the old folder does not have it or the new one already has a file of that name.
     */
    public boolean moveFile(String filename, String fromFolder, String toFolder) throws IOException {
        if (!hasFolder(fromFolder) || !hasFolder(toFolder)) {
            return false;
        }
        Path source = filePath(filename, fromFolder);
        Path target = filePath(filename, toFolder);
        if (source.equals(target) || !Files.isRegularFile(source) || Files.exists(target)) {
            return false;
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target);
        Path from = source.getParent();
        try (Stream<Path> rest = Files.list(from)) {
            if (rest.findAny().isEmpty()) {
                Files.delete(from);
            }
        }
        return true;
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
        log.debug("Attachment deleted from {}", filePath.getParent());
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

    /**
     * Removes a control's folder once nothing is left in it (after its files were deleted); a folder with any file in it
     * stays - another control may share it (folder names are sanitised Control IDs). Never the upload root itself.
     *
     * @return whether the folder was removed
     */
    public boolean deleteFolderIfEmpty(String controlFolder) throws IOException {
        if (!hasFolder(controlFolder)) {
            return false;
        }
        Path folder = folderPath(controlFolder);
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> rest = Files.list(folder)) {
            if (rest.findAny().isPresent()) {
                return false;
            }
        }
        Files.delete(folder);
        return true;
    }

    /** The folder of a control's files: its Control ID, or its database id while it has no Control ID. */
    public static String controlFolder(String controlId, Long id) {
        if (controlId == null || controlId.isBlank()) {
            return id == null ? null : String.valueOf(id);
        }
        return controlId;
    }

    // ---- Pictures of the rich-text fields (RichImageController, 2026-11-23) ----

    /** The folder of a control's pictures, by its database id (a Rename ID does not touch it). */
    public static String richImageFolder(long controlId) {
        return "rich-images_" + controlId;
    }

    /** Stores a picture under a name nobody has (CREATE_NEW: never over another file). */
    public void saveRichImage(long controlId, String name, byte[] bytes) throws IOException {
        Path folder = folderPath(richImageFolder(controlId));
        Files.createDirectories(folder);
        Files.write(filePath(name, richImageFolder(controlId)), bytes, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    public byte[] readRichImage(long controlId, String name) throws IOException {
        return Files.readAllBytes(filePath(name, richImageFolder(controlId)));
    }

    /** Copies these pictures of one control to another (Create Control); a missing one is left out. */
    public int copyRichImages(long fromControlId, long toControlId, java.util.Collection<String> names) throws IOException {
        int copied = 0;
        for (String name : names) {
            Path from = filePath(name, richImageFolder(fromControlId));
            if (!Files.isRegularFile(from)) {
                continue;
            }
            Files.createDirectories(folderPath(richImageFolder(toControlId)));
            Path to = filePath(name, richImageFolder(toControlId));
            if (!Files.exists(to)) {
                Files.copy(from, to);
                copied++;
            }
        }
        return copied;
    }

    /** Deletes a control's pictures and their folder (the control itself is gone: deactivation, data retention). */
    public int deleteRichImages(long controlId) throws IOException {
        Path folder = folderPath(richImageFolder(controlId));
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.delete(file);
                    deleted++;
                }
            }
        }
        Files.deleteIfExists(folder);
        return deleted;
    }

    /**
     * Gets MIME type for a file
     */
    public String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        
        String lower = filename.toLowerCase();
        // Files attached before the types were limited
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".txt")) return "text/plain";
        return AttachmentTypes.mimeType(lower);
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
