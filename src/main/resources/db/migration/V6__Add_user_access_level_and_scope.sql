-- Access model: what a user may do (access_level) and which controls they see (access_scope);
-- the Admin Panel stays the admin_access flag. The old role and secondary_role columns are kept
-- as they are, but nothing checks them for access any more, so new users no longer need a role.

ALTER TABLE users ADD COLUMN IF NOT EXISTS access_level VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS access_scope VARCHAR(20);

-- Backfill from the old roles, compared like the code compared them ("SoQM Team" = "SOQM_TEAM").
-- The first rule that matches wins.

-- KDN in either role column: it used to restrict every other role to viewing KDN controls
UPDATE users
SET access_level = 'PARTICIPANT', access_scope = 'KDN'
WHERE access_level IS NULL
  AND (UPPER(REPLACE(REPLACE(TRIM(role), ' ', '_'), '-', '_')) = 'KDN'
       OR UPPER(REPLACE(REPLACE(TRIM(secondary_role), ' ', '_'), '-', '_')) = 'KDN');

-- SOQM_TEAM, and any other role starting with SOQM (SoQM Head / Delegate), which had the SoQM rights
UPDATE users
SET access_level = 'SOQM', access_scope = 'ALL'
WHERE access_level IS NULL
  AND UPPER(REPLACE(REPLACE(TRIM(role), ' ', '_'), '-', '_')) LIKE 'SOQM%';

-- The ADMIN role string of the seed accounts keeps its admin_access flag; Master as in Power Apps
UPDATE users
SET access_level = 'PARTICIPANT', access_scope = 'ALL'
WHERE access_level IS NULL
  AND UPPER(REPLACE(REPLACE(TRIM(role), ' ', '_'), '-', '_')) IN ('ADMIN', 'MASTER');

UPDATE users
SET access_level = 'PARTICIPANT', access_scope = 'OWN'
WHERE access_level IS NULL
  AND UPPER(REPLACE(REPLACE(TRIM(role), ' ', '_'), '-', '_')) IN ('FACILITATOR', 'CONTROL_OPERATOR', 'PROCESS_OWNER');

-- Anything else (no role, Read Only, unknown values) gets the least access
UPDATE users
SET access_level = 'READ_ONLY', access_scope = 'OWN'
WHERE access_level IS NULL;

ALTER TABLE users ALTER COLUMN access_level SET DEFAULT 'READ_ONLY';
ALTER TABLE users ALTER COLUMN access_scope SET DEFAULT 'OWN';
ALTER TABLE users ALTER COLUMN access_level SET NOT NULL;
ALTER TABLE users ALTER COLUMN access_scope SET NOT NULL;

ALTER TABLE users ADD CONSTRAINT users_access_level_check
    CHECK (access_level IN ('SOQM', 'PARTICIPANT', 'READ_ONLY'));
ALTER TABLE users ADD CONSTRAINT users_access_scope_check
    CHECK (access_scope IN ('OWN', 'ALL', 'KDN'));
-- SoQM sees every control
ALTER TABLE users ADD CONSTRAINT users_soqm_scope_all_check
    CHECK (access_level <> 'SOQM' OR access_scope = 'ALL');

ALTER TABLE users ALTER COLUMN role DROP NOT NULL;
