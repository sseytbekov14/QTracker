-- The earlier free text of "Other Related Controls" is removed from QTracker (2026-10-16): the field holds only the
-- links of control_related_controls (V15), and the text was already gone from View Control, its API and the Excel.
-- Earlier Changelog entries that name "Other Related Controls (earlier text)" stay as they were (the audit log and
-- the history are not rewritten). The links are not in this column and are not touched.
-- Undo by hand: db/rollback/V16__undo_drop_other_related_controls_text.sql (an empty column; the texts are gone).

ALTER TABLE controls DROP COLUMN IF EXISTS other_related_controls;
