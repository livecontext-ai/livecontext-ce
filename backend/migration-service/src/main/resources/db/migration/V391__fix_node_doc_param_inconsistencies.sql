-- ============================================================================
-- V391: Align agent-facing node_type_documentation with what the backend parses
-- ============================================================================
-- Cross-checked each documented build-parameter against the creator that
-- actually reads it (orchestrator builder/creators/*). Every fix below makes the
-- doc match the backend (backend is the source of truth). Without these, an agent
-- that copies the documented params/examples silently builds a broken node or
-- calls a dead tool name.
--
--  1. exit          status enum/default 'stopped' is REJECTED by the creator
--                   (UtilityNodeCreator accepts exited|cancelled|failed|completed).
--  2. approval      param 'message' is never read; the real param is
--                   contextTemplate (DecisionNodeCreator.executeAddApproval).
--  3. get_rows      order_by is consumed nowhere (sorting = a core:sort node).
--  4. workflow trg  trigger_on / input_mapping are read nowhere.
--  5. webhook       auth_config object is never read; auth_type enum listed
--                   unsupported values. Creator reads flat cred keys and only
--                   supports none|basic|header|jwt (TriggerCreator webhook branch).
--  6. chat          the supported optional chatMatch filter was undocumented.
--  7. merge         example sub-key {label} is a no-op; the creator auto-wires
--                   from {sourceStep} (ForkMergeNodeCreator.executeAddMerge).
--  8. email_inbox   example used the non-existent {{split:...}} template prefix;
--                   the split-body shorthand is {{item.<field>}}.
--  9. code          V371 reintroduced banned em/en-dashes after V349 stripped them.
-- 10. all           'workflow_builder(' is a dead tool name (renamed to 'workflow'
--                   in V22); later migrations reintroduced it in 5 nodes' examples.
-- ============================================================================

SET search_path TO orchestrator;

-- 1. exit: status values the creator actually accepts -------------------------
UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(parameters, '{status,enum}', '["exited","cancelled","failed","completed"]'::jsonb),
        '{status,default}', '"exited"'::jsonb),
    updated_at = NOW()
WHERE type = 'exit'
  AND parameters -> 'status' -> 'enum' @> '"stopped"'::jsonb;

-- 2. approval: message -> contextTemplate (the param the creator reads) --------
UPDATE node_type_documentation
SET parameters = (parameters - 'message') || jsonb_build_object(
        'contextTemplate', jsonb_build_object(
            'type', 'string',
            'required', false,
            'example', 'Please review order #{{mcp:fetch.output.order_id}}',
            'description', 'Message shown to the approver, resolved at yield (literal text + {{...}} references). Alias: context_template.')),
    examples = replace(examples::text, 'message:', 'contextTemplate:')::jsonb,
    updated_at = NOW()
WHERE type = 'approval'
  AND parameters ? 'message';

-- 3. get_rows: order_by is consumed nowhere -----------------------------------
UPDATE node_type_documentation
SET parameters = parameters - 'order_by',
    updated_at = NOW()
WHERE type = 'get_rows'
  AND parameters ? 'order_by';

-- 4. workflow trigger: trigger_on / input_mapping are read nowhere ------------
UPDATE node_type_documentation
SET parameters = parameters - 'trigger_on' - 'input_mapping',
    updated_at = NOW()
WHERE type = 'workflow'
  AND (parameters ? 'trigger_on' OR parameters ? 'input_mapping');

-- 5. webhook: real auth model (flat cred keys, supported auth_type values) -----
UPDATE node_type_documentation
SET parameters = jsonb_set(parameters - 'auth_config', '{auth_type,enum}',
        '["none","basic","header","jwt"]'::jsonb)
        || jsonb_build_object(
            'basicUsername', jsonb_build_object('type','string','required',false,
                'description','HTTP Basic username (auth_type=basic).'),
            'basicPassword', jsonb_build_object('type','string','required',false,
                'description','HTTP Basic password (auth_type=basic).'),
            'authHeaderName', jsonb_build_object('type','string','required',false,
                'description','Required header name (auth_type=header).'),
            'authHeaderValue', jsonb_build_object('type','string','required',false,
                'description','Expected header value (auth_type=header).'),
            'jwtSecretKey', jsonb_build_object('type','string','required',false,
                'description','JWT signing secret (auth_type=jwt).'),
            'jwtAlgorithm', jsonb_build_object('type','string','required',false,
                'description','JWT algorithm, e.g. HS256 (auth_type=jwt).')),
    updated_at = NOW()
WHERE type = 'webhook'
  AND parameters ? 'auth_config';

-- 6. chat: document the supported optional chatMatch filter --------------------
UPDATE node_type_documentation
SET parameters = parameters || jsonb_build_object(
        'chatMatch', jsonb_build_object(
            'type', 'object',
            'required', false,
            'description', 'Optional pattern filter {type, value, caseSensitive}. type: any | starts_with | ends_with | contains | equals | regex (default any). value is required unless type=any. Absent fires on any message.')),
    updated_at = NOW()
WHERE type = 'chat'
  AND NOT (parameters ? 'chatMatch');

-- 7. merge: the auto-wire sub-key is sourceStep, not label --------------------
UPDATE node_type_documentation
SET parameters = replace(parameters::text,
        'Optional: [{label}] to name the expected inputs. Also accepts: inputs. Usually not needed - merge auto-detects predecessors from edges',
        'Optional: [{sourceStep: ''<existing node label>''}] to explicitly wire an input edge. Alias: inputs. Usually not needed - merge auto-detects predecessors from edges')::jsonb,
    examples = replace(examples::text, '{label: ''Email''}, {label: ''SMS''}',
        '{sourceStep: ''Email''}, {sourceStep: ''SMS''}')::jsonb,
    updated_at = NOW()
WHERE type = 'merge';

-- 8. email_inbox: the split-body shorthand is {{item.<field>}} -----------------
UPDATE node_type_documentation
SET examples = replace(examples::text, '{{split:item.output.uid}}', '{{item.uid}}')::jsonb,
    updated_at = NOW()
WHERE type = 'email_inbox'
  AND examples::text LIKE '%{{split:item.output.uid}}%';

-- 9. code: strip banned em/en-dashes reintroduced by V371 (mirror V349) --------
--    The dash glyphs in the pattern are the intentional glyphs being removed.
UPDATE node_type_documentation
SET description = translate(description, '--―', '---'),
    concepts = translate(concepts::text, '--―', '---')::jsonb,
    updated_at = NOW()
WHERE type = 'code'
  AND (description ~ '[--―]' OR concepts::text ~ '[--―]');

-- 10. Dead tool name: workflow_builder( was renamed to workflow( in V22 --------
UPDATE node_type_documentation
SET examples = replace(examples::text, 'workflow_builder(', 'workflow(')::jsonb,
    updated_at = NOW()
WHERE examples::text LIKE '%workflow_builder(%';

UPDATE node_type_documentation
SET concepts = replace(concepts::text, 'workflow_builder(', 'workflow(')::jsonb,
    updated_at = NOW()
WHERE concepts::text LIKE '%workflow_builder(%';

UPDATE node_type_documentation
SET description = replace(description, 'workflow_builder(', 'workflow('),
    updated_at = NOW()
WHERE description LIKE '%workflow_builder(%';
