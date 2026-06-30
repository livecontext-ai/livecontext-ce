-- V229: Add `generateScreenshot` parameter + `screenshot` (FileRef) output to the
-- INTERFACE row of node_type_documentation. Best-effort capture; absent on failure.
-- Idempotent UPDATE (safe to re-apply - overwrites the parameters + outputs JSONB).
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{generateScreenshot}',
        '{
            "type": "boolean",
            "default": false,
            "required": false,
            "description": "When true, capture a PNG of the rendered interface and expose it as the `screenshot` FileRef output for downstream nodes (Email, Telegram, agent image input). Best-effort: failure leaves the field absent, the workflow does NOT fail."
        }'::jsonb,
        true
    ),
    outputs = jsonb_set(
        outputs,
        '{screenshot}',
        '{
            "type": "fileRef",
            "description": "PNG of the rendered interface (1280x800). Present only when generateScreenshot=true AND capture succeeded. Reference via {{interface:<label>.output.screenshot}}."
        }'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
