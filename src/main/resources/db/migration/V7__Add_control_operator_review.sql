-- The Control Operator's own field next to Control Steps Performed and Results, used when the Facilitator
-- and the Control Operator are different people. Same type and length as control_steps_performed.
-- Existing rows are not touched: their single text stays the Facilitator's, the new field is empty (NULL).

ALTER TABLE controls ADD COLUMN IF NOT EXISTS control_operator_review VARCHAR(2000);
