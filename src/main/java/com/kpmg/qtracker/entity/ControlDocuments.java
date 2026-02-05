package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "control_documents")
@Data
public class ControlDocuments {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    private String link;
    private String attachment;
    private String soqmDevelopmentMaterials; // Available, Not Available
}