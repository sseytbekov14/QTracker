-- Seed canonical dev/test users with BCrypt password hash (raw password: aaa).
WITH seed_users(username, mail, displayname, role, enabled, title, password_hash) AS (
    VALUES
        ('fac1', 'fac1@test.com', 'Lionel Messi', 'FACILITATOR', TRUE, 'Forward - Inter Miami', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac2', 'fac2@test.com', 'Cristiano Ronaldo', 'FACILITATOR', TRUE, 'Forward - Al Nassr', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac3', 'fac3@test.com', 'Kylian Mbappe', 'FACILITATOR', TRUE, 'Forward - Real Madrid', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac4', 'fac4@test.com', 'Kevin De Bruyne', 'FACILITATOR', TRUE, 'Midfielder - Manchester City', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac5', 'fac5@test.com', 'Mohamed Salah', 'FACILITATOR', TRUE, 'Forward - Liverpool', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op1', 'op1@test.com', 'Robert Lewandowski', 'CONTROL_OPERATOR', TRUE, 'Forward - FC Barcelona', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op2', 'op2@test.com', 'Erling Haaland', 'CONTROL_OPERATOR', TRUE, 'Forward - Manchester City', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op3', 'op3@test.com', 'Luka Modric', 'CONTROL_OPERATOR', TRUE, 'Midfielder - Real Madrid', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op4', 'op4@test.com', 'Harry Kane', 'CONTROL_OPERATOR', TRUE, 'Forward - Bayern Munich', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op5', 'op5@test.com', 'Karim Benzema', 'CONTROL_OPERATOR', TRUE, 'Forward - Al Ittihad', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq1', 'sq1@test.com', 'Neymar Jr', 'SOQM_LEAD', TRUE, 'Forward - Al Hilal', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq2', 'sq2@test.com', 'Vinicius Jr', 'SOQM_LEAD', TRUE, 'Forward - Real Madrid', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq3', 'sq3@test.com', 'Jude Bellingham', 'SOQM_LEAD', TRUE, 'Midfielder - Real Madrid', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq4', 'sq4@test.com', 'Pedri', 'SOQM_LEAD', TRUE, 'Midfielder - FC Barcelona', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq5', 'sq5@test.com', 'Rodri', 'SOQM_LEAD', TRUE, 'Midfielder - Manchester City', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po1', 'po1@test.com', 'Virgil van Dijk', 'PROCESS_OWNER', TRUE, 'Defender - Liverpool', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po2', 'po2@test.com', 'Sergio Ramos', 'PROCESS_OWNER', TRUE, 'Defender - Sevilla', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po3', 'po3@test.com', 'Manuel Neuer', 'PROCESS_OWNER', TRUE, 'Goalkeeper - Bayern Munich', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po4', 'po4@test.com', 'Thibaut Courtois', 'PROCESS_OWNER', TRUE, 'Goalkeeper - Real Madrid', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po5', 'po5@test.com', 'Joshua Kimmich', 'PROCESS_OWNER', TRUE, 'Midfielder - Bayern Munich', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('soqm1', 'soqm1@qtracker.local', 'SoQM Lead 1', 'SOQM_LEAD', TRUE, 'SoQM Lead', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('soqm2', 'soqm2@qtracker.local', 'SoQM Lead 2', 'SOQM_LEAD', TRUE, 'SoQM Lead', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq')
)
INSERT INTO users (username, mail, displayname, role, enabled, title, password)
SELECT username, mail, displayname, role, enabled, title, password_hash
FROM seed_users
ON CONFLICT DO NOTHING;

WITH seed_hashes(username, password_hash) AS (
    VALUES
        ('fac1', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac2', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac3', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac4', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('fac5', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op1', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op2', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op3', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op4', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('op5', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq1', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq2', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq3', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq4', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('sq5', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po1', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po2', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po3', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po4', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('po5', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('soqm1', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq'),
        ('soqm2', '$2a$10$8t3YROCKmg/49mCQDFZjj.HcSiKuOeGEe2ulLBYX6tNivYACXAThq')
)
UPDATE users u
SET password = s.password_hash
FROM seed_hashes s
WHERE u.username = s.username
  AND (u.password IS NULL OR u.password !~ '^[$]2[aby][$]');
