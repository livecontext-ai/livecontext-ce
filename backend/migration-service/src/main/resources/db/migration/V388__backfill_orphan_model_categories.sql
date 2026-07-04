-- Backfill mode-aware DEFAULT category rows for every model_config_overrides row
-- that has ZERO model_category_settings rows ("orphans").
--
-- WHY: a model is only offered in a category-scoped selector (the chat /
-- browser_agent / image_generation pickers) if it has a row in
-- model_category_settings. Models inserted by a MIGRATION (e.g. V384/V386 seeded
-- qwen + moonshot/Kimi, and claude-fable-5) landed in model_config_overrides
-- WITHOUT category rows, so they showed in the raw catalog view yet were
-- invisible/unselectable in every agent + chat picker. The V156 category backfill
-- only covered rows that existed when it ran; anything inserted afterwards without
-- an explicit categories sidecar stayed orphaned. (The boot-time SEED path is
-- fixed forward-going by CatalogMergeService's assignDefaultCategoriesOnInsert,
-- but that only touches NEW inserts - existing orphans need this one-shot repair.)
--
-- Mode → default categories mirrors ModelCategory.acceptsMode (Java, the picker's
-- own predicate): NULL/'chat' → chat + browser_agent ; 'image' → image_generation ;
-- any other/unknown mode → left untouched (forward-safe, no bad category invented).
--
-- Idempotent: only rows with NO sidecar at all are touched, and every insert is
-- guarded by ON CONFLICT DO NOTHING, so a re-run (or a row that gained a category
-- between the two statements) is a no-op. enabled=true matches the seed/bundle
-- default; enabling a model grants no capability by itself (a provider without a
-- key/bridge stays out of the picker), so this never exposes anything unusable.
--
-- CE note: CE-blocked providers (openrouter/cohere) that happen to be orphaned get
-- categories here too, but they stay filtered at the picker + bundle layer on a CE
-- install (ModelCatalogService / CatalogBundleApplier), so this is a no-op for them
-- there and correct on cloud.
SET search_path TO agent;

-- chat-capable rows (mode NULL or 'chat') → chat + browser_agent
INSERT INTO model_category_settings (model_config_id, category, enabled)
SELECT mco.id, c.category, TRUE
FROM model_config_overrides mco
CROSS JOIN (VALUES ('chat'), ('browser_agent')) AS c(category)
WHERE (mco.mode IS NULL OR mco.mode = 'chat')
  AND NOT EXISTS (
      SELECT 1 FROM model_category_settings mcs WHERE mcs.model_config_id = mco.id
  )
ON CONFLICT (model_config_id, category) DO NOTHING;

-- image rows (mode 'image') → image_generation
INSERT INTO model_category_settings (model_config_id, category, enabled)
SELECT mco.id, 'image_generation', TRUE
FROM model_config_overrides mco
WHERE mco.mode = 'image'
  AND NOT EXISTS (
      SELECT 1 FROM model_category_settings mcs WHERE mcs.model_config_id = mco.id
  )
ON CONFLICT (model_config_id, category) DO NOTHING;
