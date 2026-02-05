package com.kpmg.qtracker.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class NotificationItemDTO {
    private String id;                // Unique UUID per notification
    private String type;              // Comment, Status Change, File Upload
    private Long controlId;           // DB id
    private String controlIdNumber;   // Business CONTROL ID
    private String component;
    private String message;           // Short description
    private String fullText;          // Full detailed text
    private String by;                // User or role
    private LocalDateTime timestamp;  // When
    private boolean isRead;           // Read/unread status
    private List<AttachmentDTO> attachments; // Files or links related to this notification
}
