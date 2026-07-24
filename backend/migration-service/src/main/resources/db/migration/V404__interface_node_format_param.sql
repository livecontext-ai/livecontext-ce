-- Interface node: global display/capture format shared by screenshot + video + previews.
-- Adds the `format` parameter to the interface row of node_type_documentation and
-- updates the screenshot/video output descriptions that hardcoded fixed dimensions.
-- The PDF output deliberately keeps its own paper-based pdfFormat (A4/Letter/Legal).
-- Idempotent: jsonb_set with create=true overwrites/creates the keys on every run.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(
            parameters,
            '{format}',
            '{
            "type": "string",
            "default": null,
            "required": false,
            "description": "GLOBAL display/capture format of this interface node - one setting shared by the generateScreenshot PNG, the generateVideo clip (unless an explicit videoPreset overrides it) and every preview surface (canvas node, side panel, shared application), so all of them show the same aspect ratio. UNSET (the default) keeps the legacy behaviour: full-page screenshot at 1280x800 viewport width, video at the vertical preset, previews at 1280x800. Preset names: classic (1280x800 exact frame) | widescreen (1920x1080) | vertical (1080x1920, TikTok/Reels/Shorts) | square (1080x1080) | portrait (1080x1350) | mobile (390x844) | tablet (820x1180) | desktop (1440x900) | banner (1500x500) | social_card (1200x630) | a4_portrait (794x1123) | a4_landscape (1123x794). Also accepts a custom \"WIDTHxHEIGHT\" string, each dimension 16-2160, floored to even (e.g. \"1080x1920\"). Unknown values are treated as unset. Note: setting format=classic differs from leaving it unset - classic captures an EXACT 1280x800 frame (crops below the fold) while unset captures the full page. The generatePdf output ignores this and keeps its paper-based pdfFormat/pdfLandscape. Author the interface HTML for the format''s width (meta viewport width should match)."
        }'::jsonb,
            true
        ),
        '{videoPreset,description}',
        to_jsonb(
            'Per-video override of the capture dimensions: vertical (1080x1920, TikTok/Reels/Shorts) | horizontal (1920x1080) | square (1080x1080). Precedence: explicit videoPreset > the node''s format param > vertical default. Omit it when format is set so the video and the screenshot share one aspect ratio. Ignored when generateVideo is false.'::text
        ),
        true
    ),
    outputs = jsonb_set(
        jsonb_set(
            outputs,
            '{screenshot,description}',
            to_jsonb(
                'PNG capture of the rendered interface. Without a format param this is a full-page capture at 1280x800 viewport width; with a format it is an exact WIDTHxHEIGHT frame matching that format (content below the fold is cropped). Present only when generateScreenshot=true and the rendering component captured successfully.'::text
            ),
            true
        ),
        '{video,description}',
        to_jsonb(
            'MP4 recording of the interface animation. Dimensions follow the explicit videoPreset when set (vertical 1080x1920 / horizontal 1920x1080 / square 1080x1080), otherwise the node''s format param, otherwise vertical. Length is at most videoMaxDurationSeconds, shorter when the page sets window.__DONE__ = true. Present only when generateVideo=true and the rendering component recorded successfully.'::text
        ),
        true
    ),
    updated_at = NOW()
WHERE type = 'interface';
