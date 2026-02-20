package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "controls")
@Data
public class ControlDetails {
    @Id
    @Column(name = "id")
    private Long controlId;

    private String processName;
    private String homogeneity;
    private String referencesToControl;
    private String department;

    @Column(length = 2000)
    private String processActivities;


    private String otherRelatedControls;
    private String itApplications;

    @Column(length = 2000)
    private String controlStepsPerformed;

    @Column(name = "soqm_head_comments", length = 2000)
    private String soqmHeadComments;

    @Column(name = "process_owner_comments", length = 2000)
    private String processOwnerComments;

}
