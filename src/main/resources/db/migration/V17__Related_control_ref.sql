-- The list Other Related Controls is chosen from (business decision of 2026-10-19): Control IDs of an Excel file that
-- SoQM Admin Team give, one list for every control (no filter by component). Filled and kept up to date only by the
-- command --import-related-controls <file> [--apply] [--deactivate-missing]; nothing is ever deleted, an ID left out
-- of a later file becomes inactive (no longer offered) only with --deactivate-missing.
-- control_key is the Control ID compared the way QTracker compares it (lower case, no spaces: ValueList.key), so the
-- same ID cannot be in the list twice in another case or with other spaces (neither H2 nor PostgreSQL share a form of
-- an index on an expression).
-- Undo by hand: db/rollback/V17__undo_related_control_ref.sql.

CREATE TABLE IF NOT EXISTS related_control_ref (
    id BIGSERIAL PRIMARY KEY,
    control_id VARCHAR(255) NOT NULL,
    control_key VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_related_control_ref_key UNIQUE (control_key)
);

CREATE INDEX IF NOT EXISTS idx_related_control_ref_active ON related_control_ref (active);
