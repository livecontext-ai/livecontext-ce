-- V303: allow decision = 'REPLACED_UPLOAD' on publication.image_screening_decisions.
--
-- The pre-publish image-screening modal now lets a publisher REPLACE a flagged
-- image/video by UPLOADING their own file (alongside "Replace with AI"), which
-- is the only working replacement path in environments without a Stability AI
-- credential. An uploaded replacement is logged with decision 'REPLACED_UPLOAD'
-- (distinct from REPLACED_AI / REPLACED_STOCK for the safe-harbor audit trail).
--
-- V274 created the column with
--   CHECK (decision IN ('REPLACED_STOCK','REPLACED_AI','KEPT_ATTESTED','KEPT_OWN_UPLOAD','SKIPPED')).
-- Widen it. Idempotent: drop the existing constraint then (re)create the widened one.

ALTER TABLE publication.image_screening_decisions
    DROP CONSTRAINT IF EXISTS image_screening_decisions_decision_check;

ALTER TABLE publication.image_screening_decisions
    ADD CONSTRAINT image_screening_decisions_decision_check
    CHECK (decision IN ('REPLACED_STOCK','REPLACED_AI','REPLACED_UPLOAD','KEPT_ATTESTED','KEPT_OWN_UPLOAD','SKIPPED'));
