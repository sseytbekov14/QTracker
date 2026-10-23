-- The field "SoQM Development Materials" is removed from QTracker (2026-10-14): the select on the Documents tab,
-- its API, the Excel row and its column. Earlier Changelog entries that name the field stay as they were (the audit
-- log and the history are not rewritten). The Documents attachments are not in this column and are not touched.
-- Undo by hand: db/rollback/V13__undo_drop_soqm_development_materials.sql (an empty column; the values are gone).

ALTER TABLE controls DROP COLUMN IF EXISTS soqm_development_materials;
