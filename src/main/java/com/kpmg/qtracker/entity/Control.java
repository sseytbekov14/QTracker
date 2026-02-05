package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Entity
@Table(name = "control_controls")
@Data
public class Control {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id", unique = true)
    private String controlId;

    private String controlFrequency;
    private String controlCategory;
    private String controlType;
    private String component;
    private String operatedBy;
    private String referencesToControl;
    private String priority;
    private String nonAuditServicesApplicability;
    private String homogeneity;
    private String controlStatus;

    @Column(length = 1000)
    private String controlDescription;

    @Column(length = 1000)
    private String prp;
    
    @Column(name = "control_operators_program", length = 2000)
    private String controlOperatorsProgram;
    
    @Column(name = "soqm_head_comments", length = 2000)
    private String soqmHeadComments;
    
    @Column(name = "process_owner_comments", length = 2000)
    private String processOwnerComments;
    
    @ManyToOne
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deadline")
    private LocalDate deadline;

    @Column(name = "attachment_details_path", length = 500)
    private String attachmentDetailsPath;

    @Column(name = "attachment_documents_path", length = 500)
    private String attachmentDocumentsPath;
}