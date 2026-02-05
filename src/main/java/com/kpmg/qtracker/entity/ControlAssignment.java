package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "control_assignments")
@Data
public class ControlAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    private String facilitator; // JSON array of user emails
    private String controlOperator; // JSON array of user emails
    private String soqmLead; // JSON array of user emails
    private String processOwner; // JSON array of user emails
    private String controlSharedWith; // JSON array of user emails

    private LocalDate controlOperationDate;
    private LocalDate controlOperationDeadline;
    private LocalDate nextControlOperationDate;

}