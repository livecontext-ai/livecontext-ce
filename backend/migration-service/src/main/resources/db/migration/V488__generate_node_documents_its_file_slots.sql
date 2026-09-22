-- The generate node gained three file slots, and the agent reads this table to
-- know a parameter exists at all.
--
-- A model can take SEVERAL images meaning different things in one call: the
-- still the clip opens on, the one it lands on, and files it only borrows a
-- subject or a style from. With `input_image` as the platform's single image
-- parameter, two of those three had nowhere to go whatever the provider
-- accepted, so they are now their own slots (GenerationSpec.ASSET_PARAMS).
--
-- Documented here rather than left to the catalogue: an agent writing a
-- generate node reads these `parameters`, and a slot absent from them is a slot
-- it will not use. `input_image` is deliberately untouched: it is still the slot
-- for a model that takes ONE image, and every saved workflow already writes it.

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET parameters = parameters
    || '{
      "first_frame_image": {"type": "string|object", "required": false, "description": "The still the produced clip OPENS on: the WHOLE FileRef output of an upstream node as a whole-value template (e.g. {{core:download.output.file}}), never .path and never a URL. Only models listing first_frame_image accept it - read that model''s inputs in workflow(action=''help'', topics=[''generate''])."},
      "last_frame_image":  {"type": "string|object", "required": false, "description": "The still the produced clip LANDS on, same shape as first_frame_image. Some models take it only TOGETHER with first_frame_image, and some refuse it in the same call as the slot they take references in, because pinning a frame and lending a reference are different kinds of request to them. Both are refused naming the slots, before anything is charged; workflow(action=''help'', topics=[''generate'']) lists them per model as requires and excludes."},
      "reference_image":   {"type": "string|object", "required": false, "description": "Files the result borrows a subject or a style from without ever showing them as a frame. Same shape as first_frame_image, and a LIST of whole FileRefs where that model''s inputs row says maxItems above 1."}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'generate';
