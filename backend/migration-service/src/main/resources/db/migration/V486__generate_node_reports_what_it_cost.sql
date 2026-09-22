-- The generate node now reports what a run COST, not only the size it was charged on.
--
-- Why the documentation has to move with it: `node_type_documentation.outputs` is what an agent
-- READS before it writes a template, so a field the node emits and this row does not name is a
-- field nobody references. The same row also carried advice that is now wrong - it told the reader
-- to work the cost out by converting billed_quantity into the rate's own unit and multiplying,
-- which is arithmetic against a rate that can be republished between the run and the reading. The
-- amount committed is on the answer; that is the number to read.
--
-- ABSENT is not zero, and the wording says so wherever the field is named: a run on a key the
-- account configured itself is paid to the provider directly and carries no amount at all.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET outputs = jsonb_set(
        outputs,
        '{billed_credits}',
        '{"type": "number", "description": "What the platform charged for this run, in credits, as it was committed. Read this rather than working the cost out from billed_quantity and a rate: the rate can be republished between the run and the reading. ABSENT means the platform charged nothing - credential_source=''user'' pays the provider directly - and absent is not a charge of zero."}'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'generate';

-- The unit note keeps explaining the measurement (a model listed per minute reports seconds), and
-- stops sending the reader off to multiply.
UPDATE node_type_documentation
SET outputs = jsonb_set(
        outputs,
        '{billed_unit,description}',
        '"What billed_quantity counts: call, second, image or character. This is the unit the size was MEASURED in, which is not always the unit the rate is quoted in: a model listed per minute reports seconds here. What the run cost is reported directly as billed_credits."'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'generate';

-- Same correction in the tips a reader is shown beside the node. They live in `concepts`: the
-- table has no `best_practices` column, and V429 writes this array in the tenth position of its
-- column list, which is `concepts`. Reading that list off by one is invisible to every test in the
-- repo - nothing executes a migration - and it stops the whole application from booting.
UPDATE node_type_documentation
SET concepts = '["Pick the model FIRST: it decides the format, the accepted params and the price. generation(action=''models'') lists every id with its accepts, limits and rate; a model id cannot be guessed.", "A param the model does not accept, or a value outside its limits, is refused BEFORE the provider is called - the run costs nothing and the error names the accepted values, so correct it and run again.", "Every successful run on the PLATFORM key is charged, and what it cost is on the answer as output.billed_credits: read that instead of multiplying a rate by output.billed_quantity, because the rate can be republished between the run and the reading. A run with credential_source=''user'' is paid to the provider directly and carries no billed_credits at all, which is not a charge of zero.", "credential_source=''user'' runs on a key you configured yourself and the platform bills nothing for that node; ''platform'' uses the platform''s key at the platform price.", "The node FAILS when no asset comes back rather than continuing with an empty file - the call has already been charged by then, and a downstream node running on nothing would hide it.", "Chain into core:media to edit what was generated: generate a clip, generate a voice over, then mux_audio the two together."]'::jsonb,
    updated_at = NOW()
WHERE type = 'generate';
