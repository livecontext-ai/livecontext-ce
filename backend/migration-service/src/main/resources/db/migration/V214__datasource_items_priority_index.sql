-- V214: Covering index for the ORDER BY priority DESC, id DESC scan in
-- CrudRepository.readRows.
--
-- Origin: scalability audit 2026-05-13 flagged that the read path's ORDER
-- BY (priority DESC, id DESC) had no supporting index. Postgres planner
-- falls back to an in-memory Sort over every row matching the WHERE
-- (data_source_id + tenant_id + optional jsonb filter). On a datasource
-- with thousands of rows this triggers a temp-file spill (work_mem=16MB
-- default) and adds 5-10× latency on the count-style queries that drive
-- dashboards (e.g. the Daily Email Digest stats panel firing 7 parallel
-- crud-find / Count <Label> nodes per cron tick).
--
-- Shape: (data_source_id, priority DESC, id DESC). Composite B-tree -
-- planner uses the data_source_id prefix for the equality filter, then
-- the trailing (priority DESC, id DESC) covers the sort. tenant_id is
-- NOT in the index - the equality on data_source_id alone is selective
-- enough (a given datasource belongs to exactly one tenant; the tenant
-- check in the query stays as a defense-in-depth row filter).
--
-- Idempotent: CREATE INDEX IF NOT EXISTS. Built CONCURRENTLY so the
-- write path on data_source_items is not blocked during the build -
-- requires migration-service to run with `spring.flyway.mixed=true` so
-- the non-transactional CREATE INDEX CONCURRENTLY survives the Flyway
-- parser. Enabled prod-wide on 2026-05-13 (d1cf8c7d6).
--
-- Size estimate: data_source_items table is 920 KB / 605 rows in prod
-- today; index is ~30-50 KB. Negligible storage cost.

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dsi_ds_priority_id
    ON datasource.data_source_items (data_source_id, priority DESC, id DESC);
