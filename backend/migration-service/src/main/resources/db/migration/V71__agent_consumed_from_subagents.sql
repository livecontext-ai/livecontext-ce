-- V71: Expose sub-agent cascade in the budget API.
--
-- Renumbered from V67 after merging scaling/horizontal-scaling, which had
-- already consumed V67 for V67__create_agent_tasks.sql (applied to the shared
-- dev DB at schema version 70). Flyway refused to start with two V67 files
-- ("Found more than one migration with version 67"), so this migration moved
-- to the next free slot past V70.
--
-- Adds credits_consumed_from_subagents as an observable breakdown of
-- credits_consumed. Every time BudgetReservationService.settleReservationChain
-- charges an ancestor for a descendant's actual spend, it also increments this
-- column by the same amount. Callers can then split:
--
--   consumed_total = credits_consumed
--   consumed_from_subagents = credits_consumed_from_subagents
--   consumed_own = credits_consumed - credits_consumed_from_subagents
--
-- Rationale: the existing budget response shows `reserved_for_subagents` which is
-- a transient in-flight counter (zero at rest by design - refund + debit is one
-- transaction in settleReservationChain). Two external sessions in a row have
-- misread this as "cascade does not work" because the aggregate `consumed` hides
-- which fraction came from the cascade vs the agent's own LLM spend. Making the
-- split visible in the API eliminates the need to tail backend logs to verify
-- that the cascade fired.
--
-- Invariants (enforced at the write site, not at the DB):
--   * credits_consumed_from_subagents <= credits_consumed (subset, not separate)
--   * Both columns reset together in the auto-reset and manual-reset queries
--   * Hibernate-facing @Column is updatable=false so JPA dirty flushes cannot
--     bypass the targeted SQL UPDATE used by settleReservationChain
--
-- Backfill: NOT NULL DEFAULT 0. Existing rows get 0, which is semantically
-- correct (they have no historical settle records attributing cascade spend).

ALTER TABLE agent.agents
    ADD COLUMN credits_consumed_from_subagents NUMERIC(19,4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent.agents.credits_consumed_from_subagents IS
    'Subset of credits_consumed that came from descendant sub-agent cascade settles (observability only, always <= credits_consumed, written exclusively by BudgetReservationService.settleReservationChain).';
