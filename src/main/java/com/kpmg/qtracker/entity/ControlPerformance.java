package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "performance")
@Data
public class ControlPerformance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    private String controlOperator;
    private String facilitator;
    private String controlFrequency;
    private LocalDate controlOperationDate;
    private String soqmYear;
    private LocalDate actualOperationDate;
    private String assignedTo;

    @Column(name = "created_at")
    private LocalDate createdAt;

    @Column(name = "updated_at")
    private LocalDate updatedAt;
}