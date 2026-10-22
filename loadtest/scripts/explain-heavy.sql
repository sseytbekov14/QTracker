-- EXPLAIN (ANALYZE, BUFFERS) of the five heavy statements of the load test, on the load test database only
-- (psql -d qtracker_loadtest -f explain-heavy.sql). "Before V23" plans run inside a transaction that drops the
-- index and is rolled back.
\pset pager off
\set mail '''lt.edit.001@loadtest.local'''

\echo '== 1. A list of SoQM Admin Team / All controls: every control as an entity, newest first'
EXPLAIN (ANALYZE, BUFFERS) SELECT c.* FROM controls c LEFT JOIN users u ON u.id = c.created_by ORDER BY c.id DESC;

\echo '== 2a. My controls, before: one of five passes (facilitator)'
EXPLAIN (ANALYZE, BUFFERS) SELECT c.id FROM controls c WHERE lower(c.facilitator) LIKE lower('%' || trim(:mail) || '%');

\echo '== 2b. My controls, after: one pass, five conditions'
EXPLAIN (ANALYZE, BUFFERS) SELECT c.id FROM controls c
 WHERE lower(c.facilitator) LIKE lower('%' || trim(:mail) || '%') OR lower(c.control_operator) LIKE lower('%' || trim(:mail) || '%')
    OR lower(c.soqm_team) LIKE lower('%' || trim(:mail) || '%') OR lower(c.process_owner) LIKE lower('%' || trim(:mail) || '%')
    OR lower(c.control_shared_with) LIKE lower('%' || trim(:mail) || '%');

\echo '== 3. KDN users: candidates with KDN in the ID (after) - before it was statement 1'
EXPLAIN (ANALYZE, BUFFERS) SELECT c.* FROM controls c WHERE upper(c.control_id) LIKE '%KDN%' ORDER BY c.id DESC;

\echo '== 4a. Closed late, before: every control of the list in IN, no index'
BEGIN;
DROP INDEX IF EXISTS idx_workflow_history_control;
EXPLAIN (ANALYZE, BUFFERS) SELECT wh.control_id, max(wh.created_at) FROM workflow_history wh
 WHERE wh.control_id = ANY (ARRAY(SELECT id FROM controls))
   AND ((wh.to_step IS NOT NULL AND upper(trim(wh.to_step)) = 'COMPLETED')
     OR (wh.action_type = 'APPROVE' AND wh.from_step IS NOT NULL AND upper(trim(wh.from_step)) = 'PROCESS_OWNER_REVIEW'))
 GROUP BY wh.control_id;
ROLLBACK;

\echo '== 4b. Closed late, after: the completed controls with a deadline, index'
EXPLAIN (ANALYZE, BUFFERS) SELECT wh.control_id, max(wh.created_at) FROM workflow_history wh
 WHERE wh.control_id = ANY (ARRAY(SELECT id FROM controls WHERE performance_status = 'COMPLETED' AND deadline IS NOT NULL))
   AND ((wh.to_step IS NOT NULL AND upper(trim(wh.to_step)) = 'COMPLETED')
     OR (wh.action_type = 'APPROVE' AND wh.from_step IS NOT NULL AND upper(trim(wh.from_step)) = 'PROCESS_OWNER_REVIEW'))
 GROUP BY wh.control_id;

\echo '== 5a. Changelog of one control, before V23'
BEGIN;
DROP INDEX IF EXISTS idx_admin_audit_log_control;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM admin_audit_log WHERE control_id = 100 ORDER BY created_at DESC;
ROLLBACK;

\echo '== 5b. Changelog of one control, after V23'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM admin_audit_log WHERE control_id = 100 ORDER BY created_at DESC;

\echo '== 6a. History of one control, before V23'
BEGIN;
DROP INDEX IF EXISTS idx_workflow_history_control;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM workflow_history WHERE control_id = 100 ORDER BY created_at DESC;
ROLLBACK;

\echo '== 6b. History of one control, after V23'
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM workflow_history WHERE control_id = 100 ORDER BY created_at DESC;
