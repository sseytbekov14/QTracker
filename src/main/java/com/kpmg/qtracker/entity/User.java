package com.kpmg.qtracker.entity;

import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.AccessPolicy;
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

    /**
     * Stored for reports and old tooling only: it follows the level on every save (SoQM Team = true, see
     * {@code AccessPolicy.Profile#adminAccess}); the Admin Panel is decided by the level alone.
     */
    @Column(name = "admin_access")
    private Boolean adminAccess = false;

    /** What the user may do; a user saved without one gets the least access. */
    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 20)
    private AccessLevel accessLevel = AccessLevel.READ_ONLY;

    /** Which controls the user sees. */
    @Enumerated(EnumType.STRING)
    @Column(name = "access_scope", nullable = false, length = 20)
    private AccessScope accessScope = AccessScope.OWN;

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
        syncAdminAccess();
    }

    @PreUpdate
    void preUpdate() {
        syncAdminAccess();
    }

    private void syncAdminAccess() {
        adminAccess = AccessPolicy.Profile.of(accessLevel, accessScope).adminAccess();
    }
}
