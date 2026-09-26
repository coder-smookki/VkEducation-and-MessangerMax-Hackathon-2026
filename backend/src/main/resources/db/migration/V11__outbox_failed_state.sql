-- S-07 / QA-R05: a notification that will never be sent says so, instead of hiding among pending ones.
-- Set by a permanent refusal (the user blocked the bot, the chat is gone) and by the last failed attempt.
ALTER TABLE outbox_event ADD COLUMN failed_at timestamptz;

-- Rows that already spent every attempt (5 at the time of writing) can never be claimed again anyway;
-- mark them now so the failed state means the same thing for old rows and new ones.
UPDATE outbox_event
   SET failed_at = now(), last_error = coalesce(last_error, 'ATTEMPTS_EXHAUSTED')
 WHERE sent_at IS NULL AND attempts >= 5
   AND (leased_until IS NULL OR leased_until <= now());
