package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "control_details")
@Data
public class ControlDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    private String processName;
    private String homogeneity;
    private String referencesToControl;
    private String department;

    @Column(length = 2000)
    private String processActivities;

    @Column(length = 2000)
    private String controlOperatorsProgram;

    private String otherRelatedControls;
    private String itApplications;

    @Column(length = 2000)
    private String controlStepsPerformed;

    @Column(length = 2000)
    private String soqmHeadComments;

    @Column(length = 2000)
    private String processOwnerComments;

    private String attachedFile;
}