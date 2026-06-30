-- Init script run inside the Postgres testcontainer BEFORE Flyway.
-- Creates the schemas Flyway expects to find (Flyway only runs the migrations,
-- it does not necessarily create the namespaces unless `createSchemas` is set -
-- and even then, the order of creation must match what each migration expects).
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS orchestrator;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS storage;
CREATE SCHEMA IF NOT EXISTS conversation;
CREATE SCHEMA IF NOT EXISTS agent;
CREATE SCHEMA IF NOT EXISTS datasource;
CREATE SCHEMA IF NOT EXISTS trigger;
CREATE SCHEMA IF NOT EXISTS interface;
CREATE SCHEMA IF NOT EXISTS publication;
