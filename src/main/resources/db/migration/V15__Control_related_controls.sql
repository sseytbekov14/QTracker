-- Other Related Controls on the Details tab (business decision of 2026-10-15): SoQM Admin Team choose other controls
-- of QTracker, each one a link to that control. One row per link, in the order they were chosen. The links follow the
-- controls, not their Control IDs, so a Rename ID keeps them. A removed control takes its links with it.
-- The earlier free text in controls.other_related_controls stays as it was ("earlier text" on View Control).

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
