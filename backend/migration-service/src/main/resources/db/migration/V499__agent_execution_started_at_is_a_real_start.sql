-- An agent execution's started_at was persist time, so it always landed AFTER ended_at.
--
-- agent.agent_executions is written ONCE, at the end of the execution. The writer set
-- ended_at = now() and left started_at to the entity's @PrePersist default, which
-- stamped now() again a few microseconds later. The pair was therefore inverted on
-- EVERY row the table has ever held: 7,953 of 7,953 in production on 2026-09-17, across
-- all four sources (WORKFLOW, CHAT, SCHEDULE, SUB_AGENT), with a span of about -3 ms on
-- turns that really took seconds to minutes. duration_ms was correct throughout, which
-- is why nothing looked broken: the UI reads duration_ms, and only a reader that
-- subtracts the two columns, or that trusts started_at as a real instant, sees it.
--
-- The forward fix derives started_at = ended_at - duration_ms at write time
-- (AgentObservabilityService.startedAtFrom), and the @PrePersist default now falls back
-- to ended_at rather than now(). This migration applies the same derivation to the rows
-- already in the table so history and new writes agree. Note what that does NOT claim:
-- ended_at is still the instant agent-service RECEIVED the record, so both absolute
-- instants carry the transport hop. What becomes exact is the SPAN between them.
--
-- Scope of the UPDATE, deliberately narrow: only rows whose start sits LATER than the
-- duration says it should. A row already stamped at its true start (what a future writer
-- that creates the row while the execution runs would produce) is left alone, and so is
-- a row with no ended_at - there is nothing to derive from, and inventing one would be
-- worse than a null. Re-running the statement updates zero rows.
--
-- WHY THE DURATION IS BOUNDED BEFORE IT BECOMES AN INTERVAL. duration_ms is BIGINT fed
-- from a Java long, so a corrupt measurement is representable, and Postgres does not
-- fail softly on one: `bigint * INTERVAL '1 millisecond'` raises `interval out of range`
-- past ~9.2e15, and the subtraction raises `timestamp out of range` past ~2.4e14. Either
-- one ABORTS this migration, and migration-service is the only Flyway runner for every
-- schema - so a single absurd row would stop the whole deploy, with auth-service on
-- ddl-auto: validate refusing to boot behind it (the failure mode V491's test already
-- documents). The subquery therefore clamps to 0 with an integer comparison BEFORE any
-- interval is built, which is the same guard, in the same order, as
-- AgentObservabilityService.startedAtFrom: a duration that would walk back past 1970 is
-- a corrupt measurement, not a long run, and collapses to a zero-length window.
--
-- NO CHECK CONSTRAINT HERE, ON PURPOSE. `CHECK (started_at <= ended_at)` is the obvious
-- way to make a recurrence loud, and it would be wrong to add in THIS migration: even
-- NOT VALID, a CHECK is enforced on new rows immediately, and migrations run BEFORE the
-- app pods roll. Every still-running old pod would have its observability write rejected
-- for the length of the rollout - trading a cosmetic column for lost execution history.
-- It belongs in a later release, once every writer in the fleet derives the start. The
-- same reason leaves a bounded tail of inverted rows written during that rollout window,
-- and NOTHING IN THIS RELEASE SWEEPS THEM. Flyway never replays an applied version, this
-- is not a repeatable migration, and no endpoint runs it - so those rows keep an inverted
-- pair for good. That is accepted here rather than hidden: the cohort is the few minutes
-- between the migration and the last pod rolling, on a column no product surface reads
-- directly. Sweeping it means a later versioned migration re-running this exact statement,
-- which is why the statement is written to be idempotent (a replay updates zero rows) and
-- why its test asserts that property against a row inserted after it first ran.
--
-- One more consequence of that same ordering, cosmetic and self-healing, stated so nobody
-- has to rediscover it from a dashboard: during the rollout, old pods still run the old
-- metrics SQL (MAX(started_at), DATE(started_at)) against rows this statement has already
-- turned into real starts. For those few minutes "Last run" reads early by each run's
-- duration and a midnight-crossing run sits a day back. New pods read ended_at
-- (AgentMetricsQueryService.OCCURRED_AT) and the numbers return on their own.

SET search_path TO agent;

UPDATE agent.agent_executions AS e
SET started_at = e.ended_at - (b.safe_duration_ms * INTERVAL '1 millisecond')
FROM (
    SELECT id,
           CASE
               -- Both bounds are plain integer comparisons: no interval is constructed
               -- from an unvetted duration, so neither overflow above can be reached.
               WHEN COALESCE(duration_ms, 0) > 0
                    AND duration_ms <= FLOOR(EXTRACT(EPOCH FROM ended_at) * 1000)
               THEN duration_ms
               ELSE 0
           END AS safe_duration_ms
    FROM agent.agent_executions
    WHERE ended_at IS NOT NULL
) AS b
WHERE e.id = b.id
  AND e.started_at > e.ended_at - (b.safe_duration_ms * INTERVAL '1 millisecond');
