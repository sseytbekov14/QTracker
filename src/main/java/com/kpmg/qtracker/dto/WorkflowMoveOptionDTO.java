package com.kpmg.qtracker.dto;

import lombok.Value;

/**
 * One move SoQM may make on the control it is looking at, for the Move dialog of View Control
 * (AccessPolicy.move): where to, whether it is a return, and for whom SoQM acts.
 */
@Value
public class WorkflowMoveOptionDTO {
    String target;
    String statusLabel;
    String label;
    boolean returnMove;
    boolean onBehalf;
    String actingFor;
    /** The people assigned to the step SoQM acts for, by name. */
    String assigned;
    boolean commentRequired;
}
