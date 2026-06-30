-- Agent budget hierarchy (rev 8 plan - the project docs).
--
-- Cascading caller-chain reservation pattern: every ancestor of a spawning agent
-- atomically reserves credits up-front, then settles against its actual at termination.
-- This closes the "parent budget bypassed via sub-agent spawns" bug where a parent
-- with budget=100 could spawn 6 children at budget=100 each (600 credits effective).
--
-- New column: agent.agents.credits_reserved - in-flight reservation held by in-progress
-- descendants. free(A) = credit_budget(A) - credits_consumed(A) - credits_reserved(A).
--
-- Precision widening to NUMERIC(19,4): matches the entity precision hints at §4.1.
-- On PostgreSQL 14+ this is typically metadata-only for `numeric` typmod changes,
-- but is NOT contractually guaranteed to skip a table rewrite - see plan §3
-- "Lock impact" for the two-phase fallback (split migration) if agent.agents turns
-- out to be large on the target environment.

ALTER TABLE agent.agents
    ALTER COLUMN credit_budget    TYPE NUMERIC(19,4),
    ALTER COLUMN credits_consumed TYPE NUMERIC(19,4);

ALTER TABLE agent.agents
    ADD COLUMN credits_reserved NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE agent.agents
    ADD CONSTRAINT agents_credits_non_negative
        CHECK (credits_consumed >= 0 AND credits_reserved >= 0);
