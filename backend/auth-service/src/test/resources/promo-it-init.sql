-- Schemas the auth-service entities map into (mirrors the prod search_path).
-- Created before Hibernate ddl-auto so default_schema=auth resolves.
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS storage;

-- ShedLock table (created by Flyway in prod, not a JPA entity so ddl-auto skips it).
-- Present here only to keep scheduled-task locking quiet during the test.
CREATE TABLE IF NOT EXISTS auth.shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
