-- Undo of V27__Drop_unused_tables.sql, run by hand (Flyway does not undo): the five tables come back as V1, V8, V11,
-- V15 and V22 made them, EMPTY. Their rows are only in a database backup taken before V27 (pg_restore of the tables
-- needed). Needed only before running the undo script of V8, V11, V15, V18 or V22, or a version older than V27 that
-- would read one of them (none does).
-- Afterwards also: DELETE FROM flyway_schema_history WHERE version = '27';

CREATE TABLE IF NOT EXISTS user_access_v8_backup (
    user_id      BIGINT      NOT NULL PRIMARY KEY,
    access_level VARCHAR(20) NOT NULL,
    access_scope VARCHAR(20) NOT NULL,
    migrated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_admin_access_v11_backup (
    user_id      BIGINT      NOT NULL PRIMARY KEY,
    admin_access BOOLEAN     NOT NULL,
    access_level VARCHAR(20) NOT NULL,
    access_scope VARCHAR(20) NOT NULL,
    migrated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS users_role_backup (
    user_id        BIGINT       NOT NULL PRIMARY KEY,
    role           VARCHAR(255),
    secondary_role VARCHAR(255),
    migrated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS control_notification_log (
    id BIGSERIAL PRIMARY KEY,
    control_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    notification_code VARCHAR(255) NOT NULL,
    scheduled_date DATE NOT NULL,
    CONSTRAINT ukmws2eb14dw35e6vsimhx1dvna UNIQUE (control_id, notification_code, scheduled_date)
);

CREATE TABLE IF NOT EXISTS control_related_controls (
    id BIGSERIAL PRIMARY KEY,
    control_id BIGINT NOT NULL,
    related_control_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT fk_control_related_controls_control
        FOREIGN KEY (control_id) REFERENCES controls (id) ON DELETE CASCADE,
    CONSTRAINT fk_control_related_controls_related
        FOREIGN KEY (related_control_id) REFERENCES controls (id) ON DELETE CASCADE,
    CONSTRAINT uq_control_related_controls UNIQUE (control_id, related_control_id),
    CONSTRAINT ck_control_related_controls_not_itself CHECK (control_id <> related_control_id)
);

CREATE INDEX IF NOT EXISTS idx_control_related_controls_related ON control_related_controls (related_control_id);
