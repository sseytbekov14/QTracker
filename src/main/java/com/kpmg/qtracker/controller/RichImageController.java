package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The pictures of the rich-text fields of a control (2026-11-23): a screenshot pasted or dropped into the editor is
 * uploaded here and the text keeps only its address, /api/controls/{id}/rich-images/{name} (RichText lets no other
 * picture through). Whoever may change the control uploads; whoever may read it sees them - KDN and Read Only too.
 * Only PNG, JPEG and GIF, recognised by their first bytes, up to 5 MB, under a random name in the control's own
 * folder; a picture is never opened or changed on the server.
 */
@Slf4j
@RestController
@RequestMapping("/api/controls/{controlId}/rich-images")
@RequiredArgsConstructor
public class RichImageController {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-f0-9]{32}\\.(png|jpg|gif)");

    private final ControlService controlService;
    private final ControlPermissionService controlPermissionService;
    private final PermissionService permissionService;
    private final FileStorageService fileStorageService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(@PathVariable Long controlId,
                                                      @RequestParam("image") MultipartFile image, HttpSession session) {
        User user = session == null ? null : (User) session.getAttribute("currentUser");
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "User not authenticated"));
        }
        Control control = controlService.getControlById(controlId).orElse(null);
        if (control == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Control not found"));
        }
        ControlPermission permission = controlPermissionService.resolve(control, user);
        if (!permission.canEdit()) {
            return ResponseEntity.status(403).body(Map.of("success", false,
                    "message", permission.editRefusal("You do not have permission to change this control")));
        }
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No picture"));
        }
        if (image.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "A picture is at most 5 MB."));
        }
        try {
            byte[] bytes = image.getBytes();
            String extension = extension(bytes);
            if (extension == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false,
                        "message", "Only PNG, JPEG and GIF pictures can be put into the text."));
            }
            String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
            fileStorageService.saveRichImage(controlId, name, bytes);
            return ResponseEntity.ok(Map.of("success", true, "url", url(controlId, name)));
        } catch (IOException e) {
            log.error("A picture of control id {} could not be stored ({})", controlId, e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "The picture could not be stored"));
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<byte[]> show(@PathVariable Long controlId, @PathVariable String name, HttpSession session) {
        permissionService.requireReadable(controlId, session == null ? null : (User) session.getAttribute("currentUser"));
        if (name == null || !NAME.matcher(name).matches()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = fileStorageService.readRichImage(controlId, name);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType(name)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                    .body(bytes);
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException | SecurityException e) {
            return ResponseEntity.notFound().build();
        }
    }

    static String url(long controlId, String name) {
        return "/api/controls/" + controlId + "/rich-images/" + name;
    }

    /** png, jpg or gif by the first bytes; null for anything else. */
    static String extension(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "gif";
        }
        return null;
    }

    private static String mimeType(String name) {
        return switch (name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            default -> "image/gif";
        };
    }
}
