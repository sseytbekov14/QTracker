package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Who uploaded an attachment and in which workflow stage. The file list itself stays in
 * controls.attachment_details_path / attachment_documents_path; rows migrated from before V5
 * have no stage (and may have no author).
 */
@Entity
@Table(name = "control_attachments",
        uniqueConstraints = @UniqueConstraint(name = "uq_control_attachments_file",
                columnNames = {"control_id", "tab", "file_name"}))
@Data
public class ControlAttachment {
    public static final String TAB_DETAILS = "DETAILS";
    public static final String TAB_DOCUMENTS = "DOCUMENTS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id", nullable = false)
    private Long controlId;

    @Column(name = "tab", nullable = false, length = 20)
    private String tab;

    @Column(name = "file_name", nullable = false, length = 1000)
    private String fileName;

    @Column(name = "uploaded_by_email")
    private String uploadedByEmail;

    /** Control performance status at upload time, e.g. IN_PROGRESS. */
    @Column(name = "uploaded_stage", length = 50)
    private String uploadedStage;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
}
