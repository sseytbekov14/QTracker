-- KDN users (staff of other countries) only view their KDN controls: PARTICIPANT / KDN becomes
-- READ_ONLY / KDN, and from now on scope KDN goes only with READ_ONLY (AccessPolicy.levelScopeRefusal).
-- Their assignments stay: a KDN user may still be the Facilitator, Control Operator or Process Owner of a
-- KDN control, to see it and get its notifications; SoQM performs those steps.
--
-- The users changed here are kept in user_access_v8_backup, so db/rollback/V8__undo_KDN_users_read_only.sql
-- can give them their old level back.

CREATE TABLE IF NOT EXISTS user_access_v8_backup (
    user_id      BIGINT      NOT NULL PRIMARY KEY,
    access_level VARCHAR(20) NOT NULL,
    access_scope VARCHAR(20) NOT NULL,
    migrated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user_access_v8_backup (user_id, access_level, access_scope)
SELECT id, access_level, access_scope
FROM users
WHERE access_scope = 'KDN'
  AND access_level <> 'READ_ONLY';

UPDATE users
SET access_level = 'READ_ONLY'
WHERE access_scope = 'KDN'
  AND access_level <> 'READ_ONLY';

ALTER TABLE users ADD CONSTRAINT users_kdn_read_only_check
    CHECK (access_scope <> 'KDN' OR access_level = 'READ_ONLY');
