-- Consolidation of catalog-service seed data (catalog seed data)
-- Idempotent: uses ON CONFLICT DO NOTHING for all INSERTs.
-- Production: no-op. Fresh install: creates initial tool data.

SET search_path TO catalog, public;

-- ============================================================================
-- Section 1: Instagram Tool Names (from V14)
-- Inserted by V43/V46 (self-contained), kept here as ON CONFLICT DO NOTHING fallback.
-- ============================================================================

-- (Instagram tool_names are now created dynamically in V43 with auto-created tool_category)

-- ============================================================================
-- Section 2: Normalization fixes (from V71, V72)
-- ============================================================================

-- V71: Normalize LinkedIn provider name (remove space)
UPDATE lexical_search_index
SET provider = 'external apis/linkedin tools'
WHERE provider = 'external apis/linked in tools';

-- V72: Normalize credential icon_slug and icon_url to match SVG file naming convention
UPDATE credentials
SET icon_slug = LOWER(REGEXP_REPLACE(icon_slug, '[^a-zA-Z0-9]', '', 'g'))
WHERE icon_slug IS NOT NULL
  AND icon_slug ~ '[^a-z0-9]';

UPDATE credentials
SET icon_url = '/icons/services/' || icon_slug || '.svg'
WHERE icon_slug IS NOT NULL
  AND icon_slug != ''
  AND (icon_url IS NULL OR icon_url != '/icons/services/' || icon_slug || '.svg');

UPDATE credentials
SET icon_slug = LOWER(REGEXP_REPLACE(
    REGEXP_REPLACE(icon_url, '^.*/([^/]+)\.svg$', '\1'),
    '[^a-zA-Z0-9]', '', 'g'
))
WHERE icon_slug IS NULL
  AND icon_url IS NOT NULL
  AND icon_url ~ '/icons/services/[^/]+\.svg$';

CREATE INDEX IF NOT EXISTS idx_credentials_icon_slug ON credentials(icon_slug);

-- ============================================================================
-- Section 3: Activate all APIs and tools by default (from V79)
-- ============================================================================

UPDATE apis SET is_active = true WHERE is_active = false;
UPDATE api_tools SET is_active = true WHERE is_active = false;
