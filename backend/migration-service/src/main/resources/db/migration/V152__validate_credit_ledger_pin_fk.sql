-- V152: VALIDATE the fk_cl_pin foreign key added NOT VALID in V148.
--
-- WARNING: Estimated 3-8 minutes on a 50M-row credit_ledger.
-- Run during a low-traffic deploy window. Pre-warm with `ANALYZE auth.credit_ledger`
-- via OPERATOR_RUNBOOK §v152-deploy.
--
-- Lock: SHARE UPDATE EXCLUSIVE - concurrent reads/writes proceed; DDL/VACUUM blocks.
--
-- Why deferred from V148: the FK validation is a full sequential scan of credit_ledger
-- to confirm every existing pin_id (all NULL on first deploy) satisfies the FK.
-- Adding it NOT VALID in V148 made V148 instant. Validating here lets ops choose
-- a calm window for the scan.
--
-- On failure (e.g. an orphan pin_id slipped in): drop the constraint, fix the
-- orphan rows, re-add NOT VALID, re-validate. Document fixes in the deploy ticket.
--
-- Rollback: ALTER TABLE auth.credit_ledger DROP CONSTRAINT fk_cl_pin;
--           ALTER TABLE auth.credit_ledger ADD CONSTRAINT fk_cl_pin
--             FOREIGN KEY (pin_id) REFERENCES auth.workflow_run_pricing_pin(id)
--             ON DELETE SET NULL NOT VALID;
--   (i.e., revert to V148's NOT VALID state)

ALTER TABLE auth.credit_ledger
    VALIDATE CONSTRAINT fk_cl_pin;
