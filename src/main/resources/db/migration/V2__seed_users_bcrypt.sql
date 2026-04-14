-- Seed canonical dev/test users with BCrypt password hash (raw password: aaa).
WITH seed_users(mail, displayname, role, enabled, admin_access, password_hash) AS (
    VALUES
    ('fac1@qtracker.local', 'Facilitator 1', 'FACILITATOR', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('fac2@qtracker.local', 'Facilitator 2', 'FACILITATOR', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('op1@qtracker.local', 'Control Operator 1', 'CONTROL_OPERATOR', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('op2@qtracker.local', 'Control Operator 2', 'CONTROL_OPERATOR', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('admin@qtracker.local', 'Admin User', 'ADMIN', TRUE, TRUE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('soqm1@qtracker.local', 'SoQM Team 1', 'SOQM_TEAM', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('soqm2@qtracker.local', 'SoQM Team 2', 'SOQM_TEAM', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('po1@qtracker.local', 'Process Owner 1', 'PROCESS_OWNER', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('po2@qtracker.local', 'Process Owner 2', 'PROCESS_OWNER', TRUE, FALSE, '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq')
)
INSERT INTO users (mail, displayname, role, enabled, admin_access, password, created_at)
SELECT mail, displayname, role, enabled, admin_access, password_hash, CURRENT_TIMESTAMP
FROM seed_users
ON CONFLICT DO NOTHING;

UPDATE users
SET admin_access = TRUE
WHERE UPPER(TRIM(role)) = 'ADMIN';

WITH seed_hashes(mail, password_hash) AS (
    VALUES
    ('fac1@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('fac2@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('op1@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('op2@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('admin@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('soqm1@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('soqm2@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('po1@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
    ('po2@qtracker.local', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq')
)
UPDATE users u
SET password = s.password_hash
FROM seed_hashes s
WHERE u.mail = s.mail
  AND (u.password IS NULL OR u.password !~ '^[$]2[aby][$]');
