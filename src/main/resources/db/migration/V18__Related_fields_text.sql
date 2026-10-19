-- Other Related Controls and IT Applications on the Details tab take several values (business decision of 2026-10-19),
-- kept as ValueList writes them: "A; B" (";" between values, spaces around them do not count).
-- Other Related Controls are Control IDs chosen from related_control_ref (V17), plain text, no longer links to
-- controls: controls.other_related_controls comes back as TEXT (V16 dropped the earlier free text of that name) and
-- gets the links each control had in control_related_controls (V15), as the current Control IDs of the linked
-- controls, in their order (a ";" in a Control ID becomes ","). Those IDs need not be in the list: View Control shows
-- them "not in list" and they stay until SoQM Admin Team remove them. The links themselves stay in
-- control_related_controls, no longer read.
-- IT Applications: VARCHAR(255) holds one value; TEXT holds several. The values stay as they are.
-- Undo by hand: db/rollback/V18__undo_related_fields_text.sql.

ALTER TABLE controls ADD COLUMN IF NOT EXISTS other_related_controls TEXT;

UPDATE controls SET other_related_controls = (
    SELECT STRING_AGG(REPLACE(TRIM(c.control_id), ';', ','), '; ' ORDER BY r.sort_order, r.id)
    FROM control_related_controls r
    JOIN controls c ON c.id = r.related_control_id
    WHERE r.control_id = controls.id AND c.control_id IS NOT NULL AND TRIM(c.control_id) <> ''
)
WHERE other_related_controls IS NULL
  AND EXISTS (SELECT 1 FROM control_related_controls r WHERE r.control_id = controls.id);

ALTER TABLE controls ALTER COLUMN it_applications TYPE TEXT;
