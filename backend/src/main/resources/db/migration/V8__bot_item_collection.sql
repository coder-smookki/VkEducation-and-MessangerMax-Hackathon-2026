-- S-05: the bot also collects items and asks about observations, so the dialog needs more states.
ALTER TABLE bot_dialog DROP CONSTRAINT bot_dialog_state_check;
ALTER TABLE bot_dialog ADD CONSTRAINT bot_dialog_state_check
    CHECK (state IN ('IDLE', 'AWAITING_CASE_TEXT', 'AWAITING_ITEMS', 'AWAITING_OBSERVATION'));
-- Collecting items without knowing which draft they belong to is not a state worth storing.
ALTER TABLE bot_dialog ADD CONSTRAINT bot_dialog_items_need_case
    CHECK (state <> 'AWAITING_ITEMS' OR case_id IS NOT NULL);
