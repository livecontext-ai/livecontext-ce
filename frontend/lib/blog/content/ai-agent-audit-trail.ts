// Markdown body for "The agent audit trail: a field schema you can copy". Plain
// string module (see the-niche-data-advantage.ts for the rationale).
//
// The schema half of a pair; the retention/law/storage half is
// ai-agent-audit-log-retention.ts. They cross-reference each other, keep both
// links working. The cost model (quadratic context) and the stop-reason
// taxonomy are deliberately NOT re-derived here: they live in
// workflow-beats-do-everything-agent.ts and cap-ai-agent-cost-budgets.ts.
//
// Legal facts (AI Act Art. 12 scope, the "no instrument specifies a schema"
// finding) and the OpenTelemetry GenAI content-capture defaults were verified
// against primary sources on 2026-07-23. OTel gen_ai.* attributes were all
// Development status (0 Stable) at that date, so treat any attribute name here
// as unstable and re-verify before pinning a schema to it.
//
// Product figures are real column names and migration ids from this repo
// (AgentObservabilityService, the V-prefixed Flyway migrations named inline).
const content = `An audit trail is not a longer log. It is a different artifact, with a different reader, a different write contract, and a different clock. This piece publishes a copyable run-level and step-level schema in which every field carries four things at once: its data type, its cardinality class, whether it can hold personal data, and the reason it exists. A companion article does the storage arithmetic that turns retention tiering into a derived decision, maps the actual legal obligations, and handles the deletion request that collides with a trail kept for years.

The reference implementation quoted throughout is this blog's own platform. Real column names, real migrations, real bugs.

## The reader you are writing for is not you, and not now

A dashboard is read by its author, within minutes, with the incident still in working memory. A trail is read by an indifferent or hostile third party, months later, who cannot ask a follow-up question. That difference generates every decision below.

Two invariants follow, and almost nobody writes them down:

1. **Audit records are never sampled.**
2. **Content fields are never degraded inside their retention window.**

The rest is design judgment, and the storage cost of that judgment is arithmetic.

State the uncomfortable thing up front: **no instrument specifies this schema.** Outside EU AI Act Art. 12(3), which applies to exactly one Annex III sub-point (point 1(a), remote biometric identification, and not to biometric verification), nothing reviewed here (the AI Act, ISO/IEC 42001, NIST AI RMF, SOC 2) specifies a log schema, field types, cardinality limits or a sampling strategy. The schema below is engineering judgment aimed at satisfying the *purposes* the law names in Art. 12(2)(a) to (c) and the explainability right in Art. 86. It is not a compliance artifact and I will not sell it as one.

Every field in a usable trail carries four things at once: its data type and nullability, the question or obligation that forces it, its cardinality class, and its retention class including whether it may be sampled or degraded. No published source fills all four corners. [OpenTelemetry's GenAI conventions](https://github.com/open-telemetry/semantic-conventions-genai) have types but no obligations and no content by default; [ARMO's minimum-viable audit trail](https://www.armosec.io/blog/minimum-viable-audit-trail/) has obligations and field names but no types; the AI Act cluster has the law and concedes it specifies no fields.

Two anti-overlap notes. The trail is **linear in steps, not quadratic**: you pay the model to re-send accumulated context each turn but store each message once, so a six-step run is ~27 rows regardless of context growth (the quadratic side belongs to the cost-model article). And \`stop_reason\` and \`terminal_category\` appear here purely as fields to record; the taxonomy and cap behaviour belong to the budget-enforcement article.

## An observability dashboard is not an audit trail

The conflation splits cleanly by article title: observability-titled pieces sell traces as the audit trail; audit-titled pieces rarely mention that the standard schema records no content by default.

That default is the headline finding. The GenAI semantic conventions make prompts, completions, system instructions, tool arguments and tool results all requirement level \`Opt-In\`, and the spec's position is that instrumentations "SHOULD NOT capture them by default", with option 1 being "[Default] Don't record instructions, inputs, or outputs." So "we have OTel tracing, therefore we have an audit trail" is false out of the box: what you have is model, token counts, latency and finish reason, none of the material that reconstructs a decision.

Turning it on is harder than it looks. In [opentelemetry-python-contrib](https://github.com/open-telemetry/opentelemetry-python-contrib/blob/main/util/opentelemetry-util-genai/src/opentelemetry/util/genai/utils.py), the capture switch is not a boolean:

\`\`\`
OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT
  = NO_CONTENT | SPAN_ONLY | EVENT_ONLY | SPAN_AND_EVENT
  unset            -> NO_CONTENT
  invalid value    -> warning, then NO_CONTENT

# second gate, barely documented:
OTEL_SEMCONV_STABILITY_OPT_IN must select the GenAI
experimental mode, or get_content_capturing_mode() raises.
\`\`\`

Setting the capture variable alone is not enough. (Verified for the Python contrib packages only; other language SDKs may differ in flag name, enum values, or whether the second gate exists at all.)

Meanwhile mainstream observability advice is audit-fatal in two independent ways. For high-volume features above ~1,000 requests/second, [reduce call-envelope sampling to 10-20% and reserve full token-level capture for explicit debug sessions](https://www.braintrust.dev/articles/llm-call-observability); and [scrub or mask content before it reaches the backend](https://mlflow.org/articles/setting-up-llm-observability-pipelines-in-2026/). A ten percent sample is useless when the decision you must defend sits in the ninety percent you dropped.

| Dimension | Observability dashboard | Audit trail |
|---|---|---|
| Consumer | The author, minutes later | An indifferent or hostile third party, months later |
| Read latency | Seconds to hours | Months to years |
| Sampling | Expected (10-20%, or tail-based) | Forbidden |
| Content default | Off (OTel GenAI content is Opt-In) | On, within its retention window |
| Write contract | Fire-and-forget, failure logged | Same-transaction, failure fails the operation |
| Ordering source | Timestamps, resampled | Writer-assigned sequence |
| Mutability | Mutable by design (reprocessing, dropped fields on backend upgrade) | Append-only, ideally hash-chained |
| Retention driver | How long a regression stays interesting (days) | An obligation or a dispute horizon (months to years) |
| Failure mode | You debug slower | You cannot answer the question |

The write contract is the cheapest thing to get wrong. This platform holds both stances, each correct for its artifact. The agent observability write is a fire-and-forget HTTP POST (\`AgentClient.recordObservability\`) whose failure is caught and logged at WARN as "non-critical": the run still bills and returns, the audit row is simply lost. The feature-flag audit (\`V173__flag_flip_audit.sql\`) states the opposite contract in its migration header: same transaction, no \`REQUIRES_NEW\`, no async, no \`AFTER_COMMIT\` listener (that would race a JVM kill), and if the audit insert throws, the flag is not flipped.

The consequence of the best-effort choice is the failure mode that looks fine until you need it: **trail coverage becomes correlated with system health**, so it thins out during exactly the incidents you will be asked to explain.

## The run-level schema

One row per run. This is the header an auditor reads first.

| Field | Type | Null | Cardinality | Personal data | Why it exists |
|---|---|---|---|---|---|
| \`run_id\` | uuid | no | high | no | Join key for every child row. Mint at **dispatch**, not at INSERT. |
| \`trail_seq\` | bigint (dedicated sequence) | no | high | no | Ordering that survives clock skew and same-millisecond writes. |
| \`prev_row_hmac\` | bytea(32) | yes | high | no | Tamper evidence: covers own content plus the previous row's HMAC. |
| \`tenant_id\`, \`organization_id\` | text / uuid | no | medium | indirect | Erasure and access-control scope key. |
| \`actor_subject_ref\` | text (pseudonymous token) | yes | high | **yes** | "Who asked." Resolves to identity only via a separately-held mapping. |
| \`parent_run_id\` | uuid | yes | high | no | Which run spawned this one. |
| \`caller_agent_id\` | uuid | yes | medium | no | Which agent spawned it. |
| \`depth\` | int2 | no | low | no | Cycle detection and tree ordering. |
| \`caller_tool_call_id\` | text | yes | high | no | The exact call in the parent that spawned the child. |
| \`trigger_source\` | enum | no | **low** | no | manual / chat / webhook / schedule / datasource / workflow / error. Decides whether a human is accountable for the run existing. |
| \`started_at\`, \`ended_at\` | timestamptz | no / yes | high | no | Two timestamps, not one plus a duration. |
| \`status\` | enum | no | low | no | The claim you will be asked to defend: did this run succeed. |
| \`stop_reason\` | text (raw enum string) | yes | low | no | Stored verbatim for forensics. |
| \`terminal_category\` | enum | yes | low | no | Materialised, not derived at read time. |
| \`billed_provider\`, \`billed_model\` | text | no | low | no | What you were charged for. |
| \`executed_provider\`, \`executed_model\` | text | yes | low | no | What actually ran. They can differ. |
| \`model_snapshot\` | jsonb (\`_v\` keyed) | yes | medium | no | Price list and model config frozen at execution start. |
| \`prompt_tokens\`, \`completion_tokens\`, \`cache_creation_tokens\`, \`cache_read_tokens\`, \`reasoning_tokens\` | int4 x5 | no (default 0) | high | no | Five counters, not one total: they price differently. |
| \`cost_settled\` | numeric(15,4) | yes | high | no | The amount actually charged, materialised at write time. |
| \`system_prompt_hash\` | bytea(32) | yes | high | no | Reference, never the text. |
| \`build_sha\` | text(40) | yes | low | no | Did this run predate the fix. |
| \`config_snapshot\` | jsonb | yes | medium | maybe | Policy in force, including whether approval was required. |
| \`approval_ref\` | uuid | **yes** | high | no | NULL means "no approval required by the policy in force". |
| \`iteration_count\`, \`tool_call_count\` | int4 | no | high | no | Shape of the run without reading its steps. |

Eleven of those need more than a sentence.

**Mint \`run_id\` at dispatch.** A real bug: MCP-side task-claim rows were written before the execution row existed, so a Hibernate-generated id left \`task_id\` silently NULL. The fix passes an explicit execution id through the dispatch call and uses it as the primary key (\`AgentObservabilityRequest.executionId\`, documented in-code as "stable correlation ID minted at dispatch").

**The sub-agent call tree needs four fields, not one:** parent run, caller agent, depth, and the exact tool call in the parent. Drop any one and a multi-agent run reads as an unorderable flat pile.

**Two timestamps, not one plus a duration.** A duration cannot be reconciled against an external event timeline. This is also the only field shape the AI Act itself names: Art. 12(3)(a) requires "recording of the period of each use of the system (start date and time and end date and time of each use)".

**Billed and executed model can differ.** A routing layer can send a billed \`(provider, model)\` pair to a different execution target while preserving the billed identity on the response (\`V365__create_model_execution_links.sql\`). A trail recording only one is wrong about what produced the output.

**\`model_snapshot\`** freezes the price list at execution start:

\`\`\`json
{
  "_v": 1,
  "provider": "anthropic",
  "model_id": "claude-opus-4-8",
  "price_input": 5.0,
  "price_output": 25.0,
  "credits_input": 1.0,
  "credits_output": 5.0,
  "canonical_id": "anthropic/claude-opus-4-8",
  "bundle_version": 41,
  "markup": 1.2,
  "captured_at": "2026-07-22T09:14:03Z"
}
\`\`\`

Roughly 260 bytes, about 905 MB/year at 10k runs/day, about a dollar a year of block storage. It exists so cost survives mid-run model deprecation and retroactive price edits, and it is the field engineers cut first and regret hardest.

**\`cost_settled\` is materialised at write time.** Recomputing from tokens times price at read time is the *fallback* \`model_snapshot\` enables, not the record; any later divergence is itself a finding.

**\`terminal_category\` is stored materialised even though it is derivable** from \`stop_reason\`, currently by generated contract code (\`AgentStopReason.valueOfOrError(x).terminal()\`). Codegen changes; a trail readable in seven years cannot depend on this month's build, or old rows silently re-classify themselves.

**\`build_sha\`** (~40 bytes) is the field most often missing and most often needed. Trap: \`.git\` is usually not in the Docker build context, so the running version reports a static placeholder unless the commit is passed as a build arg.

**Never store the system prompt text per run.** At 10k runs/day a 6 KB system prompt is 20.89 GB/year of pure duplication, and this platform stores it up to three times per run (the \`agent_executions.system_prompt TEXT\` column, a copy in \`agent_config_snapshot\` JSONB, and again as a SYSTEM-role row in \`agent_execution_messages\`), so 20.89 GB/year is the floor, not the total. Store each distinct prompt once per version, reference by hash. It is not the largest avoidable line item, though: the duplicated tool-result store (quantified in the companion article on retention) is 83.55 GB/year, four times larger. Those two, 83.55 GB/year of tool results then 20.89 GB/year of system prompts, are the only avoidable items above 10 GB/year in this model.

**\`trail_seq\` comes from a dedicated sequence, not \`created_at\`.** It survives clock skew, a restore into another timezone, and two rows written in the same millisecond. Gaps are acceptable and should be documented as such; monotonicity is the asserted property. \`V169__trigger_lifecycle_invariants.sql\` shows the pattern: it orders history by \`(trigger_id, trigger_type, seq DESC)\` and keeps a \`created_at DESC\` index only for the time-window ops query.

**\`prev_row_hmac\` is the boundary** between an observability log and an audit trail. Each row's HMAC covers its own content plus the previous row's, so a silent edit or deletion breaks the chain. This platform's \`V195__create_organization_audit_event.sql\` header lists it as deliberately omitted from that MVP, alongside a retention purge under a distributed lock, a WORM mirror, and append-only role separation. That list doubles as a maturity checklist.

## The step-level schema

One row per LLM turn, tool call, decision or signal. Step rows outnumber run rows roughly 26 to 1 and carry all the payload, so their retention and personal-data profile is entirely different.

| Field | Type | Null | Cardinality | Personal data | Why it exists |
|---|---|---|---|---|---|
| \`run_id\` | uuid | no | high | no | Parent join key. |
| \`tenant_id\`, \`organization_id\` | text / uuid | no | medium | indirect | On **every** child row, for org-scoped erasure. |
| \`step_seq\` | int4 (writer-assigned) | no | high | no | Deterministic order. Never derived from \`created_at\`. |
| \`iteration_seq\` | int4 (writer-assigned) | no | medium | no | Which LLM turn this belongs to. |
| \`parallel_index\` | int2 | **yes** | low | no | NULL means sequential. Distinguishes a concurrent batch from a causal chain. |
| \`step_kind\` | enum | no | low | no | llm_turn / tool_call / decision / signal / message. |
| \`tool_name\` | text | yes | **low** | no | The GROUP BY for "what does this agent actually do". |
| \`tool_call_id\` | text | yes | high | no | Correlates request with result across retries and reorders. |
| \`args_digest\` | bytea(32) | yes | high | no* | Prove or disprove a produced payload without retaining it. |
| \`result_digest\` | bytea(32) | yes | high | no* | Same, for results. |
| \`content_length\` | int4 | yes | high | no | How big the payload **was**, retained after it is gone. |
| \`payload_ref\` | uuid | yes | high | pointer only | Offloaded blob above the inline threshold. |
| \`content\` | text | yes | high | **yes** | Inline payload, on the short clock. |
| \`error_code\` | enum | yes | low | no | Machine-readable failure class. Full window. |
| \`error_message\` | text | yes | high | **yes** | Free text. Payload clock. |
| \`branch_taken\` | text (port label) | yes | low | no | Which outgoing edge the run followed. |
| \`skip_reason\` | text | yes | low | no | Why a node did **not** run. |
| \`skip_source_node\` | text | yes | medium | no | Which upstream decision skipped it. |
| \`redaction_applied\` | int2 (bitmask) | no | low | no | Which redaction rules fired. |
| \`prompt_tokens\`, \`completion_tokens\`, ... | int4 | **yes** | high | no | Written only when non-zero, so NULL keeps its meaning. |
| \`duration_ms\` | int8 | yes | high | no | Attributes a run-level timeout to the step that consumed the budget. |

\\* A digest is not personal data only when the payload space is not enumerable (see the caveat below).

The five token counters are NOT NULL default 0 on the run header (a run always has a total) but nullable on step rows, where NULL means "not applicable" (a tool-call row has no tokens), not zero. Sum steps against the header with that rule in mind, or the two disagree.

**\`parallel_index\` costs four bytes** and prevents the worst trail failure: reconstructing a causal chain out of a parallel batch, which is worse than a gap because it is confidently wrong.

**\`args_digest\` and \`result_digest\` are the pivot of the retention design.** 32 B per digest; the 6 tool-call rows carry two, the 14 message rows carry one, so 832 bytes per run, 2.83 GB/year at 10k runs/day. Keep the digest for the full obligation window, the payload on a short clock: when someone produces a document and claims the agent saw it, the digest proves or disproves it with zero payload retained.

The caveat, plainly: **for a small enumerable input space (a postcode, a date of birth) the digest is re-identifiable** and must be salted with a separately-held key. The rule is "never publish an unsalted digest of a low-entropy field", not "digests are non-personal". The [EDPB pseudonymisation guidelines](https://www.edpb.europa.eu/system/files/2025-01/edpb_guidelines_202501_pseudonymisation_en.pdf) hold that simple hashing without domain separation and access control is insufficient (January 2025 consultation draft).

**\`content_length\` is set unconditionally before the decision to inline, offload or truncate**, which is what tells a future reader that truncation happened and how much they are not seeing (\`AgentObservabilityService\`, \`CONTENT_INLINE_THRESHOLD = 8192\`):

\`\`\`
length = content.length()          # set FIRST, always
if length > 8192:
    id = storage.saveText(content) # payload_ref
    content = content[:500] + "...[truncated]"
else:
    keep inline
# if the offload throws: fall back to an inline prefix
# with NO storage id, which MUST be distinguishable
# from a successful offload.
\`\`\`

**Split \`error_code\` from \`error_message\`.** Free-text messages are unqueryable, unstable across library upgrades, and routinely echo the input that caused the failure, making them the highest-risk personal-data field in the trail while looking like diagnostics. The code retains for the full window; the message goes on the payload clock.

**\`branch_taken\` makes the trail replayable on paper** rather than by re-execution; in a workflow engine the ports are a closed low-cardinality set per node kind (\`if\` / \`else\` / \`elseif_N\`, \`case_N\` / \`default\`, \`body\` / \`iterate\` / \`exit\`, \`branch_N\`). Record why a node did **not** run too: \`skip_reason\` plus \`skip_source_node\` make the negative a first-class fact, so a skipped branch is distinguishable from one never reached.

**\`redaction_applied\` is two bytes** separating three states a bare trail conflates: payload clean, payload redacted, or redactor disabled. Without it a clean-looking trail is evidentially worthless. This platform's \`ToolCallRedactor\` is two-layer (a secret-field-name regex plus a credential-tool allowlist that blanks the whole argument body) and persists no marker of which layer fired; that is the gap this field closes.

## The approval record is its own row, and its hardest field is what the human saw

Human-in-the-loop is the one thing the AI Act enumerates for the systems it covers, and the one thing OTel has no attribute for. Art. 12(3)(d) requires, for Annex III point 1(a) systems, "the identification of the natural persons involved in the verification of the results" referred to in Art. 14(5).

A usable approval record (this platform's \`orchestrator.workflow_signal_waits\`):

\`\`\`
signal_type, signal_config jsonb, status, resolution,
resolution_data jsonb, approval_context text,
expires_at, created_at, claimed_at, claimed_by,
resolved_at, resolved_by,
UNIQUE (run_id, node_id, item_id, epoch)

signal_config = { type, approverRoles, requiredApprovals,
                  timeoutMs, receivedApprovals, delegation,
                  continuationMode }
\`\`\`

**The field nobody records is what the approver actually saw.** \`approval_context\` is the node's context template rendered against the execution context **frozen at the moment of the pause**, persisted with the signal, then re-emitted verbatim into the resolved node output so it survives the awaiting-to-resolved transition (migration \`V373\`, which adds \`approval_context\` to the signal-wait table).

**\`approval_ref\` on the run row is nullable, and NULL must mean "no approval was required by the policy in force"**, a different fact from "approval status unknown". That requires the policy version to be recoverable from \`config_snapshot\`.

**Identity defaults must be visibly distinguishable from real identities.** Here \`resolved_by\` falls back to the literal \`"system"\` when null in the node output, and to \`"api"\` when the upstream user header is absent. Fine, as long as no human can ever be named \`api\`.

**Sizing an identity column is an audit concern.** \`resolved_by\` was \`VARCHAR(100)\` until federated identifiers of the form \`b:org:user\` (~120 chars) overflowed it, rolling back the resolve transaction and leaving approvals stuck in \`CLAIMED\` forever, indistinguishable from genuinely pending ones (\`V191__signal_waits_widen_resolved_by.sql\`).

**Delegated approvals need their own delivery ledger.** \`orchestrator.approval_channel_deliveries\`: a single-use callback token (\`VARCHAR(64) UNIQUE\`), status (\`PENDING\`, \`SENT\`, \`FAILED\`, \`RESOLVED\`, \`CANCELLED\`), the message text actually sent, an allowed-user allowlist, and \`UNIQUE (signal_wait_id, channel)\` as the replay guard. Identity is then a namespaced string such as \`telegram:<fromId>\`.

**Recorded intent is not enforced control, and the trail should not imply otherwise.** Here \`approverRoles\` is recorded in the signal config and displayed to the approver, but the in-app resolve endpoint enforces only run scope, not role membership. If your trail records a role it did not check, say so in the field's documentation.
`;

export default content;
