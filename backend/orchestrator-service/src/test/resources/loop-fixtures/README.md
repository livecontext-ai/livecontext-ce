# Loop-Equivalence Harness Fixtures

This directory holds the canonical fixture set consumed by the `EquivalenceHarness`
introduced in P1.1 of the Execution Kernel & Scaling roadmap
(`the project docs` §7.4).

**Status: P1.0 - fixtures committed without `expected_trace.jsonl` (see "Format spec" below).**

## Purpose

Each fixture is a frozen workflow shape whose execution trace MUST be byte-equivalent
between the legacy `executeReadyNodesLoop` and the new `WorkflowExecutionLoop`. The harness:

1. Loads `workflow_plan.json` and `trigger_input.json`.
2. Runs both loops against the same fixture input.
3. Canonicalizes both `StateSnapshot` mutation streams per §7.4 ("Canonicalization protocol").
4. Asserts the SHA-256 hashes of the two traces match (modulo the documented commutative-pair
   tolerance, §7.4 "Equivalence definition" rule 3).

The CI daily diff job (§7.4 "Daily diff job") replays all fixtures and reports
`divergence_count`. Any non-zero count blocks promotion of the new loop.

## Inventory

| # | Name | Pattern under test | Why it matters |
|---|------|--------------------|----------------|
| 01 | `01_simple_chain` | trigger → mcp → mcp → mcp | Linear baseline; smallest possible diff. |
| 02 | `02_split_classify_apply` | trigger → mcp → core:split → agent:classify → mcp → core:merge | Split + per-item classify + merge - the prevalent prod shape. |
| 03 | `03_signal_resume_user_approval` | trigger → mcp → interface (`__continue` blocking) → mcp | Signal-resume code path with user approval. |
| 04 | `04_partial_failure_split_async` | split (30 items) → async classify (some fail) → apply → merge | Canonical partial-failure-split-async fixture (memory: `project_partial_failure_split_async_fix`). |
| 05 | `05_branching_decision_else` | trigger → mcp → core:decision (`if` + `else`) → mcp → mcp → core:merge | Decision branch routing; only one branch fires per run. |
| 06 | `06_loop_with_exit` | trigger → mcp → core:loop (body / iterate / exit) → mcp | Loop with explicit exit port; body iterates 3 times. |
| 07 | `07_parallel_epoch_reset` | trigger:schedule (repeat=true) → mcp; 3 fire events | Trigger epoch cycling - exercises `Parallel Epoch Deferred Reset` fix (memory key finding). |
| 08 | `08_single_node_sbs_rerun` | trigger → mcp; STEP_BY_STEP rerun via `StepByStepExecutor` | Single-node rerun via SBS rerun call site. |
| 09 | `09_workflow_run_controller_rerun` | trigger → mcp → core:split (5) → mcp → core:merge; rerun via `WorkflowRunController` | Controller-driven rerun (`WorkflowRunController.java:816`) - `ResultBackedReady` oracle. |
| 10 | `10_async_batch_complete_split` | trigger → mcp → core:split (10) → agent (async batch) → mcp → core:merge | Per-item barrier in async batch path. |
| 11 | `11_mid_run_handoff` | **Deferred to P1.5** - cross-version test (§7.7). NOT committed in P1.0. | Validates rolling-deploy crossover during the loop migration soak. |

## Format spec

Each numbered subdirectory contains:

```
NN_name/
├── workflow_plan.json     # WorkflowPlan JSON consumed by the orchestrator (see the project docs)
├── trigger_input.json     # Trigger payload (shape depends on trigger type)
└── expected_trace.jsonl   # **NOT COMMITTED IN P1.0** - pinned during P1.1 (see below)
```

### `workflow_plan.json`

Standard `WorkflowPlan` JSON: `{ id, triggers, mcps, agents, cores, tables, edges, notes,
interfaces, schedule? }`. Format reference: `the project docs`. Edges use the V2
`{ from, to }` format with port-suffixed refs (`core:label:if`, `core:label:body`, …) for
control-flow nodes per CLAUDE.md "Edge Format" and "Port System".

### `trigger_input.json`

The payload supplied to the trigger at fire time. Shape varies by trigger type:

