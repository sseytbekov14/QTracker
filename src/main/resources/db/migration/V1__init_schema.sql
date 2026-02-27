CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL,
    username VARCHAR(255) NOT NULL,
    mail VARCHAR(255) NOT NULL,
    displayname VARCHAR(255),
    role VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    title VARCHAR(255),
    password VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS controls (
    id BIGSERIAL,
    control_id VARCHAR(255),
    control_frequency VARCHAR(255),
    control_category VARCHAR(255),
    control_type VARCHAR(255),
    component VARCHAR(255),
    operated_by VARCHAR(255),
    references_to_control VARCHAR(255),
    priority VARCHAR(255),
    non_audit_services_applicability VARCHAR(255),
    homogeneity VARCHAR(255),
    control_description VARCHAR(2000),
    prp VARCHAR(2000),
    soqm_head_comments VARCHAR(2000),
    process_owner_comments VARCHAR(2000),
    created_by BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deadline DATE,
    attachment_details_path VARCHAR(500),
    attachment_documents_path VARCHAR(500),
    process_name VARCHAR(255),
    department VARCHAR(255),
    process_activities VARCHAR(2000),
    other_related_controls VARCHAR(255),
    it_applications VARCHAR(255),
    control_steps_performed VARCHAR(2000),
    facilitator VARCHAR(255),
    control_operator VARCHAR(255),
    soqm_lead VARCHAR(255),
    process_owner VARCHAR(255),
    control_shared_with VARCHAR(255),
    control_operation_date DATE,
    control_operation_deadline DATE,
    next_control_operation_date DATE,
    soqm_development_materials VARCHAR(255),
    performance_status VARCHAR(255) NOT NULL DEFAULT 'DRAFT',
    control_status VARCHAR(255),
    soqm_year VARCHAR(255),
    return_to_facilitator_comment VARCHAR(2000),
    return_to_operator_comment VARCHAR(2000),
    return_to_soqm_team_comment VARCHAR(2000)
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL,
    control_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    link VARCHAR(500),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS workflow_steps (
    id BIGSERIAL,
    sequence_order INT,
    assigned_at TIMESTAMP,
    completed_at TIMESTAMP,
    control_id BIGINT,
    comments VARCHAR(2000),
    assigned_to_email VARCHAR(255),
    assigned_to_name VARCHAR(255),
    return_reason VARCHAR(255),
    returned_to_step VARCHAR(255),
    status VARCHAR(255),
    step_type VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS workflow_history (
    id BIGSERIAL,
    control_id BIGINT,
    created_at TIMESTAMP,
    comments VARCHAR(2000),
    action_type VARCHAR(255),
    from_step VARCHAR(255),
    performed_by_email VARCHAR(255),
    performed_by_name VARCHAR(255),
    to_step VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS control_notification_log (
    id BIGSERIAL,
    control_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    notification_code VARCHAR(255) NOT NULL,
    scheduled_date DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGSERIAL,
    control_id BIGINT,
    created_at TIMESTAMP,
    ip_address VARCHAR(255),
    action_type VARCHAR(255) NOT NULL,
    control_control_id VARCHAR(255),
    user_agent VARCHAR(500),
    action_description TEXT,
    admin_email VARCHAR(255) NOT NULL,
    admin_name VARCHAR(255),
    changed_fields TEXT,
    new_values TEXT,
    previous_values TEXT
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'users_pkey' AND t.relname = 'users'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'users_username_key' AND t.relname = 'users'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_username_key UNIQUE (username);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'users_mail_key' AND t.relname = 'users'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_mail_key UNIQUE (mail);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'controls_pkey' AND t.relname = 'controls'
    ) THEN
        ALTER TABLE controls ADD CONSTRAINT controls_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'controls_control_id_key' AND t.relname = 'controls'
    ) THEN
        ALTER TABLE controls ADD CONSTRAINT controls_control_id_key UNIQUE (control_id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'fk_controls_created_by_users' AND t.relname = 'controls'
    ) THEN
        ALTER TABLE controls
            ADD CONSTRAINT fk_controls_created_by_users
            FOREIGN KEY (created_by) REFERENCES users (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'chk_controls_control_status' AND t.relname = 'controls'
    ) THEN
        ALTER TABLE controls
            ADD CONSTRAINT chk_controls_control_status
            CHECK (
                control_status IS NULL OR
                control_status IN ('NOT_APPLICABLE', 'ACTIVE', 'DELETED', 'SUPERSEDED')
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'notifications_pkey' AND t.relname = 'notifications'
    ) THEN
        ALTER TABLE notifications ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_created ON notifications (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_control ON notifications (control_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'workflow_steps_pkey' AND t.relname = 'workflow_steps'
    ) THEN
        ALTER TABLE workflow_steps ADD CONSTRAINT workflow_steps_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'workflow_steps_step_type_check' AND t.relname = 'workflow_steps'
    ) THEN
        ALTER TABLE workflow_steps
            ADD CONSTRAINT workflow_steps_step_type_check
            CHECK (step_type IN ('FACILITATOR', 'CONTROL_OPERATOR', 'SOQM_LEAD', 'PROCESS_OWNER'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'workflow_steps_status_check' AND t.relname = 'workflow_steps'
    ) THEN
        ALTER TABLE workflow_steps
            ADD CONSTRAINT workflow_steps_status_check
            CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'REVIEW', 'SOQM_HEAD_REVIEW', 'PROCESS_OWNER_REVIEW', 'COMPLETED'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'workflow_history_pkey' AND t.relname = 'workflow_history'
    ) THEN
        ALTER TABLE workflow_history ADD CONSTRAINT workflow_history_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'workflow_history_action_type_check' AND t.relname = 'workflow_history'
    ) THEN
        ALTER TABLE workflow_history
            ADD CONSTRAINT workflow_history_action_type_check
            CHECK (
                action_type IN (
                    'INITIATE',
                    'SUBMIT_TO_OPERATOR',
                    'SUBMIT_TO_SOQM_LEAD',
                    'RETURN_TO_FACILITATOR',
                    'SUBMIT_TO_PROCESS_OWNER',
                    'RETURN_TO_OPERATOR',
                    'APPROVE',
                    'RETURN',
                    'REJECT',
                    'COMMENT',
                    'REASSIGN'
                )
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'control_notification_log_pkey' AND t.relname = 'control_notification_log'
    ) THEN
        ALTER TABLE control_notification_log ADD CONSTRAINT control_notification_log_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'ukmws2eb14dw35e6vsimhx1dvna'
          AND t.relname = 'control_notification_log'
    ) THEN
        ALTER TABLE control_notification_log
            ADD CONSTRAINT ukmws2eb14dw35e6vsimhx1dvna
            UNIQUE (control_id, notification_code, scheduled_date);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        WHERE c.conname = 'admin_audit_log_pkey' AND t.relname = 'admin_audit_log'
    ) THEN
        ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_pkey PRIMARY KEY (id);
    END IF;
END $$;
