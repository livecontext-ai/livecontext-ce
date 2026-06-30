-- Enable pgvector extension for vector similarity search in datasource tables.
-- The Docker image pgvector/pgvector:pg17 includes the extension binary;
-- this migration activates it at the database level.
-- Install in 'datasource' schema since only datasource-service uses vectors.
CREATE EXTENSION IF NOT EXISTS vector SCHEMA datasource;
