-- ============================================================================
-- V405: Add node_type_documentation row for core:public_link
--   - core:public_link mints a public, time-limited, HMAC-signed download URL
--     for a stored file (FileRef), on the platform's own storage. For APIs that
--     PULL media from a URL instead of accepting an upload (Instagram media
--     containers, TikTok PULL_FROM_URL, link previews).
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'public_link',
    'Public Link',
    'core',
    'core',
    'Turns a file produced by an upstream node (a FileRef output such as an interface video, a downloaded file, a generated PDF) into a PUBLIC, time-limited download URL on the platform''s own storage. External servers can fetch the URL with no credentials until it expires; nothing is copied to any third-party host. Use it when a tool parameter wants a URL it will download itself (Instagram create_media_container video_url, TikTok PULL_FROM_URL source, Facebook link) instead of a file upload. The node only signs files owned by the executing workflow''s tenant; a foreign file fails the node. When emitting via set_plan, put the config under ''params'': { file, ttl_minutes, disposition }.',
    '{
      "file":        {"type": "string", "required": true,  "description": "The FileRef to expose, as a template reference to the WHOLE file object, e.g. {{interface:card.output.video}} or {{core:download.output.file}}. Passing .path or a plain URL string fails."},
      "ttl_minutes": {"type": "number", "required": false, "default": 240, "description": "Link lifetime in minutes, clamped to 5-10080 (7 days). After expiry the URL returns an error. Mint the link close to where it is consumed so approval delays do not eat the lifetime."},
      "disposition": {"type": "string", "required": false, "default": "inline", "description": "How browsers handle the file at the URL: inline (render) or attachment (download prompt). URL-pull APIs work with either; keep inline."}
    }'::jsonb,
    '{
      "url":         {"type": "string", "description": "Absolute public URL for the file, fetchable by any external server with no credentials until expiry. Reference via {{core:<label>.output.url}}."},
      "expires_at":  {"type": "string", "description": "ISO timestamp when the link stops working"},
      "ttl_minutes": {"type": "number", "description": "Effective lifetime in minutes after clamping"},
      "file":        {"type": "fileRef", "description": "Echo of the input FileRef the link points at"}
    }'::jsonb,
    NULL,
    NULL,
    '["The node fails (does not silently continue) when the file is missing, foreign to the workflow''s tenant, or when public links are disabled on the installation - route the failure or fix the reference.", "The URL is public: anyone holding it can download the file until expiry. Choose ttl_minutes accordingly."]'::jsonb,
    '[]'::jsonb,
    '["public", "link", "url", "share", "signed", "expiring", "file", "external", "instagram", "tiktok", "pull"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    global_variables = EXCLUDED.global_variables,
    edge_ports = EXCLUDED.edge_ports,
    concepts = EXCLUDED.concepts,
    examples = EXCLUDED.examples,
    keywords = EXCLUDED.keywords,
    enabled = EXCLUDED.enabled,
    updated_at = NOW();
