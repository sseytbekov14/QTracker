-- Create Control on the Assignment tab (business decision of 2026-10-14) makes the next cycle of a Monthly or
-- Quarterly control: the new control keeps the control it was created from. Empty for every other control (those
-- created by hand or by the daily auto-creation, and every control from before this version).
-- If the source row is ever removed, the link goes (the new control stays).

ALTER TABLE controls ADD COLUMN IF NOT EXISTS created_from_control_id BIGINT;

ALTER TABLE controls ADD CONSTRAINT fk_controls_created_from
    FOREIGN KEY (created_from_control_id) REFERENCES controls (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_controls_created_from ON controls (created_from_control_id);
