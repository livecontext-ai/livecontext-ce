-- Flyway SQL callback: reset schema resolution before every migration.
--
-- Some historical migrations set search_path to a service schema and do not
-- reset it. Flyway reuses connections, so the next migration can otherwise
-- resolve unqualified orchestrator tables against the wrong schema during a
-- fresh database replay.
SET search_path TO orchestrator, public;
