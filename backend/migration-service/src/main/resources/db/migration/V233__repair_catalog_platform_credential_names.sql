-- Repair CE installs bootstrapped from legacy catalog-data.sql.gz dumps where
-- catalog.apis.platform_credential_name was left as NULL/\N or guessed from a
-- shared legacy icon_slug. The user-facing credential-template endpoint joins
-- apis.platform_credential_name to credentials.credential_name, so missing or
-- wrong values hide Available Integrations and platform credential state.

WITH per_api_credential AS (
    SELECT a.id AS api_id, MIN(tc.credential_name) AS credential_name
    FROM catalog.apis a
    JOIN catalog.api_tools at ON at.api_id = a.id
    JOIN catalog.tool_credentials tc ON tc.api_tool_id = at.id
    JOIN catalog.credentials c ON c.credential_name = tc.credential_name
    WHERE a.source = 'import'
      AND tc.credential_name IS NOT NULL
      AND btrim(tc.credential_name) <> ''
    GROUP BY a.id
    HAVING COUNT(DISTINCT tc.credential_name) = 1
),
unique_icon_credential AS (
    SELECT c.icon_slug, MIN(c.credential_name) AS credential_name
    FROM catalog.credentials c
    WHERE c.icon_slug IS NOT NULL
      AND btrim(c.icon_slug) <> ''
      AND c.credential_name IS NOT NULL
      AND btrim(c.credential_name) <> ''
    GROUP BY c.icon_slug
    HAVING COUNT(DISTINCT c.credential_name) = 1
),
resolved_credentials AS (
    SELECT a.id AS api_id,
           COALESCE(pac.credential_name, uic.credential_name) AS credential_name
    FROM catalog.apis a
    LEFT JOIN per_api_credential pac ON pac.api_id = a.id
    LEFT JOIN unique_icon_credential uic ON uic.icon_slug = a.icon_slug
    WHERE a.source = 'import'
)
UPDATE catalog.apis a
SET platform_credential_name = rc.credential_name,
    updated_at = FLOOR(EXTRACT(EPOCH FROM now()) * 1000)::bigint
FROM resolved_credentials rc
WHERE a.id = rc.api_id
  AND rc.credential_name IS NOT NULL
  AND a.platform_credential_name IS DISTINCT FROM rc.credential_name;
