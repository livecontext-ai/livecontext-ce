-- Move the display/capture format from the interface NODE to the interface ENTITY.
--
-- WHY: an interface's HTML is authored for ONE fixed viewport width (the iframe does not
-- apply device-width), so the format is intrinsic to the interface's design, not to a given
-- workflow's use of it. Keeping it on the node meant (a) the same interface could be rendered
-- at the wrong width by another node, and (b) every entity-driven surface (interface list,
-- fullscreen view, marketplace cards, showcase) had no format at all and rendered at a
-- hardcoded 1280x800. V404 added the node param; this migration retires it in favour of the
-- entity column.
--
-- NULL is meaningful and is the default: "no declared shape" = full-page screenshot at a
-- 1280x800 viewport. It is NOT the same as the 'classic' preset, which is an exact 1280x800
-- frame that crops below the fold. Never backfill NULL to a preset - every pre-existing
-- interface was authored as full-page content and must stay that way.
--
-- No backfill from the node params: the node-level format shipped one day earlier, and a
-- backfill would be ambiguous anyway (two nodes may reference one interface with different
-- formats, with no correct winner).

-- 1. The entity column. Two tables: the live interface AND the per-run snapshot, which carries
--    its own copy of the templates. Without the snapshot column, every snapshot-backed run
--    would silently fall back to 1280x800 (the render path prefers the snapshot).
SET search_path TO interface;

ALTER TABLE interfaces ADD COLUMN IF NOT EXISTS format VARCHAR(32);
ALTER TABLE interface_run_snapshots ADD COLUMN IF NOT EXISTS format VARCHAR(32);

COMMENT ON COLUMN interfaces.format IS
    'Display/capture format: a preset name (classic|widescreen|vertical|square|portrait|mobile|tablet|desktop|banner|social_card|a4_portrait|a4_landscape) or a custom "WIDTHxHEIGHT" (16-2160, even). NULL = no declared shape (full-page capture at 1280x800), which differs from the classic preset.';

-- 2. Retire the node param documented by V404, and revert the three descriptions V404 rewrote
--    to point at it. Leaving them would advertise a param the node no longer accepts.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = parameters - 'format',
    outputs = jsonb_set(
        jsonb_set(
            outputs,
            '{screenshot,description}',
            to_jsonb(
                'PNG capture of the rendered interface. Dimensions follow the format declared on the interface itself (set it with interface(action=''update'', interface_id=''<uuid>'', format=''vertical'')): with a format it is an exact WIDTHxHEIGHT frame matching that format (content below the fold is cropped); without one it is a full-page capture at 1280x800 viewport width. Present only when generateScreenshot=true and the rendering component captured successfully.'::text
            ),
            true
        ),
        '{video,description}',
        to_jsonb(
            'MP4 recording of the interface animation. Dimensions follow the explicit videoPreset when set (vertical 1080x1920 / horizontal 1920x1080 / square 1080x1080), otherwise the format declared on the interface itself, otherwise vertical. Length is at most videoMaxDurationSeconds, shorter when the page sets window.__DONE__ = true. Present only when generateVideo=true and the rendering component recorded successfully.'::text
        ),
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';

UPDATE node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{videoPreset,description}',
        to_jsonb(
            'Per-video override of the capture dimensions: vertical (1080x1920, TikTok/Reels/Shorts) | horizontal (1920x1080) | square (1080x1080). Precedence: explicit videoPreset > the format declared on the interface itself > vertical default. Omit it to let the video and the screenshot share the interface''s own format. Ignored when generateVideo is false.'::text
        ),
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
