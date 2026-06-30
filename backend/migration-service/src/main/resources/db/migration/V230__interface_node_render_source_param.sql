-- V230: Add `exposeRenderedSource` parameter + `rendered_html`/`rendered_css`/`rendered_js` outputs
-- to the INTERFACE row of node_type_documentation. Best-effort exposure; absent on render failure.
-- Idempotent UPDATE (safe to re-apply - overwrites the parameters + outputs JSONB).
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{exposeRenderedSource}',
        '{
            "type": "boolean",
            "default": false,
            "required": false,
            "description": "When true, expose the resolved interface templates as the `rendered_html`, `rendered_css`, `rendered_js` string outputs for downstream nodes (email body, debugging). Best-effort: render failure leaves the fields absent, the workflow does NOT fail."
        }'::jsonb,
        true
    ),
    outputs = jsonb_set(
        jsonb_set(
            jsonb_set(
                outputs,
                '{rendered_html}',
                '{
                    "type": "string",
                    "description": "Resolved HTML template of the rendered interface. Present only when exposeRenderedSource=true AND the renderer returned a non-null htmlTemplate. Capped at 256 KB; oversized templates are truncated to the first 256 KB. Reference via {{interface:<label>.output.rendered_html}}."
                }'::jsonb,
                true
            ),
            '{rendered_css}',
            '{
                "type": "string",
                "description": "Resolved CSS template of the rendered interface. Present only when exposeRenderedSource=true AND the renderer returned a non-null cssTemplate. Absent for interfaces with no CSS. Capped at 256 KB. Reference via {{interface:<label>.output.rendered_css}}."
            }'::jsonb,
            true
        ),
        '{rendered_js}',
        '{
            "type": "string",
            "description": "Resolved JS template of the rendered interface. Present only when exposeRenderedSource=true AND the renderer returned a non-null jsTemplate. Absent for interfaces with no JS. Capped at 256 KB. Reference via {{interface:<label>.output.rendered_js}}."
        }'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
