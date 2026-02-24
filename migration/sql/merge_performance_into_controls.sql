-- ============================================================
-- Migration: Merge performance table into controls
-- Date: 2025-01-xx
-- Description: 
--   1. Add soqm_year column to controls table
--   2. Copy soqm_year data from performance to controls
--   3. Drop performance table
-- ============================================================

-- Step 1: Add soqm_year column to controls
ALTER TABLE controls ADD COLUMN IF NOT EXISTS soqm_year VARCHAR(255);

-- Step 2: Copy soqm_year from performance to controls
UPDATE controls c
SET soqm_year = p.soqm_year
FROM performance p
WHERE p.control_id = c.id
  AND p.soqm_year IS NOT NULL;

-- Step 3: Verify migration (run manually to check)
-- SELECT c.id, c.soqm_year, p.soqm_year
-- FROM controls c
-- LEFT JOIN performance p ON p.control_id = c.id
-- WHERE c.soqm_year IS DISTINCT FROM p.soqm_year;

-- Step 4: Drop performance table
DROP TABLE IF EXISTS performance;
