package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * One workflow move at a time per control: the move locks the control's row (SELECT ... FOR UPDATE) and reads its
 * status again before deciding, so a second move of the same control - a double click, or someone else on another
 * page - waits for the first to commit and is then refused from the status it finds (409), instead of moving the
 * control a second time with duplicate history and notifications.
 */
@Component
public class ControlRowLock {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The control with its row locked until the transaction ends and its state read again under the lock. Outside a
     * persistence context (unit tests building the services by hand) the control is returned as it is.
     */
    public Control lockedFresh(Control control) {
        if (entityManager == null || control == null || control.getId() == null) {
            return control;
        }
        Control managed = entityManager.find(Control.class, control.getId());
        if (managed == null) {
            return control;
        }
        entityManager.refresh(managed, LockModeType.PESSIMISTIC_WRITE);
        return managed;
    }
}
