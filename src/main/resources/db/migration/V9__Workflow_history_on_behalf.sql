-- Who a workflow move was made for (business decision 3: SoQM moves a control on or back for any role).
-- acted_as: the role whose step it was ("Facilitator", "SoQM Team", ...); on_behalf: a SoQM user made it
-- for the participant who holds that step; assigned_performer: the people assigned to that step then
-- (e-mails, comma-separated). performed_by_* stay the person who actually did it.
-- Existing rows: acted_as and assigned_performer NULL (not recorded then), on_behalf FALSE.

ALTER TABLE workflow_history ADD COLUMN IF NOT EXISTS acted_as VARCHAR(40);
ALTER TABLE workflow_history ADD COLUMN IF NOT EXISTS on_behalf BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workflow_history ADD COLUMN IF NOT EXISTS assigned_performer VARCHAR(2000);