| Trigger type | Shape |
|--------------|-------|
| `manual` | `{}` (empty object - manual triggers carry no payload) |
| `webhook` | The HTTP request body as a JSON object |
| `chat` | `{ "message": "<text>", "conversationId": "<uuid>" }` |
| `schedule` | `{ "fireAt": "<iso8601>" }`; for fixtures with multiple fires we use a wrapper `{ "fires": [ { "fireAt": "..." }, ... ] }` to encode the harness driving multiple cycles |
| `datasource` | `{ "row": { ...columns } }` |

Fixtures 08 and 09 add **harness-only directives** to `trigger_input.json` (`__rerun`,
`__rerunRequested`, `__rerunVia`) - these are NOT part of the runtime contract; the harness
parses them to drive the appropriate rerun call site after the initial fire completes. They
are documented inline in those fixtures' `trigger_input.json`.

### `expected_trace.jsonl`

**NOT COMMITTED IN P1.0.** This file is pinned at first run during P1.1 by the
`TraceRecorder` (ADR `docs/adr/0001_trace_recorder_api.md`) executing the fixture against
the **legacy** `executeReadyNodesLoop`. P1.0 ships the inputs only; P1.1 produces the golden
traces and commits them in a separate review (per §7.4 anti-rot policy below).

Once pinned, each line is one canonical mutation entry:

```json
{ "seq": <long>, "mutator": "<name>", "argsHash": "<sha256-hex>", "postStateHash": "<sha256-hex>" }
```

(Plus `runId` and `tenantId` for prod-recorded traces - fixture traces sentinelize both.)

## Anti-rot policy

Per §7.4 ("Anti-rot policy"):

- **Quarterly regeneration.** Every quarter, all `expected_trace.jsonl` files are regenerated
  under the current canonicalizer. The PR diffing the old and new traces requires
  **audit + workflow-team sign-off** before merging.
- **Out-of-cycle re-pinning** (e.g. when a planned canonicalizer fix is merged) requires the
  same audit + sign-off - fixture diffs are NEVER auto-applied.
- **Adding a new fixture** does not require regenerating the existing 10. New fixture is
  pinned independently; existing pins stand untouched.
- **Modifying a `workflow_plan.json` or `trigger_input.json`** in this directory invalidates
  that fixture's `expected_trace.jsonl` and triggers re-pinning + sign-off for that fixture
  alone.

## Notes on fixture authoring

- Labels follow `LabelNormalizer` rules: lowercase, transliterate accents, non-alphanumeric →
  `_`, collapse repeats. `LabelNormalizer.triggerKey("My Webhook")` → `trigger:my_webhook`
  (see CLAUDE.md "Label Normalization").
- All fixtures use **mock tool slugs** (`mock/...`) so the harness can run without external
  catalog dependencies. The `MockToolsGateway` returns deterministic stub outputs.
- Fixtures 04 and 10 reference async-agent behavior. Async is enabled at runtime via the
  `agentQueueEnabled` Spring property (see `ExecutionServiceInjector.java:186`); there is
  **no per-agent JSON field** for async - the harness flips the global flag for these two
  fixtures during replay. Documented inline in their `trigger_input.json` via the
  `__harness_directives.async_agents` key.
- Fixture 07 uses `params.cron` on the schedule trigger and a harness directive
  `__harness_directives.fires: [...]` to drive 3 distinct fire events through the
  `ReusableTriggerService` epoch-cycling code path.
- **Harness directive vocabulary** (the keys `HarnessDirectiveParser` must support across all
  fixtures):
  - `mock_*_output` - deterministic upstream output for an MCP step (fixtures 02/04/10).
  - `async_agents` - list of agent IDs to flip global `agentQueueEnabled` on (fixtures 04/10).
  - `mock_agent_backend` - stub config for `provider: anthropic-batch` (fixtures 04/10),
    including `kind` and `fail_item_indices`.
  - `interface_resume` - fixture 03's interface action click payload.
  - `fires` - list of `{ at, payload }` to drive `ReusableTriggerService.fire()` (fixture 07).
  - `execution_mode` - `STEP_BY_STEP` for fixture 08; default `AUTOMATIC`.
  - `rerun_via` + `rerun_node` - pick the rerun call site (fixtures 08 / 09).
  - `expected_iterations_observed` + `expected_loop_exits_at_iteration` - fixture 06's
    loop-counter contract pin (the harness asserts `LoopState.currentIndex` matches the
    expected sequence).
  - `comment` - free-text explanation, ignored at runtime.

