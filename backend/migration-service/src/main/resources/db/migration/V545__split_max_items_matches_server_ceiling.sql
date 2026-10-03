-- LC-064 (security audit, round 2): align the stored split documentation with the server
-- fan-out ceiling (SplitNodeExecutor.SPLIT_HARD_CEILING = 10000).
--
-- The row seeded by V11 declares maxItems max 1000. The generic parameter validator reads this
-- row, so add_node refused values the creator, set_plan, validate and the run itself accept,
-- and the agent was shown two different ceilings. The run bound is unchanged: a list longer than
-- the ceiling with no maxItems still fails the node instead of being truncated.
UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(parameters, '{maxItems,max}', '10000'::jsonb),
        '{maxItems,description}',
        to_jsonb('Maximum items to process in parallel (default: 100, max: 10000). Items beyond maxItems are not processed; a list above 10000 with no maxItems fails the run instead of being truncated. Also accepts: max_items'::text)),
    updated_at = NOW()
WHERE type = 'split'
  AND parameters ? 'maxItems';
