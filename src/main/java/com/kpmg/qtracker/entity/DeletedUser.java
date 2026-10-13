package com.kpmg.qtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A user deleted from the Admin Panel (business decision of 2026-10-13; V12): the users row is gone, the e-mail
 * may still stand in a control's people or Shared With, where it is shown as "e-mail (deleted user)". The audit
 * entry USER_DELETED holds the rest (who, why, which controls).
 */
@Entity
@Table(name = "deleted_users")
@Data
public class DeletedUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "mail", nullable = false)
    private String mail;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "role", length = 64)
    private String role;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by_email", nullable = false)
    private String deletedByEmail;
}
