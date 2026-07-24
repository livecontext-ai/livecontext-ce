// Markdown body for "From dataset to live workflow". Plain string module (see
// the-niche-data-advantage.ts's siblings for the rationale).
//
// This article's value is REAL node mechanics from this engine, so every
// template string must match the code. The load-bearing ones were verified
// against source on 2026-07-24:
//   - HTTP node body under .data: HttpRequestNode.java:510 (result.put("data", parsed))
//   - code node .result double-wrapper: docs/product/facts/expressions.md:93
//   - SpEL double()/arithmetic/comparison inside {{ }}: expressions.md:37,45
//   - Table-CRUD where is textual, SpEL is numeric: expressions.md:96
//   - array index items[0]: expressions.md:32
// Do NOT "modernise" a template string without re-checking it against the code;
// a wrong {{...}} is the one error a reader copies and cannot debug.
//
// Citations are file:line into the repo, not external URLs (this piece has no
// external links by design; the evidence is the code). Companion pieces cover
// dataset selection, cost, budgets and audit; this one starts after the dataset
// is chosen and stops at a running workflow.
const content = `You picked the data. Now it has to run itself.

A qualified niche dataset is inert until something reads it on a schedule, decides against it, and ends in an action a human will trust. This piece starts exactly there: the dataset is already chosen. How to select a niche dataset, and what context and budget it costs to run one, are covered in the companion pieces (linked once, not re-argued here). This one begins after the data is chosen and stops at a running workflow.

Every node mechanic below is cited to one production workflow engine's code and docs, with the exact strings. The worked build in one line: a schedule trigger refreshes hourly, an HTTP request re-fetches the tracked listing, a code node normalizes the raw response, a table lookup plus a decision splits a never-seen SKU from a known one, a second decision flags a material price move, a user-approval gate guards the write, and only then does an alert fire. An idempotent baseline write means re-runs never duplicate a single row. For each node the pattern is the same: the portable trap first, then this engine's exact string.

## The graph: eight nodes, seven prefixes

Before the prose walks the build, see the whole thing at once. The engine names every node with a category prefix. There are seven: \`trigger:\`, \`mcp:\`, \`table:\`, \`agent:\`, \`core:\`, \`note:\`, and \`interface:\` (\`LabelNormalizer.java:14-24\`, \`:262-265\`). The \`core:\` family is the biggest, covering Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, and User Approval (\`LabelNormalizer.java:182\`). Note that HTTP Request is a \`core:\` node, not an \`mcp:\` one (\`WORKFLOW_NODE_TYPES.md:1559-1594\`).

| # | Node (role) | What it does in the build | Key output field | Cited to |
|---|---|---|---|---|
| 1 | Schedule trigger | Fires hourly, the heartbeat | \`triggered_at\`, \`execution_count\` | \`triggers.md:23-27\` |
| 2 | \`core:fetch_listings\` (HTTP) | Fresh read of the live source | \`data.organic_results[]\` | \`AGENTS.md:371\`; \`nodes.md:66\` |
| 3 | \`core:normalize\` (code) | Reshape raw JSON to \`{sku, price, currency, seen_at}\` | \`result\` (wrapped) | \`CodeNode.java:130-137\` |
| 4 | \`find_rows\` (baseline lookup) | Idempotency probe by \`sku\` | \`items\`, \`item_count\` | \`ConceptsHelpProvider.java:281\` |
| 5 | \`core:decision\` (new vs known) | Split on \`item_count == 0\` | \`selected_branch\` | \`nodes.md:29\` |
| 6a | \`insert_row\` (new branch) | Write the baseline | inserted row | \`tables.md:52\` |
| 6b | \`core:decision\` (material move) | Flag a move above 5% | \`selected_branch\` | \`expressions.md:96\` |
| 7 | \`core:user_approval\` | Human gate before the write | \`approved\`/\`rejected\`/\`timeout\` | \`nodes.md:39\` |
| 8 | \`mcp:send_alert\` + \`update_row\` | The real action, then the guarded write | sent, merged row | \`nodes.md:62\`; \`tables.md:49\` |

The three table operations map to builder palette tiles Create Row / Find Rows / Update Row (kinds \`create-row\` / \`find\` / \`update-row\`); the prose names \`insert_row\` / \`find_rows\` / \`update_row\` are the agent-tool aliases for those tiles.

Every node output is referenced with one uniform shape, regardless of node type:

\`\`\`
{{type:label.output.field}}
\`\`\`

The \`.output.\` segment is mandatory (\`WORKFLOW_NODE_TYPES.md:1650-1660\`; \`expressions.md:9\`). Nested fields and array indexing both work (\`expressions.md:28-32\`):

\`\`\`
{{mcp:api_call.output.data.users[0].email}}
\`\`\`

Labels normalize through a fixed five-step pipeline: transliterate accents, lowercase, replace every non-alphanumeric character with an underscore, collapse repeats, trim the ends (\`LabelNormalizer.java:55-82\`). So a node you label \`Baseline Lookup\` is referenced as:

\`\`\`
{{table:baseline_lookup.output.item_count}}
\`\`\`

If an LLM authors a raw label with spaces inside a template, the engine auto-normalizes it before evaluation (\`LabelNormalizer.java:496-537\`), which is why the spaces do not break resolution. One hard constraint governs what any node can read: it can reference only its ancestors, the nodes that already executed. Peers and parallel branches cannot see each other, and there is no forward reference. The engine resolves only from \`context.stepOutputs\` (\`WORKFLOW_NODE_TYPES.md:1617-1644\`).

The schedule trigger takes a standard five-field cron only. The builder default \`0 * * * *\` is hourly, and interval shorthand like \`5m\` or \`1h\` is rejected outright (\`triggers.md:23-27\`). It emits \`triggered_at\` and a one-based \`execution_count\`, and each fire opens a new epoch (\`EXECUTION_ENGINE.md:15\`).

## Refresh and read: the heartbeat and the real response shape

Node 1 is the heartbeat. Node 2 is an HTTP Request node that pulls the current listing for the one SKU this workflow tracks. This is where "refreshes itself" stops being a slogan and starts depending on a real payload.

The portable lesson: bind to the actual response, not the declared schema. A declared schema is a promise. The wire is the truth, and they disagree more often than anyone admits.

A production-verified example makes it concrete. SerpAPI's \`amazon_search\` returns items under \`organic_results[]\`, each carrying \`title\`, \`thumbnail\`, \`price\`, \`extracted_price\`, \`rating\`, \`reviews\`, \`badges\`, \`sponsored\`, and \`delivery[]\`. What it does not carry is a \`prime\` boolean or a \`brand\` field. To know whether an item ships Prime, you match \`/prime/i\` against the \`delivery[]\` array, not a \`prime\` field that does not exist (\`AGENTS.md:371\`). Meanwhile the catalog's declared \`outputSchema\` optimistically lists a boolean \`prime\` (\`serpapi.json:8879\`), a \`brand\` (\`serpapi.json:8849\`), and \`delivery\` as an object (\`serpapi.json:8889\`). The live payload contradicts all three. Read what arrives.

There is a second reason the read node cannot be trusted blindly. An HTTP Request node treats a 404 or 500 as a node-level success. Only a transport error fails the node (\`nodes.md:66\`). So the normalize step that follows must defend against a body-shaped error, an error delivered inside a 200. Do not assume a node failure will catch it, because it will not.

## Reshape: the code node, and the two traps that make it look empty

Node 3 is a \`core:code\` node that flattens the raw response into the shape everything downstream needs: \`{sku, price, currency, seen_at}\`. It takes exactly three parameters: \`code\`, \`language\`, and \`timeoutSeconds\`. There is no \`input_mapping\`. Languages are \`javascript\`, \`python\`, \`typescript\`, and \`bash\`, and \`timeoutSeconds\` clamps to the range 1 to 120, defaulting to 10 (\`CodeNode.java:67-70\`, \`:170-177\`).

Because \`amazon_search\` items carry no \`sku\` and no \`currency\` field, normalize derives them: \`sku\` from the product identifier (the \`asin\`, or parsed out of the product link), and \`currency\` as a constant for a single-marketplace watch, since neither is a first-class field in the response. This is also where the body-shaped-error guard lives: inspect the 200 body for an error key and confirm the array before you map. The WRONG version reads \`organic_results\` straight and lets an error body flow downstream; the CORRECT version fails loudly first:

\`\`\`
const res = $input.fetch_listings && $input.fetch_listings.data;
if (!res || res.error || !Array.isArray(res.organic_results)) {
  throw new Error("bad body: " + JSON.stringify($input).slice(0, 300));
}
const top = res.organic_results[0];
$output = {
  sku: top.asin,
  price: top.extracted_price,
  currency: "USD",
  seen_at: new Date().toISOString()
};
\`\`\`

Because normalize picks \`organic_results[0]\`, \`$output\` is a single object, not an array. That matters: an array-shaped normalize output would make the single-value template \`{{core:normalize.output.result.sku}}\` resolve to nothing, the guard's \`find_rows\` value would be empty, \`item_count\` would read 0 every run, and a blank-SKU row would be inserted hourly with the idempotent guard silently defeated. Keep normalize emitting one object; if you ever need to fan out over many listings, that is a \`core:split\` node, not a bare array return.

Two traps make a code node look empty with no error at all, which is the worst kind of failure because there is nothing in the log to chase.

Portable trap one is input shape. Upstream data does not arrive at the root of your input object. It arrives keyed by the predecessor node's label. On this engine the JavaScript wrapper injects \`const $input = JSON.parse(...)\` and \`let $output = undefined\` (\`CodeNode.java:180-190\`), and each upstream step's output is placed under its own label key with its envelope stripped (\`CodeNode.java:300-319\`; \`OutputUnwrapper.java:178-186\`). So you read the fetch output as \`$input.fetch_listings.data.organic_results\`, or \`$input['core:fetch_listings']\` if you prefer bracket access. You never read \`$input.organic_results\`, which is undefined. You assign your result to \`$output\`, and it is captured through a \`__RESULT__\` stdout prefix and JSON-parsed back (\`CodeNode.java:180-190\`). Python uses \`_input\` and \`_output\`, bash uses \`INPUT\` and \`OUTPUT\`.

Portable trap two is output nesting. Many engines wrap what you return inside an envelope of their own. Here, the engine wraps your \`$output\` object under an extra \`result\` key (\`CodeNode.java:130-137\`, \`result.put("result", parsedResult)\`; \`CodeNodeSpec.java:22-26\`). Downstream you must drill past it:

\`\`\`
{{core:normalize.output.result.sku}}
\`\`\`

And to map the whole normalized object into a downstream parameter, you point at \`.result\`:

\`\`\`
{"result":"{{core:normalize.output.result}}"}
\`\`\`

Get the nesting wrong and you get a silent double \`result.result\` and an empty read, never an error (\`AGENTS.md\` Interface System note).

One supporting mechanic explains why the whole-object map above must be a lone template. A pure single \`{{...}}\` returns the typed value, a Number, a Map, or a List. The same expression embedded in surrounding prose coerces to a String, with Maps auto-encoded as JSON (\`expressions.md:72-74\`). Object-typed parameters therefore have to be a single template, never stitched into text.

## The correct-versus-wrong table nobody else has

Each row states the general trap in plain words; the exact wrong and correct strings for this engine are fenced immediately below, so the one-token difference is legible without cramming a long template into a table cell.

| Node / operation | General trap (portable) | Cited to |
|---|---|---|
| Code-node field read | Returned object sits under an envelope | \`CodeNode.java:130-137\` |
| Code-node whole-object map | Envelope must be included when mapping the whole object | \`AGENTS.md\` GOTCHA |
| Code-node input read | Input is keyed by predecessor label, not root | \`CodeNode.java:300-319\` |
| Table where column | Column is the bare stored name | \`CrudRepository.java:369-372\` |
| Numeric threshold | A filter that looks numeric may compare as text | \`CrudRepository.java:378-416\` |
| Build an object param | Some transforms stringify objects | \`AGENTS.md\` finding #2 |

Code-node field read:

\`\`\`
WRONG:   {{core:normalize.output.sku}}
CORRECT: {{core:normalize.output.result.sku}}
\`\`\`

Code-node whole-object map:

\`\`\`
WRONG:   {"result":"{{core:normalize.output}}"}
CORRECT: {"result":"{{core:normalize.output.result}}"}
\`\`\`

Code-node input read:

\`\`\`
WRONG:   $input.organic_results
CORRECT: $input.fetch_listings.data.organic_results
\`\`\`

Table where column:

\`\`\`
WRONG:   {column:'data.sku', operator:'=', value:'ABC-123'}
CORRECT: {column:'sku', operator:'=', value:'ABC-123'}
\`\`\`

Numeric threshold (do the math in a \`core:decision\`, not the query):

\`\`\`
WRONG:   {column:'price', operator:'>', value:9}
CORRECT: compare in core:decision (SpEL, numeric)
\`\`\`

Build an object param:

\`\`\`
WRONG:   assemble the object in a core:transform mapping
CORRECT: assemble it in a core:code node ($output keeps JSON types)
\`\`\`

The transform row burns people who never suspect it. A \`core:transform\` node stringifies object values. An object you assemble inside a transform expression reaches a downstream object-typed tool parameter as a String, producing a provider error like \`expected map, actual string\` (\`AGENTS.md\` workflow-builder finding #2). Object-typed values must be built in a \`core:code\` node instead, where \`$output\` fields keep their real JSON types through the whole-value template.

The table where-column row is worth internalizing too. User data lives in a single JSONB \`data\` column, and the where column is the bare name. A leading \`data.\` prefix is auto-stripped both at build time and at runtime, and a dotted column is otherwise rejected by the sanitizer, so the strip is mandatory rather than cosmetic (\`CrudRepository.java:369-372\`; \`SqlSanitizer.java:46\`). The reserved name \`id\` maps to the row primary key via \`id::text\`, not a JSONB field.

## Decide: where the comparison actually happens

Node 5 is the decision layer, and it hides the single most surprising mechanic in the build.

The portable trap: a filter that looks numeric may compare as text, and text ordering is not number ordering. On this engine, table CRUD where-clauses compare everything as text. Stored columns are read via \`jsonb_extract_path_text(data, :col)\`, the primary key via \`id::text\`, and the bound value passes through \`.toString()\` (\`CrudRepository.java:378-416\`). Meanwhile the SpEL comparison inside a decision condition is numeric (\`expressions.md:96\`). Same-looking \`>\` operator, two different worlds.

| Where the comparison runs | Comparison type | Reliable operators | Operators that mislead | Cited to |
|---|---|---|---|---|
| Table CRUD where-clause | Textual / lexicographic | \`=\`, \`!=\`, \`IN\`, \`IS NULL\`, \`IS NOT NULL\`, \`LIKE\` | \`>\`, \`<\`, \`>=\`, \`<=\` | \`CrudRepository.java:378-416\` |
| \`core:decision\` (SpEL) | Numeric | all comparison operators | none for numbers | \`expressions.md:96\` |

The consequence is a real latent bug. In a where-clause, \`amount > 9\` excludes \`'100'\`, because \`'1'\` sorts before \`'9'\`. And \`id > 5\` silently skips ids 10 through 99 (\`WorkflowBuilderHelpModule.java:258-262\`). Ordering operators are safe in a where-clause only when lexical order happens to match intent, which means zero-padded strings or ISO dates in \`yyyy-MM-dd\` form (\`WorkflowBuilderHelpModule.java:262\`). There is no numeric-cast ordering operator to reach for; a numeric-aware comparison is a known but unshipped fix at time of writing.

So the "did the price move more than 5%" math belongs in node 6b, a \`core:decision\`, not in the query. It needs the prior price, which lives in the \`find_rows\` result: \`find_rows\` returns \`items[]\`, and each matched row exposes its flattened fields, so the baseline price is at \`items[0].price\` (\`ConceptsHelpProvider.java:281\`; array indexing per \`expressions.md:28-32\`). Because the stored value came back through the same text path as every JSONB read, arithmetic must cast it: wrap both operands in \`double()\` before subtracting. The condition:

\`\`\`
{{ (double(core:normalize.output.result.price) - double(table:baseline_lookup.output.items[0].price)) / double(table:baseline_lookup.output.items[0].price) > 0.05 }}
\`\`\`

A decision activates exactly one branch. The first true condition wins, and the rest become SKIPPED. Its ports are \`if\`, \`elseif_N\`, and \`else\` (\`nodes.md:29\`; \`WORKFLOW_NODE_TYPES.md:411-418\`).

One structural rule ties the graph together. Edges are plain \`{from, to}\` records with an optional \`:port\` suffix, and branch conditions never live on the edge. They live in the \`cores[]\` node, as \`decisionConditions\` or \`switchCases\` (\`WORKFLOW_NODE_TYPES.md:33-40\`, \`:349-361\`). Two consequences follow from edge topology alone. Multiple unconditioned edges out of one source form an implicit Fork, running all branches in parallel. Multiple edges into one node form an implicit AND-merge that waits for every predecessor to resolve, whether COMPLETED or SKIPPED (\`WORKFLOW_NODE_TYPES.md:1008-1010\`, \`:1053-1056\`, \`:925-940\`).

## The idempotent-write guard, drawn as a real sub-graph

A self-refreshing trigger fires the same read hourly. Without a guard, it inserts the same SKU's baseline every hour, and the table fills with duplicates. The general pattern that fixes this on any engine: find first, decide on the count, then write only when the item is new. Never insert unconditionally when the same item can be re-fetched.

This engine has no upsert and no truncate operation, which is precisely why the guard is mandatory rather than optional (\`tables.md:49\`; \`CrudRepository.java\` \`deleteRows\` requires a validated where).

| Step | Node | Branch / port taken | Effect on the table | Cited to |
|---|---|---|---|---|
| 1 | \`find_rows\` by \`sku\` | (feeds the decision) | reads, writes nothing | \`ConceptsHelpProvider.java:281\` |
| 2 | \`core:decision\` on item_count | \`if\` (true) = never seen | none yet | \`WorkflowBuilderHelpModule.java:252-254\` |
| 3a | \`insert_row\` (baseline) | on the \`if\` branch | one new row written | \`tables.md:52\` |
| 3b | material-change decision | on the \`else\` branch | none yet | \`nodes.md:29\` |
| 4 | \`update_row\` (after approval) | approved port | named JSONB keys merged | \`tables.md:49\` |

The guard's two exact strings, fenced so the templates stay whole:

\`\`\`
find_rows {column:'sku', operator:'=', value:'{{core:normalize.output.result.sku}}'}
\`\`\`

\`\`\`
{{table:baseline_lookup.output.item_count == 0}}
\`\`\`

The probe that makes this work is \`find_rows\`, which exposes \`items[]\` (the found rows) and \`item_count\` (the count). An \`item_count\` of 0 is the "not yet processed" signal that turns the table into shared memory across runs (\`ConceptsHelpProvider.java:281\`). The find-then-decide guard is what makes a refreshing workflow safe (\`AGENTS.md\` \`dedupe_idempotent_write\`).

The write on the known-SKU path is an \`update_row\`, which requires both a where and a non-empty set map, and merges only the named JSONB keys through \`data || jsonb_build_object\` (\`tables.md:49\`). It is a partial merge, not a replace, so it will not null out fields you omit.

One tenant gotcha will waste an afternoon if you do not know it. The MCP \`table\` tool runs under the chat user's tenant, not the workflow owner's. Every CRUD query is scoped with \`AND tenant_id = :tenant_id\`, so the tool can show 0 rows while the workflow's own \`find_rows\` sees the real data (\`AGENTS.md\`). To inspect or wipe a workflow-owned table, run the operation from inside that workflow, in the correct tenant.

## Gate, then act

Node 7 is the human check before the irreversible step. The general principle: put a blocking gate before any action you cannot undo, and make it deterministic about what happens next.

On this engine the gate is a \`USER_APPROVAL\` signal. The node yields AWAITING_SIGNAL and the run pauses. USER_APPROVAL is always blocking, unlike an interface signal, which blocks only when \`__continue\` is mapped (\`EXECUTION_ENGINE.md:15\`; \`INTERFACE_NODE_GUIDE.md:783-787\`). The node has three named resume ports, \`approved\`, \`rejected\`, and \`timeout\`, and it routes deterministically by the decision made (\`nodes.md:39\`; \`WorkflowHelpProvider.java:665\`). The default timeout is 24 hours when unset (\`nodes.md:39\`).

Because a refresh fires hourly, two questions matter. First, what happens if the approval is fired twice? Nothing bad. Resolution is claim-before-process: \`resolveSignal()\` returns false on an already-resolved signal, so a re-fired approval never double-advances the DAG (\`INTERFACE_NODE_GUIDE.md:1008\`). Second, what happens to the next scheduled fire while a human sits on the decision? Each fire opens a new epoch, prior epoch results persist and stay browsable, and a blocking signal defers the trigger-cycle reset until it resolves (\`EXECUTION_ENGINE.md:15\`). The refresh does not stampede over a pending decision.

On the \`approved\` port, the real action fires. That can be a first-class Send Email node or any connected \`mcp:\` integration (\`nodes.md:62\`), followed by the guarded \`update_row\`. On the \`rejected\` and \`timeout\` ports, nothing is written and nothing is sent.

## Prove every branch before you call it live

The testing rule is not negotiable: exercise every branch against a live orchestrator and tail the service log in parallel. A green response with a stacktrace in the log is a failure, not a pass (\`AGENTS.md\` Feature Development Flow step 4). "It returned 200" is not evidence the branch worked.

| Scenario | Trigger condition | Expected branch / signal | Pass assertion | Fail signal |
|---|---|---|---|---|
| New-SKU insert | SKU with no baseline row | \`if\` branch, \`insert_row\` | exactly one row inserted | duplicate row, or stacktrace in log |
| No-change | Known SKU, price within 5% | material decision \`else\` | no flag, no approval, no alert | any alert or pause |
| Material change | Known SKU, move above 5% | run PAUSES at AWAITING_SIGNAL | status AWAITING_SIGNAL USER_APPROVAL | run completes without pausing |
| Approval ports | Resolve each of the three ports | approved / rejected / timeout | approved writes + alerts; others do neither | write on rejected/timeout |
| Re-run idempotency | Fire the schedule twice | guard blocks the second insert | row count stable | row count grows |

Run all five before you trust the graph. The material-change scenario should visibly pause; if it completes, your threshold math is in the wrong layer, probably a lexicographic where-clause pretending to be numeric.

Three lessons carry to any engine you build on next. Output nesting: drill to \`{{core:normalize.output.result.sku}}\`, never \`{{core:normalize.output.sku}}\`, because platforms wrap what you return. Textual comparison: compute the 5% move in a \`core:decision\`, not in the \`find_rows\` where-clause, because that comparison is lexical. Stringified objects: build typed values in a \`core:code\` node, not a \`core:transform\` that flattens them to strings. And the find-then-decide guard is the pattern that makes a self-refreshing workflow safe anywhere, because a schedule that acts is only as trustworthy as its defense against repeating itself.
`;

export default content;
