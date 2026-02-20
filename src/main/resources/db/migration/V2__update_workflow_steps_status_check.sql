DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'workflow_steps_status_check'
    ) THEN
        ALTER TABLE workflow_steps
            DROP CONSTRAINT workflow_steps_status_check;
    END IF;
END $$;

ALTER TABLE workflow_steps
    ADD CONSTRAINT workflow_steps_status_check
    CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'REVIEW', 'SOQM_HEAD_REVIEW', 'PROCESS_OWNER_REVIEW', 'COMPLETED'));
