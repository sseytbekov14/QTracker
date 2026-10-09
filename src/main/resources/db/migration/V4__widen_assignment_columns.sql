-- Assignment roles are stored as comma-separated email lists;
-- VARCHAR(255) overflowed at ~7-8 emails (e.g. Control Shared With).
ALTER TABLE controls ALTER COLUMN facilitator TYPE TEXT;
ALTER TABLE controls ALTER COLUMN control_operator TYPE TEXT;
ALTER TABLE controls ALTER COLUMN soqm_team TYPE TEXT;
ALTER TABLE controls ALTER COLUMN process_owner TYPE TEXT;
ALTER TABLE controls ALTER COLUMN control_shared_with TYPE TEXT;
