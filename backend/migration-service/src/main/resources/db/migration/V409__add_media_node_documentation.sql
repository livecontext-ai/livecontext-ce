-- ============================================================================
-- V409: Add node_type_documentation row for core:media
--   - core:media processes audio/video files on the optional renderer
--     component: probe (metadata), mux_audio (one audio track onto one video),
--     mix (1-8 audio tracks, optional video, with sidechain ducking),
--     extract_audio (pull the audio track out of a video).
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'media',
    'Media',
    'core',
    'core',
    'Processes audio/video files produced by upstream nodes. Four operations via the ''operation'' param: probe (read a file''s metadata as flat output fields), mux_audio (put ONE audio track onto ONE video, the common case), mix (mix 1 to 8 audio tracks, optionally onto a video, with per-track volume/offset/speed and automatic ducking of one track under another), extract_audio (pull the audio track out of a video). Every file param takes the WHOLE FileRef output of an upstream node as a whole-value template (e.g. {{core:download.output.file}} or {{interface:card.output.video}}), never .path or a URL string; all params accept {{...}} templates resolved at run time. Runs on the optional renderer component: when that component is not enabled on the installation the node FAILS at run time (validate warns with MEDIA_RENDERER_UNAVAILABLE). When emitting via set_plan, put the config under ''params''.',
    '{
      "operation":           {"type": "string", "required": true,  "description": "One of: probe, mux_audio, mix, extract_audio."},
      "input":               {"type": "string|object", "required": false, "description": "probe/extract_audio: REQUIRED whole FileRef expression (or literal FileRef object from files action=get, field ref) of the file to read (video or audio for probe, video for extract_audio)."},
      "video":               {"type": "string|object", "required": false, "description": "mux_audio: REQUIRED whole FileRef expression (or literal FileRef object) of the video. mix: optional; when present the output is an mp4 with the video stream copied, when absent the mix is audio-only."},
      "audio":               {"type": "string|object", "required": false, "description": "mux_audio: REQUIRED whole FileRef expression (or literal FileRef object) of the audio track to put onto the video."},
      "tracks":              {"type": "array",  "required": false, "description": "mix: REQUIRED array of 1-8 tracks. Each track: {source: whole FileRef expression (required), id: name for duck_under references (default track_1, track_2, ... in array order), volume: percent 0-400 (default 100), offset_seconds: start time >= 0 (default 0), trim_start_seconds/trim_end_seconds: use only this segment of the track, loop: boolean (default false), fade_in_seconds/fade_out_seconds: >= 0 (defaults 0/0), speed: 0.5-2.0 pitch-preserving (default 1.0), duck_under: id of ANOTHER track that this track automatically lowers under while it is audible, duck_amount_db (default 12), duck_attack_ms (default 20), duck_release_ms (default 300)}."},
      "volume":              {"type": "number", "required": false, "default": 100, "description": "mux_audio: audio volume in percent, 0-400."},
      "offset_seconds":      {"type": "number", "required": false, "default": 0, "description": "mux_audio: the audio starts at this time on the video, >= 0."},
      "trim_start_seconds":  {"type": "number", "required": false, "description": "mux_audio/extract_audio: use only from this second of the audio, >= 0."},
      "trim_end_seconds":    {"type": "number", "required": false, "description": "mux_audio/extract_audio: use only up to this second of the audio, >= 0."},
      "loop":                {"type": "boolean", "required": false, "default": false, "description": "mux_audio: loop the audio to the video length (same effect as audio_fit ''loop''). Cannot be combined with trim_start_seconds/trim_end_seconds on the same audio or track."},
      "fade_in_seconds":     {"type": "number", "required": false, "default": 0, "description": "mux_audio: fade-in duration in seconds, >= 0."},
      "fade_out_seconds":    {"type": "number", "required": false, "default": 1.0, "description": "mux_audio: fade-out duration in seconds, >= 0."},
      "keep_original_audio": {"type": "boolean", "required": false, "default": false, "description": "mux_audio/mix (with video only): keep the video''s own audio MIXED under the new track(s)."},
      "original_volume":     {"type": "number", "required": false, "default": 100, "description": "Level of the kept original track in percent 0-400 (only with keep_original_audio true)."},
      "audio_fit":           {"type": "string", "required": false, "default": "pad", "description": "What happens when the audio is shorter/longer than the video: pad, shortest, or loop."},
      "normalize":           {"type": "boolean|number", "required": false, "default": true, "description": "Loudness normalisation: true (target -16 LUFS), false (off), or a LUFS target number between -70 and -5."},
      "audio_bitrate":       {"type": "string", "required": false, "default": "192k", "description": "Output audio bitrate."},
      "output_format":       {"type": "string", "required": false, "default": "mp3", "description": "extract_audio and audio-only mix: mp3, wav, or aac. Forced to mp4 when a video is present."}
    }'::jsonb,
    '{
      "file":             {"type": "fileRef", "description": "The produced media file (mux_audio: mp4; mix: mp4 with video, else mp3/wav/aac; extract_audio: mp3/wav/aac). Null for probe. Reference via {{core:<label>.output.file}} and map the WHOLE object into downstream file params."},
      "duration_seconds": {"type": "number", "description": "Duration in seconds of the produced file (probe: of the probed file). Handy for calibrating narration length or interface video budgets."},
      "size_bytes":       {"type": "number", "description": "probe only: file size in bytes"},
      "format_name":      {"type": "string", "description": "probe only: container format name (e.g. mov,mp4,m4a or mp3)"},
      "bit_rate":         {"type": "number", "description": "probe only: overall bit rate in bits per second, null when unknown"},
      "has_video":        {"type": "boolean", "description": "probe only: true when the file contains a video stream"},
      "has_audio":        {"type": "boolean", "description": "probe only: true when the file contains an audio stream"},
      "video":            {"type": "object", "description": "probe only: {codec, width, height, fps}, null when has_video is false"},
      "audio":            {"type": "object", "description": "probe only: {codec, sample_rate, channels}, null when has_audio is false"}
    }'::jsonb,
    NULL,
    NULL,
    '["The node FAILS (does not silently continue) when the renderer component is absent, an input file is missing or foreign to the workflow''s tenant, or a param is out of bounds - route the failure or fix the reference.", "probe outputs FLAT fields and no file: use {{core:<label>.output.duration_seconds}} directly.", "loop:true cannot be combined with trim_start_seconds/trim_end_seconds on the same audio or track; an audio-only mix cannot have loop:true on EVERY track (nothing anchors the output length).", "Timeout or too-large failures mean the inputs exceed this installation''s render budget: trim or use smaller files. A busy failure means retry when fewer media operations run concurrently.", "Ducking recipe: give the voice track an id, then set duck_under to that id on the music track - the music dips automatically while the voice is audible.", "Calibrated narration recipe: probe the video, compute the word budget from duration_seconds in a code node, generate the speech, then mux_audio it onto the video."]'::jsonb,
    '[]'::jsonb,
    '["media", "audio", "video", "mux", "mix", "duck", "ducking", "soundtrack", "music", "voiceover", "extract", "probe", "duration", "mp3", "mp4"]'::jsonb,
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