## Running fixtures (P1.1 contract)

P1.1 ships the harness runner. The expected developer-facing entry point:

```bash
cd backend/orchestrator-service
mvn test -Dtest="com.apimarketplace.orchestrator.execution.v2.loop.EquivalenceHarnessTest"
```

The harness JUnit class (`EquivalenceHarnessTest`, P1.1 deliverable) iterates every numbered
subdirectory, deserializes `workflow_plan.json` + `trigger_input.json`, runs both legacy and
new loops via `WorkflowExecutionLoop` (with `KernelMode.LEGACY_INLINE_LOOP` and
`KernelMode.NEW_KERNEL_LOOP` respectively - both P1.1 enums, NOT yet committed in P1.0),
pipes both mutation streams through `TraceCanonicalizer`, and asserts SHA-256 hash equality
per §7.4.

**Components the harness depends on (all P1.1 deliverables):**

- `EquivalenceHarness` (Spring `@TestComponent`) - orchestrates the dual-run + diff.
- `HarnessDirectiveParser` - reads `__harness_directives` keys from `trigger_input.json` and
  configures `MockToolsGateway` (mock outputs for `mcp/*` tools), the global `agentQueueEnabled`
  flag (fixtures 04/10), and the `ReusableTriggerService` fire schedule (fixture 07).
- `TraceRecorder` injection (ADR 0001) - captures mutation traces from both runs.
- `TraceCanonicalizer` (§7.4) - normalizes wall-clock + iteration-order non-determinism.
- `MockToolsGateway` - already exists in test scaffolding; harness wires fixture-specific
  mock outputs on top.

For each fixture run, the harness produces:

- `target/loop-fixtures/<fixture-name>/legacy-trace.jsonl`
- `target/loop-fixtures/<fixture-name>/new-trace.jsonl`
- A SHA-256 hash of each, asserted equal.

`expected_trace.jsonl` is pinned by running the harness ONCE on green legacy code (P1.1
deliverable) and committing the legacy trace as the canonical reference. Subsequent runs
diff against this pinned baseline.

## Edge cases NOT covered in P1.0 (deferred)

The 10 fixtures cover the prevalent shapes called out in roadmap §7.4. The following edge
cases are deferred to later phases and explicitly NOT in P1.0:

| Edge case | Deferred to | Rationale |
|-----------|-------------|-----------|
| Empty input split (`maxItems` reached or list empty) | P1.4 | Async-path coverage primarily; cheap to add when fixture 04/10 are revisited. |
| Single-item split (N=1) | P1.4 | Boundary of per-item barrier. |
| Loop hits `maxIterations` cap with `strategy=continue-anyway` | P1.3a | Fixture 06 covers natural exit; cap-eviction is a separate signal-resume edge. |
| `WAIT_TIMER` signal that times out | P1.3a | Pairs with fixture 03 (happy-path approval); add when migrating SignalResumeService. |
| Phantom epoch failure mode | P1.3a | Fixture 07 covers success path; the phantom-epoch case (memory `Parallel Epoch Deferred Reset`) needs a contrived fire schedule. |
| Cross-version run (`11_mid_run_handoff`) | P1.5 | Already explicitly listed; requires JVM serialize/deserialize roundtrip per §7.7. |

These deferrals are intentional - adding them in P1.0 would inflate the fixture pin count
before the harness exists to pin them.

## See also

- `the project docs` §7.4 - full harness specification.
- `the project docs` §3.2 - full mutator enumeration the recorder must observe.
- `docs/adr/0001_trace_recorder_api.md` - recorder API (P1.0 ADR).
- `docs/adr/0002_trace_shape_registry.md` - diversity sampling (P1.0 ADR).
- `the project docs` - `WorkflowPlan` JSON contract.
- `CLAUDE.md` "Edge Format", "Port System", "Label Normalization".
