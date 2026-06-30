-- PR12 round-2 governance fix - persist the GUC that V196 fail-fasts on.
--
-- Context: during the 2026-05-12 prod deploy, lc-migration crashloop'd on V196
-- because `lc.migration.source_timezone` was unset at the DB level. The fix
-- was applied LIVE via `ALTER DATABASE apimarketplace SET ... TO 'UTC'`
-- against db-host WITHOUT a committed Flyway migration tracking it. That
-- drift is a real DR risk: if the database is restored from a backup taken
-- before that ALTER ran, V196 fail-fasts again.
--
-- This migration persists the GUC so:
--   1. DR restore + re-apply migrations → V203 re-asserts the GUC (so any
--      future V196-style migration sees it without manual intervention)
--   2. The change is recorded in flyway_schema_history (the prior live ALTER
--      bypassed this contract)
--
-- The ALTER is idempotent - running it on a DB where the GUC is already UTC
-- is a no-op. Safe to apply multiple times.
--
-- ⚠️ V203 does NOT self-heal a fresh setup. Since Flyway runs migrations in
-- version order and V196 < V203, on a brand-new DB V196 fires FIRST and
-- crashes BEFORE V203 ever executes. V203 is forward-protection for:
--   (a) prod + staging + local where V196 has already passed (live or
--       DR-recovered) - V203 ensures the GUC survives backup/restore cycles
--   (b) future migrations that will reuse the same GUC dependency
-- For a fresh DB stuck pre-V196, the operator MUST set the GUC manually
-- (`ALTER DATABASE <name> SET lc.migration.source_timezone TO 'UTC'`) before
-- starting migration-service. Flyway won't re-attempt V196 if it previously
-- failed without `flyway repair`.
--
-- This is the governance anti-pattern PR13 closed - see plan.md §11.ter
-- "V196 anti-pattern removal". PR13's actual implementation (shipped
-- 2026-05-12) is a 3-layer defense in depth, NOT the original-draft
-- option-1 COALESCE-in-V196 path (which was rejected for losing V196's
-- operator-contract safety check). The 3 layers:
--   Layer A - `deploy-direct.sh` writes `?options=-c%20lc.migration.source_timezone%3DUTC`
--             to the migration-service `SPRING_DATASOURCE_URL` env var
--             (libpq startup option, server-side at session start).
--   Layer B - `application.yml` Hikari `connection-init-sql: "SET … = 'UTC'"`
--             (every pooled connection self-sets at acquire, incl. pre-warmed).
--   Layer C - `MigrationServiceApplication.ensureMigrationSourceTimezoneGuc`
--             runs `ALTER DATABASE … SET … TO 'UTC'` before flyway.migrate()
--             (DB-level, survives DR-restore).
-- V203 is now redundant (any of A/B/C alone is sufficient for V196 to see
-- the GUC) but stays applied for backwards compatibility - never removed.
--
-- ⚠️ Privilege requirement: `ALTER DATABASE ... SET ...` requires DB
-- ownership (not superuser, but ownership). The Flyway role MUST own the
-- target DB or the EXECUTE below raises `must be owner of database <name>`
-- and the migration fails. Production deploys today use the `postgres` role
-- (db owner) so this is a no-op constraint. Local/staging envs that use a
-- restricted app role (e.g. `apimarketplace_app`) must either: grant
-- ownership to the Flyway role, OR pre-set the GUC out-of-band as a
-- superuser before running migrations. The EXCEPTION block below catches
-- the failure with a clear NOTICE so the failure mode is diagnosable.

-- Round-2 polish: use current_database() via dynamic SQL instead of
-- hard-coding 'apimarketplace'. The DB name is the same on all environments
-- today (prod/staging/local), but using current_database() removes the
-- silent-break risk if staging/DR-test uses a different name. ALTER DATABASE
-- cannot accept parameter binding for the database identifier, so we go
-- through format() + EXECUTE. %I is identifier-quoting (injection-safe).
--
-- Round-3 polish: wrap in EXCEPTION block for the insufficient_privilege
-- case (Flyway role is not DB owner). Without this, V203 just substitutes
-- one V196-style fail-fast with another - exactly the anti-pattern PR13
-- exists to retire.
--
-- Round-4 polish: broaden EXCEPTION to WHEN OTHERS so V203 never crashes
-- Flyway regardless of which failure mode occurs (disk_full,
-- read_only_transaction, undefined_database, etc.). The NOTICE captures
-- SQLSTATE + SQLERRM so the failure is diagnosable from migration-service
-- logs without losing forensic trail. V203 is a "best-effort forward
-- protection" migration - never a blocker for the rest of the migration
-- chain. See plan.md §11.ter PR13 (3-layer defense in depth, shipped 2026-05-12)
-- for the canonical fix that supersedes this V203 forward-protection.
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET lc.migration.source_timezone TO ''UTC''',
                   current_database());
    RAISE NOTICE 'V203: lc.migration.source_timezone set on database %', current_database();
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'V203 SKIPPED (insufficient_privilege): Flyway role lacks ownership of database %. '
                     'On already-applied envs (V196 success=t in flyway_schema_history) this is harmless. '
                     'On fresh setups, PR13''s Layer A (deploy-direct.sh URL options=) and Layer B '
                     '(Hikari connection-init-sql) cover this gap - they set the GUC at session level '
                     'and do not require DB ownership.',
                     current_database();
    WHEN OTHERS THEN
        RAISE NOTICE 'V203 SKIPPED (sqlstate=%, message=%): could not set lc.migration.source_timezone on %. '
                     'V203 is forward-protection only - failure here does not break the migration chain. '
                     'PR13''s Layer A (deploy-direct.sh URL options=) and Layer B (Hikari connection-init-sql) '
                     'cover the same gap at session level; see plan.md §11.ter for the full 3-layer design.',
                     SQLSTATE, SQLERRM, current_database();
END $$;

-- Verification - read the GUC from pg_db_role_setting which reflects the
-- ALTER DATABASE state immediately (vs. current_setting which only sees the
-- new value in fresh sessions). This actually proves the ALTER landed
-- within the same migration, instead of always logging '<unset>'.
--
-- Round-4 polish: previous query used `SELECT unnest(setconfig) INTO …`
-- which produces N rows (one per setconfig element) and SELECT INTO keeps
-- only the FIRST - if setconfig had `TimeZone=UTC` listed before our GUC,
-- the variable would silently hold the wrong value. Fixed by lifting
-- unnest into a LATERAL join and filtering at element level. Added
-- `setrole = 0` so we read the database-level setting (not a role-scoped
-- override), and `LIMIT 1` for defensive determinism.
DO $$
DECLARE
    persisted_setting TEXT;
BEGIN
    SELECT regexp_replace(s, '^lc\.migration\.source_timezone=', '')
      INTO persisted_setting
      FROM pg_db_role_setting
      JOIN pg_database ON pg_db_role_setting.setdatabase = pg_database.oid
      CROSS JOIN LATERAL unnest(setconfig) AS s
     WHERE pg_database.datname = current_database()
       AND pg_db_role_setting.setrole = 0
       AND s LIKE 'lc.migration.source_timezone=%'
     LIMIT 1;

    IF persisted_setting IS NULL THEN
        RAISE NOTICE 'V203 verification: GUC NOT persisted on % (ALTER likely was skipped - check NOTICE above for sqlstate/error)',
                     current_database();
    ELSE
        RAISE NOTICE 'V203 verification: GUC persisted on % = %', current_database(), persisted_setting;
    END IF;
END $$;
