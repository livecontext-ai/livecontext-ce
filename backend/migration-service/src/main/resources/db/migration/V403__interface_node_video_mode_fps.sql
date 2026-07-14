-- Interface node: video render mode + frame rate, extending V400's generateVideo.
-- Adds the `videoMode` / `videoFps` parameters to the interface row of
-- node_type_documentation so the agent-facing node library documents them.
-- Idempotent: jsonb_set with create=true overwrites/creates the keys on every run.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(
            parameters,
            '{videoMode}',
            '{
                "type": "string",
                "default": "smooth",
                "required": false,
                "description": "Render mode for the generateVideo output: smooth (offline frame-by-frame render under a virtual clock - every frame is perfect and the clip is fluid regardless of load; rendering takes roughly 2-4x the clip duration) | live (real-time screen recording - faster to produce but frames can drop under load). Default smooth. Ignored when generateVideo is false."
            }'::jsonb,
            true
        ),
        '{videoFps}',
        '{
            "type": "integer",
            "default": 30,
            "required": false,
            "description": "Output frame rate for the generateVideo clip (10-60, default 30). Higher fps = smoother motion and longer render time in smooth mode. Ignored when generateVideo is false."
        }'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
