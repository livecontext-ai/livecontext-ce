-- Deactivate stale duplicate active model_pricing rows.
--
-- Several seed generations (e.g. the 2026-04-08 mis-scaled batch vs the 2026-04-14
-- corrected batch) left TWO active rows per (provider, model), sometimes with rates
-- 100x apart. ModelPricingService.findCurrentPricing orders by effective_from DESC
-- so the newest row wins today, but that protection is fragile: the unique key is
-- (provider, model, effective_from) and two rows sharing the same effective_from
-- would make the pick non-deterministic.
--
-- Invariant after this migration: at most ONE active row per (provider, model).
-- The newest row (effective_from, then id as tie-break) stays active; older
-- duplicates get is_active=false + effective_to stamped, preserving the
-- append-only audit history introduced in V111.

UPDATE auth.model_pricing mp
SET is_active = false,
    -- Close the audit window at the date the superseding row took effect, not today.
    effective_to = COALESCE(mp.effective_to,
        (SELECT MIN(newer.effective_from)
         FROM auth.model_pricing newer
         WHERE newer.provider = mp.provider
           AND newer.model = mp.model
           AND newer.is_active = true
           AND newer.effective_from >= mp.effective_from
           AND newer.id <> mp.id),
        CURRENT_DATE)
WHERE mp.is_active = true
  AND EXISTS (
      SELECT 1
      FROM auth.model_pricing newer
      WHERE newer.provider = mp.provider
        AND newer.model = mp.model
        AND newer.is_active = true
        AND (newer.effective_from > mp.effective_from
             OR (newer.effective_from = mp.effective_from AND newer.id > mp.id))
  );
