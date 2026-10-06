-- SoQM may return a completed control to an earlier status (business decision 4). reopened_at marks such a
-- control until it is completed again; overdue notices skip it, since its deadline is kept as it was
-- (TODO: BUSINESS CONFIRMATION: a new deadline for the correction). Existing rows: NULL.

ALTER TABLE controls ADD COLUMN IF NOT EXISTS reopened_at TIMESTAMP;
