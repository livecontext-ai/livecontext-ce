-- Per-participant "delete conversation" on DM threads: soft, one-sided hide flags.
-- NULL = visible. Hiding never destroys messages and never affects the other
-- participant; any new activity (open / message) clears the flags so the thread
-- resurfaces. lo/hi mirror dm_threads.participant_lo / participant_hi.
ALTER TABLE conversation.dm_threads
    ADD COLUMN IF NOT EXISTS lo_hidden_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS hi_hidden_at TIMESTAMP NULL;
