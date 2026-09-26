-- ============================================================================
-- V520: one ask_user call is handed over exactly once.
--
-- The rows of one call are answered independently, and the last one answered
-- triggers the hand-over. "Last" is decided by reading the group, and two
-- answers arriving within the same second both read it after the other has
-- committed: both see every row RESOLVED, and both hand the same answers over.
--
-- What that costs is not a duplicate log line. The hand-over starts the agent's
-- follow-up turn, so the second one starts a SECOND turn on the same
-- conversation while the first is still running: two LLM runs billed, two
-- replies, and an agent reading its own answer twice.
--
-- The claim is this column. The first writer to set it wins by matching rows;
-- the second matches none and does nothing, which is the same single-statement
-- shape the per-row claim already uses.
-- ============================================================================

ALTER TABLE orchestrator.chat_authorization_requests
    ADD COLUMN IF NOT EXISTS applied_at TIMESTAMPTZ;

-- V519 described group_key as "the ask_user tool call id". It is not, any more: the tool
-- call id is minted by the provider and repeats (Gemini numbers calls call_0, call_1), so
-- grouping on it let one night's answers join the next night's group and could pull another
-- tenant's row in. It is minted per delivery. The old comment would send the next reader to
-- group by a value that collides, so it is replaced here rather than left to mislead.
COMMENT ON COLUMN orchestrator.chat_authorization_requests.group_key IS
    'One id per delivery of an ask_user call, minted server-side; the questions of that '
    'delivery share it and answer together. NOT the provider tool call id, which repeats. V520.';

COMMENT ON COLUMN orchestrator.chat_authorization_requests.applied_at IS
    'When this call''s answers were handed to the conversation. The claim that '
    'makes the hand-over happen exactly once per group. V520.';
