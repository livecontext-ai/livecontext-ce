-- Whose API key an LLM turn ran on, recorded where the money and the metrics are.
--
-- A user can run agents on their OWN provider key (a saved llm_<provider> credential).
-- Until now the ledger could not tell such a turn from a platform-key turn, so it billed
-- both at the token rate: the user paid their provider AND full platform credits. The
-- route is now decided once per execution (KeyRoute PLATFORM / OWN_KEY) and travels to the
-- debit, which charges an own-key turn a flat fee per model tier instead.
--
-- credit_ledger.key_route         which route the row was billed under (NULL = platform,
--                                 the historical meaning of every existing row).
-- credit_ledger.provider_cost_credits
--                                 for an own-key row: the tokens priced at the provider's
--                                 LIST rate, in credits (1 credit = $0.001), i.e. what the
--                                 user's provider will bill them for this turn. An ESTIMATE
--                                 shown next to the fee, never added to any balance.
-- agent_executions.key_route      same route on the execution row, so the fleet and
--                                 execution views can split the two routes.

ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS key_route VARCHAR(16),
    ADD COLUMN IF NOT EXISTS provider_cost_credits NUMERIC(15, 4);

COMMENT ON COLUMN auth.credit_ledger.key_route IS
    'Key route the row was billed under: OWN_KEY (tenant''s own provider key, flat fee per turn) or NULL/PLATFORM (platform key, token rate).';
COMMENT ON COLUMN auth.credit_ledger.provider_cost_credits IS
    'Own-key rows only: tokens at the provider''s list rate, in credits (1 credit = $0.001). The estimate of what the user''s provider bills; never part of a balance.';

ALTER TABLE agent.agent_executions
    ADD COLUMN IF NOT EXISTS key_route VARCHAR(16);

COMMENT ON COLUMN agent.agent_executions.key_route IS
    'Key route this execution ran on: PLATFORM or OWN_KEY. NULL = unpinned (pre-route rows).';

-- A consumption that could not be debited is replayed later from the dead-letter table;
-- the replay must bill it under the same route, or an own-key turn ends up at the token rate.
ALTER TABLE auth.credit_consumption_dead_letter
    ADD COLUMN IF NOT EXISTS key_route VARCHAR(16);

COMMENT ON COLUMN auth.credit_consumption_dead_letter.key_route IS
    'Key route the failed consumption was meant to be billed under (OWN_KEY / PLATFORM / NULL); replayed as such.';
