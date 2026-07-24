// Markdown body for "How long to keep an agent audit trail (and what you
// actually owe)". Plain string module (see the-niche-data-advantage.ts).
//
// The retention/law/storage half of a pair; the field-schema half is
// ai-agent-audit-trail.ts, which defines every field name priced here. Keep
// both cross-links working.
//
// LEGAL PRECISION IS LOad-BEARING. Every article number, retention period and
// scope statement was verified against the consolidated AI Act text and the
// EU AI Act service desk on 2026-07-23:
//   - Art. 12 record-keeping is HIGH-RISK ONLY; 12(3) field-level duties bind
//     ONLY Annex III point 1(a) (remote biometric ID).
//   - The 6-month log floor is Art. 19(1) (providers) and Art. 26(6)
//     (deployers), owed twice by two parties; 10 years is the Art. 18
//     DOCUMENTATION floor, a different regime.
//   - The high-risk application dates were deferred by the Digital Omnibus to
//     2027-12-02 / 2028-08-04; any source citing 2026-08-02 for high-risk is
//     stale. Confirm against the OJ text once published.
// The article's spine is that MOST readers are OUT OF SCOPE. Do not edit that
// into manufactured compliance urgency. Everything here is a MODEL, not a
// measurement; storage figures carry a "+10 to 25% in production" caveat.
//
// Product figures (WorkspaceDataPurger, PURGED_ORG_SCOPED_TABLES, the V-prefixed
// migrations) are real, from this repo.
const content = `A companion article publishes the run-level and step-level field schema this one prices: the field names, types and cardinality classes referenced in the tables below are defined there. This piece answers the three questions the schema leaves open. How many bytes does the trail actually cost? How long must each field be kept? And does any of it legally apply to you, which for most readers it does not.

The reference implementation quoted throughout is this blog's own platform: real column names, real migrations, real bugs.

## The arithmetic, so the tiering is derived and not asserted

Everything below is a **model**, not a measurement. Inputs stated so you can re-run it with your own numbers. Row sizes are analytic, derived from DDL column types plus documented Postgres overhead; real tables run roughly 10-25% larger once fillfactor, free space and bloat are included, so read every derived figure below as "+10 to 25% in production" (the flat seven-year full-capture figure, 1.68 TB in the model, is about 2.1 TB at the top of that range).

\`\`\`
Volume:  10,000 runs/day, 6 steps/run
Rows:    27/run = 1 run header + 6 iterations
                + 6 tool calls + 14 messages
Payload: 1500-token system prompt, 200-token user msg,
         250-token completions, 150 B tool arguments,
         4 KB mean tool result, 4 bytes/token
PG overhead/row: 23 B heap tuple header, MAXALIGNed to 24
                 + 4 B line pointer
                 + 8 B assumed null bitmap (1 bit/column,
                   present only when the row has NULLs;
                   8 B covers up to 64 columns) = 36 B
                 + ~16 B per btree index entry
\`\`\`

The metadata-only figure the rest of the model scales from is 9.05 KB/run, derived as:

\`\`\`
Worked row sizes (metadata only):
Run header (1 row):
  ~300 B column data (uuids, 3 timestamptz, 5 int4 token
   counters, 3 bytea(32) hashes, build_sha, enums, numerics)
  + 36 B tuple overhead + ~48 B (3 btree entries) = ~384 B
Step row (avg over 26):
  ~180 B column data + 36 B overhead + ~80 B index entries
  = ~335 B
Per run: 384 + 26 x 335 = ~9.05 KB
\`\`\`

| Capture level | Bytes/run | MB/day @10k runs | GB/year | GB over 7 years | Compressed GB/year |
|---|---|---|---|---|---|
| Metadata only | 9.05 KB | 88.38 | 31.50 | 220.51 | 31.50 (uncompressed in PG; archives well) |
| Metadata + digests (~832 B/run) | 9.86 KB | 96.29 | 34.33 | 240.31 | 34.33 |
| Full capture | 70.43 KB | 687.78 | 245.16 | 1,716 (1.68 TB) | 92.6-117 |

Full capture is 7.8x metadata-only. Compression assumes 2.5-3.5x on payloads above Postgres's ~2 kB (2048-byte) TOAST threshold, a typical published range rather than a measurement on this corpus, so the compressed full-capture figure spans 92.6 to 117 GB/year depending where in that range you land.

One input dominates the result:

| Mean tool result | KB/run (full capture) | GB/year @10k runs/day | Agent shape that lives here |
|---|---|---|---|
| 1 KB | 34.43 | 119.84 | Classification, routing, short API lookups |
| 4 KB | 70.43 | 245.16 | Mixed tool use, the model above |
| 8 KB | 118.43 | 412.24 | Document drafting, multi-record CRUD |
| 20 KB | 262.43 | 913.50 | Search, file-read, SQL-heavy agents |

Prompts and completions are 20% of payload at a 4 KB mean tool result (12.8 KB of 61.38 KB) and fall to about 5% at 20 KB (12.8 KB of 253.38 KB), so tool results are where tiering pays. **If you tier one thing, tier tool results.**

Now the inversion that motivates the whole section. 245 GB/year is about **$235/year** of gp3 block storage, **$68/year** on S3 Standard, **$12/year** on Glacier Instant Retrieval; metadata-only is about $30/year. (List us-east-1 order-of-magnitude figures, excluding request and retrieval charges; the cold tiers assume near-zero read volume.) **Nobody is cutting their trail to save $200.**

What the dollar figure hides is the real cost: **98.55 million rows/year** (689.85 million over seven years) of erasure surface, index maintenance and restore time, plus the fact that every retained byte of prompt and tool result is liability. Design the tiering around blast radius and row count.

At 1M runs/day the operational ceiling bites well before the storage bill: ~54M index inserts/day, 9.86 billion rows/year, 23.94 TB/year of full capture, and roughly 140 hours to logically restore one year at 50 MB/s. The skeleton tier is what keeps a trail *restorable*, not just affordable.

One free saving, found by reading the schema rather than the code: **the tool result is frequently persisted twice**, once as the tool-call row's content and again as the content of the corresponding tool-role message row. Store the payload once and have the message row carry the same \`payload_ref\`, and payload drops from 61.38 KB to 37.38 KB per run, 245.16 GB/year to 161.61 GB/year. Any trail with both a tool-call table and a message table has this shape. (The schema-level observation is solid; the exact overlap rate in production was not measured.)

## Retention tiers, each justified by the decision it supports

| Tier | Contents | Window | GB/year | Question it answers | Sampled or degraded? |
|---|---|---|---|---|---|
| 0 Skeleton | Run header minus all text; step metadata (\`step_seq\`, \`tool_name\`, \`branch_taken\`, status, \`stop_reason\`, durations, token counts, \`content_length\`, all digests) | Full obligation window (7 yr modelled) | 31.50 | Did this run happen, when, who triggered it, what did it do, which way did it branch, what did it cost | **Never** |
| 1 Digests and codes | \`args_digest\`, \`result_digest\`, \`error_code\`, \`redaction_applied\`, \`model_snapshot\` | 12-24 months | 34.33 | Prove or disprove that the agent saw a produced document; re-cost a disputed run at the prices in force | **Never** |
| 2 Tool args and results | \`content\`, \`payload_ref\` for tool steps | 30-90 days hot, then sampled | ~80% of payload bytes | Debug a live regression; answer a customer complaint | Yes, after the hot window |
| 3 Prompts and completions | Message content | 30 days, **plus 100% of failed or guardrail-tripped runs at any age** | see below | Reconstruct the reasoning of a disputed decision | Non-uniformly only |
| 4 Prompt templates | System prompts, prompt text by version | Forever (kilobytes) | ~0 | Which prompt version ran | Never on a per-run clock |

Tier 0 over seven years is 220.51 GB, about **$10.60/year** on Glacier Instant Retrieval (220.51 GB x $0.004/GB-month x 12). That answers most auditor questions while retaining zero bytes of personal data.

Tier 3's sampling rule is the one worth arguing, and the knob only ever touches tiers 2 and 3 (invariant 1: audit records are never sampled). At an assumed 8% failure rate, keeping all failures plus 5% of successes retains 12.6% of runs (0.08 + 0.92 x 0.05 = 0.126). Applied to the payload tiers alone (full capture minus the 31.50 skeleton and 2.83 digest tiers, i.e. 210.83 GB/year), that keeps 26.56 GB/year of payload; with tiers 0 and 1 held at 100%, resident full-detail falls from 245.16 to about **60.9 GB/year** (31.50 + 2.83 + 26.56), while keeping every run anyone will actually ask about. Uniform sampling optimises for the runs nobody investigates.

Combined plan, per tier:

\`\`\`
30 days full capture:   20.15 GB gp3           $19.34
365 days digests:       34.33 GB S3 Standard    $9.47
7 years skeleton:      220.51 GB Glacier IR    $10.58
resident total:        274.99 GB             ~ $39/year
\`\`\`

That is 274.99 GB resident versus 1.68 TB for flat full capture held seven years, a 6.2x reduction, roughly $39/year versus $1,647/year of flat gp3. The saving that matters is not the money: **only 30 days of personal-data payload is ever in scope for a deletion request instead of seven years.**

Hot-plus-cold is the shape regulators already codify. PCI DSS 4.0 requirement 10.5.1 asks for 12 months with the most recent 3 immediately available; SEC Rule 17a-4 for six years with the first two easily accessible. (Both confirmable as stated.)

The anti-pattern to name: the widely circulated **progressive-degradation ladder** that drops prompt and completion content after year one and keeps metadata only from year three. It degrades content precisely across the window where an auditor needs it, and lets a company claim "seven years of audit logs" while retaining nothing that explains a single decision.

## What you actually owe, and why it is probably nothing

| Instrument | Article / control | Binds whom | What it actually requires | Retention | Specifies fields? |
|---|---|---|---|---|---|
| EU AI Act | [Art. 12(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-12) | High-risk **systems** (design requirement) | Systems "shall technically allow for the automatic recording of events (logs) over the lifetime of the system" | n/a | **No** |
| EU AI Act | Art. 12(2)(a)-(c) | as above | Only the *purposes*: risk under Art. 79(1) or substantial modification; post-market monitoring under Art. 72; operation monitoring under Art. 26(5) | n/a | **No** |
| EU AI Act | Art. 12(3)(a)-(d) | **Annex III point 1(a) only** (remote biometric ID) | Period of each use; reference database checked; input data whose search led to a match; identification of persons verifying results | n/a | **Yes, the only place** |
| EU AI Act | [Art. 19(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-19) | **Providers** | Keep Art. 12(1) logs "to the extent such logs are under their control" | **at least 6 months** | No |
| EU AI Act | [Art. 26(6)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-26) | **Deployers** | Same duty, same limiter, separate clock | **at least 6 months** | No |
| EU AI Act | [Art. 18(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-18) | Providers | Technical documentation, QMS documentation, notified-body decisions, EU declaration of conformity | **10 years** after placing on the market or putting into service | n/a |
| EU AI Act | [Art. 86](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-86) | Deployers | "Clear and meaningful explanations of the role of the AI system in the decision-making procedure and the main elements of the decision taken" | n/a | **No** |
| ISO/IEC 42001 | the Annex A event-logging control | Voluntary | Event logs plus monitoring records showing logging is operational | none prescribed | **No** |
| NIST AI RMF | MEASURE 2.8, MANAGE 2.4, MANAGE 4.3 | Voluntary | Instrument and maintain histories and audit logs; preserve materials for forensic, regulatory and legal review; maintain incident and system-change databases | none prescribed | **No** |
| SOC 2 | 2017 TSC (2022 revised points of focus) | Contractual | Generic control-environment evidence applied to your agent | criteria-based, no period | **No** |
| HIPAA | [45 CFR 164.316(b)(2)(i)](https://www.govinfo.gov/content/pkg/CFR-2023-title45-vol2/xml/CFR-2023-title45-vol2-sec164-316.xml) | Covered entities | Retain required documentation | **6 years** | No |

Three splits that most summaries get wrong.

**Art. 12(1) is a design requirement on the system. Art. 19(1) puts a six-month floor on the provider. Art. 26(6) puts a separate, parallel six-month floor on the deployer.** Six months is owed twice over by two different parties, not one shared clock, both carrying the same limiter, "to the extent such logs are under their control".

**Six months is the LOG floor; ten years is the DOCUMENTATION floor.** Art. 18(1) and Art. 19(1) are two distinct regimes, routinely conflated.

**The obligation that actually forces per-decision explainability is Art. 86, not Art. 12.** An affected person subject to a decision taken by the deployer on the basis of an Annex III high-risk system's output (except point 2), producing legal effects or similarly significantly affecting them in a way that they consider to have an adverse impact on their health, safety or fundamental rights, has a right to explanations of the AI system's role and the main elements of the decision. Art. 86(3) makes it subsidiary to other Union law.

**And now the honest answer for most readers: out of scope of Art. 12/19/26(6) entirely.** High-risk means Art. 6(1) (safety component of an Annex I product requiring third-party conformity assessment) or Art. 6(2) ([Annex III's](https://ai-act-service-desk.ec.europa.eu/en/ai-act/annex-3) eight areas). A coding assistant, an internal research or support agent, a document-drafting agent is in none of them.

The "unless" that catches people is Annex III **point 4** (recruitment and selection, targeted job ads, filtering applications, evaluating candidates, decisions on terms of work, promotion, termination, task allocation based on behaviour or traits, performance monitoring) and **point 5** (a partial list of its four sub-points, the two that most often catch builders: (b) creditworthiness assessment and credit scoring excluding fraud detection, and (c) risk assessment and pricing for life and health insurance; the other two, (a) public-authority evaluation of eligibility for essential public assistance benefits and services including healthcare, and (d) emergency call triage and dispatch, catch govtech and benefits-adjacent agents).

Even an Annex III system can escape via the [Art. 6(3)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-6) derogation (narrow procedural task; improving a previously completed human activity; detecting patterns without replacing the prior human assessment; a preparatory task), but **never if it performs profiling of natural persons**. And Art. 6(4) makes the escape hatch generate its own paperwork: document the assessment before placing on the market, plus a registration obligation under Art. 49(2).

Two traps for builders. Building an agent purely for internal use does not make you merely a deployer: [Art. 3(11)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-3) defines putting into service as supply for first use "or for own use", so an internal high-risk system can owe Art. 19, Art. 26(6) and Art. 18 simultaneously. [Art. 25(1)(c)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-25) does the same to anyone who modifies the intended purpose of a general-purpose model so the system becomes high-risk.

Penalty exposure for logging duties is the middle tier, not the headline: [Art. 99(4)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-99) is up to EUR 15,000,000 or 3% of worldwide annual turnover, whichever is higher. It covers Arts. 16, 22, 23, 24, 26, 31, 33, 34 and 50; Art. 19 is not itself listed, so a provider's log-keeping breach is reached via Art. 16(e), which imports the Art. 19 duty, while the deployer's is Art. 26 directly. The 35 million / 7% tier is reserved for Art. 5 prohibited practices.

**The timeline has moved.** The Digital Omnibus on AI defers the high-risk application dates to **2 December 2027** for stand-alone (Annex III) high-risk systems and **2 August 2028** for high-risk AI embedded in regulated products, per the [Council of the EU](https://www.eeas.europa.eu/delegations/chile/artificial-intelligence-council-gives-final-green-light-simplify-and-streamline-rules_en). Procedural status as of late July 2026: EP plenary approval 16 June 2026, Council adoption 29 June 2026, signed 8 July 2026, awaiting Official Journal publication ([EP Legislative Train](https://www.europarl.europa.eu/legislative-train/package-digital-package/file-digital-omnibus-on-ai)). Any article still citing 2 August 2026 for high-risk is out of date. The Omnibus does not amend Articles 12, 19 or 26(6) in the agreed text as reported by every published analysis of it; the six-month floor is unchanged. Confirm against the OJ text once published.

Legacy systems may escape entirely: [Art. 111(2)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-111) applies the Regulation to high-risk systems placed on the market before the cutover only if they are subsequently subject to significant changes in their design; public-authority deployers have until 2 August 2030.

Two duties do bite regardless of risk tier: [Art. 4](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-4) (AI literacy, applicable since 2 February 2025, on providers and deployers) and [Art. 50(1)](https://ai-act-service-desk.ec.europa.eu/en/ai-act/article-50) (providers must design systems so natural persons are told they are interacting with an AI, unless obvious), which applies from 2 August 2026, ten days after this piece was published. Art. 50(2) content-marking gets a grace period to 2 December 2026 for systems already on the market. The Omnibus softens Art. 4 from ensuring a sufficient level of literacy to supporting its development among staff; the 2 February 2025 date is unchanged, and until OJ publication the original wording is still what binds.

And the standards that would specify *how* to satisfy Art. 12 do not exist yet: [CEN-CENELEC JTC 21](https://www.cencenelec.eu/news-events/news/2025/brief-news/2025-10-23-ai-standardization/) is still developing the Chapter III Section 2 standards, with acceleration measures adopted in October 2025 targeting availability around Q4 2026. Until then it is a statutory obligation with no technical specification behind it.

The voluntary frameworks give you no schema either. [ISO/IEC 42001](https://www.iso.org/standard/81230.html) is voluntary (ISO does not certify organisations; accredited bodies do), and its Annex A control A.6.2.8, "AI system recording of event logs", prescribes neither a retention duration nor a field list. [NIST AI RMF](https://www.nist.gov/itl/ai-risk-management-framework) is explicitly voluntary and behavioural. SOC 2 uses the 2017 Trust Services Criteria with 2022 revised points of focus, and no AI-specific criteria have been issued, so an auditor tests generic control-environment evidence applied to your agent.

Colorado is worth a line if you touch hiring or consequential decisions. SB 26-189, per the [bill page](https://leg.colorado.gov/bills/sb26-189), was signed 14 May 2026, effective 1 January 2027; it repeals and reenacts the 2024 Colorado AI Act. Scope is automated decision-making technology used in consequential decisions (education, employment, housing, financial/lending, insurance, health care, essential government services). Developers and deployers must retain compliance records for at least three years, for deployers running from the date of the consequential decision.

**The anti-theatre conclusion.** If you are out of scope, build the trail for the questions you will actually be asked: a customer dispute, an incident review, a bill dispute, a security investigation. Size the skeleton tier for the longest plausible future obligation, because it costs 31.50 GB/year. Then let six months be a floor you happen to clear rather than a programme of work. This is not legal advice, and none of the retention regimes above should be flattened into a single number that applies to you.

## Personal data: the trail you keep for years and the deletion request you get tomorrow

**A pseudonymous actor reference does not take the trail out of GDPR scope.** Recital 26 treats data that could be attributed to a person using additional information as personal data. Store a token that resolves to identity only through a separately-controlled mapping table, and do not claim the trail is anonymous.

**The six-month floor has a ceiling in the same sentence.** Art. 19(1) and Art. 26(6) both end "unless provided otherwise in the applicable Union or national law, in particular in Union law on the protection of personal data". Keeping everything forever is not the compliant answer, it is a separate violation.

**The design answer is the digest pivot:** the long tier holds hashes, codes, counts and classifications, no payload. That is what makes a seven-year skeleton defensible rather than a seven-year liability.

**Put \`tenant_id\` and \`organization_id\` on every child row, not just the parent.** Erasure runs as per-table org-scoped DELETEs; rows carrying only an \`execution_id\` need a join, and any row whose parent is already gone survives as an unreachable orphan still holding personal data. This platform's \`WorkspaceDataPurger\` issues an org-scoped DELETE against \`agent_execution_tool_calls\` keyed on \`organization_id\` (and equivalents), which only works because \`V210\` added the column to all five agent runtime tables and backfilled four of them (\`agent_tasks\` rows stay NULL by design, a personal scope).

**Split the trail into an erasable operational layer and a non-erasable ledger layer**, and let deletion take only the first. The reference implementation deletes 31 declared org-scoped tables (\`PURGED_ORG_SCOPED_TABLES\`) plus the agent execution child tables it hits directly (messages, tool calls, iterations), while never touching \`auth.credit_ledger\`, \`auth.usage_cycle\`, \`auth.credit_reconciliation_log\` or \`auth.organization_audit_event\`, and keeps the organisation row as a tombstone so ledger references stay valid. A coverage test asserts both the org-scoping of every statement and the non-deletion of the retained tables. The honest limit: the surviving ledger still proves that a subject's runs existed and what they cost, so this satisfies minimisation only if the ledger carries no payload and only pseudonymous identifiers.

**Erasure that does not erase.** When large payloads are offloaded to object storage and the row keeps a pointer, deleting the row **orphans the blob**. The personal data survives the deletion request, unreferenced and therefore invisible to any later audit of what you hold. The purger above documents exactly this orphan in its own javadoc: it deletes the \`storage.storage\` rows but not the underlying S3/MinIO objects. Fix: make the payload store the deletion target and the row the pointer, and reconcile orphans on a schedule.

**Decide whether redaction happens on write or on read, and record which.** A redactor that runs only when surfacing rows to a reviewer leaves raw credentials sitting in the stored tool arguments (the current state here: \`ToolCallRedactor\` is a read-path filter). A write-time redactor destroys evidence you may need. Whichever you choose, \`redaction_applied\` is what makes the choice auditable.

**The unresolved pattern worth implementing:** tombstone erased content while retaining its digest, so the tamper-evident chain survives an erasure and a later reader can still tell that something was there, how large it was, and that it was removed under a rights request rather than lost.

## Two failures to design out, and what to do about OpenTelemetry

**Retention you cannot retroactively lengthen.** The day you discover the window is longer than your purge cron, the data is gone. One team here bumping a lifecycle audit log from 30 to 365 days hit a 12x backlog on the first purge afterwards, and that was the *lucky* direction. Set the skeleton tier to the longest plausible obligation on day one; at 31.50 GB/year it is the cheapest insurance in the system. (Related: a documented retention comment saying "30d default" while the service's \`@Value\` default was 365 is how documented and configured retention silently diverge.)

**Query-path mistakes that make a trail unusable rather than wrong.** Detail rows are not the query path: pre-aggregate the low-cardinality dimensions into rollups keyed on \`(tenant, date, provider, model)\` and \`(tenant, tool_name)\`. Postgres does not auto-index foreign keys: an 18k-row, 39 MB tool-call table here whose only index was its primary key full-scanned on every aggregate read until \`V341\` added a \`CONCURRENTLY\` btree on \`execution_id\`. And unpaginated reads of MB-scale payload rows are an OOM shape: cap the page (100 is a reasonable hard max) and return \`total\` / \`shown\` / \`truncated\` so a reader is told when older rows were dropped instead of silently seeing a partial trail.

The cardinality rule that follows from the schema tables: **low-cardinality fields** (\`status\`, \`stop_reason\`, \`provider\`, \`model\`, \`tool_name\`, \`trigger_source\`, \`branch_taken\`) are what every question groups by and belong in rollups; **high-cardinality fields** (\`run_id\`, \`tool_call_id\`, digests) are join keys that need btree indexes and must never enter a rollup key.

### The OpenTelemetry verdict

**Do not pin an audit schema to it yet.** Zero \`gen_ai.*\` attributes are Stable (99 Development, 0 Stable in the live registry), the [GenAI semconv repo](https://github.com/open-telemetry/semantic-conventions-genai) has no releases and no tags, and the conventions moved out of the main semantic-conventions repo, which now renders every \`gen_ai.*\` attribute on the [legacy registry page](https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/) as "Deprecated" as an artifact of the move. A false signal in both directions.

Renames have already broken schemas once:

\`\`\`
gen_ai.system              -> gen_ai.provider.name (now absent)
gen_ai.usage.prompt_tokens -> gen_ai.usage.input_tokens
gen_ai.usage.completion_tokens -> gen_ai.usage.output_tokens
gen_ai.prompt / gen_ai.completion
   -> gen_ai.input.messages / gen_ai.output.messages
\`\`\`

OTel has **no attribute** for a human approval, an actor or principal identity, a policy or guardrail decision, a retention class, or monetary cost (token counts only, no \`gen_ai.cost.*\`). Those are precisely the audit-bearing fields, which is why the trail is your table and not your tracing backend.

Two fields are worth adopting verbatim because they are cheap and answer real audit questions: **\`gen_ai.prompt.name\` plus \`gen_ai.prompt.version\`** prove which prompt version ran without storing its text, and **\`gen_ai.conversation.compacted\`** answers whether the model saw the full history or a summary. Note also that \`gen_ai.provider.name\` is a telemetry-format discriminator that may point at a proxy, not proof of which vendor processed the data, and that \`gen_ai.conversation.id\` must not be fabricated from a UUID, trace id or content hash, so it is legitimately absent in many trails.

Span limits truncate a trail silently: \`OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT\` defaults to 128. Flattened per-message indexed attributes (the OpenInference \`llm.input_messages.<i>.message.*\` shape) can exceed that on a long conversation, while a single structured \`gen_ai.input.messages\` costs one attribute. That is derived arithmetic, not a documented incident. Structured attribute values are also not yet universally supported on spans, so the same logical field is a JSON string in one backend and an object in another.

The spec's own production recommendation is the architecture argued for here: store content in external storage with separate access controls and record references on spans, and invoke the upload hook "regardless of the span sampling decision". **Sample the traces, never sample the evidence.** That is \`payload_ref\` plus digest by another name.

Closing rule: **emit OTel for the dashboard, own a table for the trail, join them on \`run_id\`, and keep the two retention clocks separate.**
`;

export default content;
