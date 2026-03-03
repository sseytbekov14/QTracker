DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users LIMIT 1) THEN

        INSERT INTO users (username, mail, displayname, role, enabled, title, password)
        VALUES

        -- FACILITATORS
        ('fac1', 'fac1@test.com', 'Lionel Messi', 'FACILITATOR', TRUE, 'Forward - Inter Miami', 'aaa'),
        ('fac2', 'fac2@test.com', 'Cristiano Ronaldo', 'FACILITATOR', TRUE, 'Forward - Al Nassr', 'aaa'),
        ('fac3', 'fac3@test.com', 'Kylian Mbappe', 'FACILITATOR', TRUE, 'Forward - Real Madrid', 'aaa'),
        ('fac4', 'fac4@test.com', 'Kevin De Bruyne', 'FACILITATOR', TRUE, 'Midfielder - Manchester City', 'aaa'),
        ('fac5', 'fac5@test.com', 'Mohamed Salah', 'FACILITATOR', TRUE, 'Forward - Liverpool', 'aaa'),

        -- CONTROL OPERATORS
        ('op1', 'op1@test.com', 'Robert Lewandowski', 'CONTROL_OPERATOR', TRUE, 'Forward - FC Barcelona', 'aaa'),
        ('op2', 'op2@test.com', 'Erling Haaland', 'CONTROL_OPERATOR', TRUE, 'Forward - Manchester City', 'aaa'),
        ('op3', 'op3@test.com', 'Luka Modric', 'CONTROL_OPERATOR', TRUE, 'Midfielder - Real Madrid', 'aaa'),
        ('op4', 'op4@test.com', 'Harry Kane', 'CONTROL_OPERATOR', TRUE, 'Forward - Bayern Munich', 'aaa'),
        ('op5', 'op5@test.com', 'Karim Benzema', 'CONTROL_OPERATOR', TRUE, 'Forward - Al Ittihad', 'aaa'),

        -- SOQM LEADS
        ('sq1', 'sq1@test.com', 'Neymar Jr', 'SOQM_LEAD', TRUE, 'Forward - Al Hilal', 'aaa'),
        ('sq2', 'sq2@test.com', 'Vinicius Jr', 'SOQM_LEAD', TRUE, 'Forward - Real Madrid', 'aaa'),
        ('sq3', 'sq3@test.com', 'Jude Bellingham', 'SOQM_LEAD', TRUE, 'Midfielder - Real Madrid', 'aaa'),
        ('sq4', 'sq4@test.com', 'Pedri', 'SOQM_LEAD', TRUE, 'Midfielder - FC Barcelona', 'aaa'),
        ('sq5', 'sq5@test.com', 'Rodri', 'SOQM_LEAD', TRUE, 'Midfielder - Manchester City', 'aaa'),

        -- PROCESS OWNERS
        ('po1', 'po1@test.com', 'Virgil van Dijk', 'PROCESS_OWNER', TRUE, 'Defender - Liverpool', 'aaa'),
        ('po2', 'po2@test.com', 'Sergio Ramos', 'PROCESS_OWNER', TRUE, 'Defender - Sevilla', 'aaa'),
        ('po3', 'po3@test.com', 'Manuel Neuer', 'PROCESS_OWNER', TRUE, 'Goalkeeper - Bayern Munich', 'aaa'),
        ('po4', 'po4@test.com', 'Thibaut Courtois', 'PROCESS_OWNER', TRUE, 'Goalkeeper - Real Madrid', 'aaa'),
        ('po5', 'po5@test.com', 'Joshua Kimmich', 'PROCESS_OWNER', TRUE, 'Midfielder - Bayern Munich', 'aaa');

    END IF;
END $$;
