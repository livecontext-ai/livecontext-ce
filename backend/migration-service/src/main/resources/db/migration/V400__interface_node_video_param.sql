-- Interface node: video recording toggle + capture options, mirroring V376's generatePdf.
-- Adds the `generateVideo` / `videoPreset` / `videoMaxDurationSeconds` parameters and the `video`
-- output to the interface row of node_type_documentation so the agent-facing node library
-- documents them.
-- Idempotent: jsonb_set with create=true overwrites/creates the keys on every run.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(
            jsonb_set(
                parameters,
                '{generateVideo}',
                '{
                    "type": "boolean",
                    "default": false,
                    "required": false,
                    "description": "When true, record the interface''s animation to an MP4 and expose it as the `video` FileRef output for downstream nodes (social upload, Telegram send_video, email attachment). The recording stops as soon as the interface''s JS sets window.__DONE__ = true, or after videoMaxDurationSeconds otherwise. Best-effort: failure leaves the field absent, the workflow does NOT fail."
                }'::jsonb,
                true
            ),
            '{videoPreset}',
            '{
                "type": "string",
                "default": "vertical",
                "required": false,
                "description": "Capture format for the generateVideo output: vertical (1080x1920, TikTok/Reels/Shorts) | horizontal (1920x1080) | square (1080x1080). Default vertical. Ignored when generateVideo is false."
            }'::jsonb,
            true
        ),
        '{videoMaxDurationSeconds}',
        '{
            "type": "integer",
            "default": 30,
            "required": false,
            "description": "Recording ceiling in seconds for the generateVideo output (5-120, default 30). The interface can end the clip earlier by setting window.__DONE__ = true in its JS. Ignored when generateVideo is false."
        }'::jsonb,
        true
    ),
    outputs = jsonb_set(
        outputs,
        '{video}',
        '{
            "type": "fileRef",
            "description": "MP4 recording of the interface''s animation. Present only when generateVideo=true AND the recording succeeded. Reference via {{interface:<label>.output.video}}; map the whole FileRef into a file-accepting param to send/attach it."
        }'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
