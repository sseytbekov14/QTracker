package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "controls")
@Data
public class ControlDocuments {
    @Id
    @Column(name = "id")
    private Long controlId;

    private String soqmDevelopmentMaterials; // Available, Not Available
}
