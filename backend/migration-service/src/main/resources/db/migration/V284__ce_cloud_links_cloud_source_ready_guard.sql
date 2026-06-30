-- Guard against intermediate builds that may have selected CLOUD before the
-- CE install was registered on the Cloud side.
UPDATE publication.ce_cloud_links
SET llm_source = 'BYOK'
WHERE llm_source = 'CLOUD'
  AND registered_at IS NULL;
