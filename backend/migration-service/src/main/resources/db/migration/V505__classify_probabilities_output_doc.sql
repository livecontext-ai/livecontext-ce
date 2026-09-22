-- Document the classify node's `probabilities` output, which a DECISION model fills.
--
-- THE 3-WAY ALIGNMENT. The mapper's field names, node_type_documentation.outputs (what
-- the workflow-building agent reads) and the frontend schema must name the same key, or
-- the agent writes a template that resolves to nothing at runtime, silently. This
-- migration is the DB leg; the other two are ClassifyNodeSpec and UnifiedNodeOutput.
--
-- MERGED, NOT REWRITTEN. `||` adds the one key and leaves every other output exactly as
-- V422 left it. Restating the whole object would mean retyping fields this change has no
-- opinion about, and would quietly drop anything added to the column since.
--
-- The FIRST statement is guarded on the key's absence, so a replay neither duplicates it
-- nor reverts a later edit to that description. The three that follow overwrite what they
-- target. Flyway applies a migration once, so that is a statement about replaying this
-- file by hand, not about the deploy.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- node_type_documentation lives in the orchestrator schema, which beforeEachMigrate.sql
-- puts first on the path before every migration. Stated explicitly because V504 sets the
-- path to `agent`: under Flyway the reset happens in between, but running the two by hand
-- in one session would resolve this table in the wrong schema.
SET search_path TO orchestrator, public;

UPDATE node_type_documentation
SET outputs = outputs || '{
  "probabilities": {
    "type": "object",
    "description": "Probability (0 to 1) per category label, e.g. {\"urgent\": 0.94, \"normal\": 0.05, \"spam\": 0.01}. Present ONLY when the node runs on a decision model; absent on a chat model, where confidence is the model''s own estimate rather than a measured distribution. Read it to tell a clear win from a close call: {{agent:<label>.output.probabilities}} gives the whole map, and comparing the top two is what distinguishes a 0.98/0.01 result from a 0.34/0.33 coin flip worth routing to a human."
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'classify'
  AND NOT (outputs ? 'probabilities');

-- `reasoning` no longer always holds prose. On a decision model there is no explanation to
-- give, so the node stores the ranked distribution there instead ("urgent 0.94, normal
-- 0.05") rather than leaving the field empty and silently breaking every workflow that
-- already reads it. An agent told only "AI reasoning for the classification" would write a
-- prompt-style consumer for what is, on that engine, a numeric string.
UPDATE node_type_documentation
SET outputs = jsonb_set(outputs, '{reasoning,description}', to_jsonb(
        'Why this category was chosen. On a chat model, a one-sentence explanation the '
        || 'model wrote. On a decision model there is no explanation to write, so this '
        || 'holds the ranked distribution instead, e.g. "urgent 0.94, normal 0.05, spam '
        || '0.01" - the THREE highest-scoring categories only, rendered as one line for a '
        || 'human to read. It is not the whole distribution and is not meant to be parsed: '
        || 'read `probabilities` for every category and for exact values.'
    )),
    updated_at = NOW()
WHERE type = 'classify'
  AND outputs ? 'reasoning';

-- Tell the agent the node now has TWO engines, because nothing else does. The `provider`
-- enum is replaced at read time from the live catalogue (ModelCatalogEnricher), so a
-- decision provider already validates; what was missing is any statement that it exists
-- or what changes when it is chosen. An agent that cannot know about an engine will never
-- pick it.
UPDATE node_type_documentation
SET parameters = jsonb_set(parameters, '{provider,description}', to_jsonb(
        'Which engine runs the classification. A CHAT provider (openai, anthropic, google, '
        || 'mistral, deepseek) reads the categories in a prompt and writes back a label. A '
        || 'DECISION provider ("typesafe", model "jev-latest") scores every declared '
        || 'category in one call: it cannot answer with a label you did not declare, it '
        || 'fills the `probabilities` output, it ignores `temperature`, and it costs a '
        || 'fraction of a chat model. Everything else is identical - same categories, same '
        || 'category_N ports, same branches - so switching engine is a provider/model '
        || 'change and nothing else. Omit to use the platform default (a chat model).'
    )),
    updated_at = NOW()
WHERE type = 'classify'
  AND parameters ? 'provider';

-- Distinct labels are a REQUIREMENT, not a style note, and the agent had no way to know:
-- routing resolves a label to the FIRST category that matches it, so a duplicate makes one
-- branch unreachable, and the decision engine refuses the node outright before it calls
-- anything. Said on the parameter the agent reads when it writes the categories.
UPDATE node_type_documentation
SET parameters = jsonb_set(parameters, '{categories,description}', to_jsonb(
        'Array of {label, description} objects, minimum 2. The AI uses each description to '
        || 'understand what belongs in that category, so write them for a reader who has '
        || 'never seen the data. LABELS MUST BE DISTINCT: one port is created per category '
        || 'in order (category_0, category_1, ...), and a repeated label resolves to the '
        || 'first one, leaving the other branch permanently unreachable. A decision model '
        || 'refuses a node with duplicate labels rather than run it. Example: [{label: '
        || '''billing'', description: ''Payment and invoice issues''}, {label: '
        || '''technical'', description: ''Bugs and errors''}]'
    )),
    updated_at = NOW()
WHERE type = 'classify'
  AND parameters ? 'categories';

UPDATE node_type_documentation
SET concepts = concepts || to_jsonb(ARRAY[
        'TWO ENGINES: a chat model, or a decision model ("typesafe" / "jev-latest") that '
        || 'scores every category at once and cannot return an undeclared label. Prefer the '
        || 'decision model for high-volume routing where the categories are fixed; it is '
        || 'faster and far cheaper. Keep a chat model when the categories need reasoning '
        || 'about content the description cannot capture.',
        'CLOSE CALLS: on a decision model, `probabilities` gives a score per category. '
        || 'Comparing the top two is how you tell a clear win from a coin flip worth routing '
        || 'to a human - `confidence` alone cannot express that, and on a chat model it is '
        || 'the model''s own estimate rather than a measured distribution.'
    ]::text[]),
    updated_at = NOW()
WHERE type = 'classify'
  AND NOT (concepts::text LIKE '%TWO ENGINES%');
