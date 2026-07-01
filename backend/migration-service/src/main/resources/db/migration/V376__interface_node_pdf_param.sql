-- Interface node: PDF render toggle + page options, mirroring V229's generateScreenshot.
-- Adds the `generatePdf` / `pdfFormat` / `pdfLandscape` parameters and the `pdf` output to the
-- interface row of node_type_documentation so the agent-facing node library documents them.
-- Idempotent: jsonb_set with create=true overwrites/creates the keys on every run.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(
            jsonb_set(
                parameters,
                '{generatePdf}',
                '{
                    "type": "boolean",
                    "default": false,
                    "required": false,
                    "description": "When true, render the interface to a PDF and expose it as the `pdf` FileRef output for downstream nodes (email attachment, Telegram send_document, agent file input). Best-effort: failure leaves the field absent, the workflow does NOT fail."
                }'::jsonb,
                true
            ),
            '{pdfFormat}',
            '{
                "type": "string",
                "default": "A4",
                "required": false,
                "description": "Page size for the generatePdf output: A4 | Letter | Legal (default A4). Ignored when generatePdf is false."
            }'::jsonb,
            true
        ),
        '{pdfLandscape}',
        '{
            "type": "boolean",
            "default": false,
            "required": false,
            "description": "When true, render the generatePdf output in landscape orientation. Ignored when generatePdf is false."
        }'::jsonb,
        true
    ),
    outputs = jsonb_set(
        outputs,
        '{pdf}',
        '{
            "type": "fileRef",
            "description": "PDF rendering of the interface. Present only when generatePdf=true AND the render succeeded. Reference via {{interface:<label>.output.pdf}}; map the whole FileRef into a file-accepting param to attach/send it."
        }'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
