package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role")
    private String role;

    @Column(name = "secondary_role")
    private String secondaryRole;

    @Column(name = "displayname")
    private String displayName;

    @Column(unique = true, name = "mail")
    private String mail;

    @Column(name = "entra_oid", unique = true)
    private String entraOid;

    private Boolean enabled = true;

    @Column(name = "admin_access")
    private Boolean adminAccess = false;

    @Column(name = "password")
    private String password;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
