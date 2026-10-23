package com.kpmg.qtracker.entity;

import com.kpmg.qtracker.util.ValueList;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A Control ID of the list Other Related Controls is chosen from (V17, filled by --import-related-controls): one list
 * for every control. An inactive one is no longer offered; a control that has it keeps it, marked "not in list".
 */
@Entity
@Table(name = "related_control_ref",
        uniqueConstraints = @UniqueConstraint(name = "uq_related_control_ref_key", columnNames = "control_key"))
@Data
@NoArgsConstructor
public class RelatedControlRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id", nullable = false)
    private String controlId;

    /** The Control ID as QTracker compares it (ValueList.key): lower case, no spaces. */
    @Column(name = "control_key", nullable = false)
    private String controlKey;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RelatedControlRef(String controlId) {
        setControlId(controlId);
    }

    /** The Control ID as written; its key follows it. */
    public void setControlId(String controlId) {
        this.controlId = controlId;
        this.controlKey = ValueList.key(controlId);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(Notification.ZONE);
        }
    }
}
