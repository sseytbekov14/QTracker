package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "controls")
@Data
public class ControlAssignment {
    @Id
    @Column(name = "id")
    private Long controlId;

    // Comma-separated user emails; TEXT so several users per role don't overflow the column
    @Column(columnDefinition = "TEXT")
    private String facilitator;
    @Column(columnDefinition = "TEXT")
    private String controlOperator;
    @Column(name = "soqm_team", columnDefinition = "TEXT")
    private String soqmLead;
    @Column(columnDefinition = "TEXT")
    private String processOwner;
    @Column(columnDefinition = "TEXT")
    private String controlSharedWith;

    private LocalDate controlOperationDate;
    private LocalDate controlOperationDeadline;
    private LocalDate nextControlOperationDate;

}
