-- V25: Add credit budget fields to agents table
SET search_path TO agent;

ALTER TABLE agents
  ADD COLUMN IF NOT EXISTS credit_budget       NUMERIC(15,4) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS credits_consumed    NUMERIC(15,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS budget_reset_mode   VARCHAR(20) DEFAULT 'cumulative',
  ADD COLUMN IF NOT EXISTS budget_last_reset   TIMESTAMP DEFAULT NULL;
