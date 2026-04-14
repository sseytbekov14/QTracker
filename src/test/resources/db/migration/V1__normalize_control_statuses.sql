UPDATE control_controls
SET control_status = CASE
    WHEN control_status = 'In Progress' THEN 'DRAFT'
    WHEN control_status = 'Facilitator Review' THEN 'IN_PROGRESS'
    WHEN control_status = 'Control Operator Review' THEN 'REVIEW'
    WHEN control_status = 'SoQM Team Review' THEN 'SOQM_HEAD_REVIEW'
    WHEN control_status = 'Process Owner Review' THEN 'PROCESS_OWNER_REVIEW'
    WHEN control_status = 'Returned by Control Operator' THEN 'IN_PROGRESS'
    WHEN control_status = 'Returned by SoQM Team' THEN 'REVIEW'
    WHEN control_status = 'Returned by Process Owner' THEN 'IN_PROGRESS'
    WHEN control_status = 'Completed' THEN 'COMPLETED'
    WHEN control_status = 'Reject' THEN 'IN_PROGRESS'
    WHEN control_status = 'Cancelled' THEN 'COMPLETED'
    ELSE control_status
END;

UPDATE workflow_steps
SET status = CASE
    WHEN status = 'NOT_STARTED' THEN 'DRAFT'
    WHEN status = 'FACILITATOR_REVIEW' THEN 'IN_PROGRESS'
    WHEN status = 'CONTROL_OPERATOR_REVIEW' THEN 'REVIEW'
    WHEN status = 'SOQM_TEAM_REVIEW' THEN 'SOQM_HEAD_REVIEW'
    WHEN status = 'PROCESS_OWNER_REVIEW' THEN 'PROCESS_OWNER_REVIEW'
    WHEN status = 'RETURNED' THEN 'COMPLETED'
    WHEN status = 'REJECTED' THEN 'COMPLETED'
    WHEN status = 'COMPLETED' THEN 'COMPLETED'
    ELSE status
END;

UPDATE workflow_history
SET from_step = CASE
    WHEN from_step = 'Control Operator Review' THEN 'REVIEW'
    WHEN from_step = 'Returned by Control Operator' THEN 'IN_PROGRESS'
    WHEN from_step = 'Process Owner Review' THEN 'PROCESS_OWNER_REVIEW'
    ELSE from_step
END,
    to_step = CASE
    WHEN to_step = 'SoQM Team Review' THEN 'SOQM_HEAD_REVIEW'
    WHEN to_step = 'Facilitator Review' THEN 'IN_PROGRESS'
    WHEN to_step = 'Completed' THEN 'COMPLETED'
    ELSE to_step
END;
