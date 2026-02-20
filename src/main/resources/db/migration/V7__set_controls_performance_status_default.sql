UPDATE controls
SET performance_status = 'DRAFT'
WHERE performance_status IS NULL OR performance_status = '';

ALTER TABLE controls
    ALTER COLUMN performance_status SET DEFAULT 'DRAFT';

ALTER TABLE controls
    ALTER COLUMN performance_status SET NOT NULL;
