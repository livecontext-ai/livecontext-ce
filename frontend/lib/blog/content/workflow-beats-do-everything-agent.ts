// Markdown body for "What a scoped workflow actually costs versus a do-everything
// agent". Plain string module (see the-niche-data-advantage.ts for the rationale).
//
// Editorial contract for this post: every ratio is DERIVED on-page from stated
// assumptions, none is cited from another blog. The previous version asserted an
// undefended "about ten times cheaper"; that claim is now retracted in the opening
// section on purpose, so do not reintroduce a bare multiple anywhere.
//
// External facts (model prices, cache multipliers, minimum cacheable prefix, the
// newer-tokenizer note, tool-use system-prompt surcharges, web-search pricing,
// batch discount) were verified against the vendors' live documentation on
// 2026-07-22. Product figures come from this repo, re-check them before editing:
//   backend/agent-service/.../budget/TenantBudgetGuard.java      (projection formula)
//   backend/agent-service/.../budget/ModelCostCalculator.java    (worstCaseSingleIter)
//   backend/agent-service/.../budget/GuardChainFactory.java      (15/75 pessimistic fallback)
//   backend/agent-service/.../budget/TenantBudgetGuardRegressionMinus11305Test.java (the burst)
//   shared/contracts/agent-stop-reason.json                      (10 stop reasons, 3 categories)
//
// KNOWN EXPIRY: Claude Sonnet 5 introductory pricing ends 2026-08-31, moving it from
// $2/$10 to $3/$15 per MTok. The worked example prices Sonnet 4.6 ($3/$15), which is
// unaffected, but the model-routing section names both.
const content = `## The number I deleted

An earlier version of this article said a scoped workflow runs "about ten times cheaper" than a do-everything agent. That number had no derivation, no assumptions and no source behind it, so it is gone.

There is no published source to replace it with. No vendor benchmark, paper or trace measures the same job implemented once as a scoped pipeline and once as an autonomous agent with cost and success instrumented both ways. The canonical piece in this category, Anthropic's [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents), contains zero cost figures; its treatment of the subject is two sentences: "Agentic systems often trade latency and cost for better task performance, and you should consider when this tradeoff makes sense," and "The autonomous nature of agents means higher costs, and the potential for compounding errors." The second sentence asserts this article's thesis with no number attached to it.

The multipliers that circulate are not interchangeable. "3-10x" circulates as a claim about LLM call count and "5-30x" as a claim about tokens per task, neither with a traceable primary source. The one figure with visible assumptions, [12.4x from a dev.to post](https://dev.to/awxglobal/why-your-llm-agent-costs-10x-more-than-your-estimate-4o78), is built from an 800-token system prompt resent on every call, 4 turns per request and 3.5 tool calls at 250 tokens of overhead each, against a baseline that counts only the user's own prompt and the model's reply. Its 12.4x is therefore a statement about prompt-and-tool overhead ratio at a fixed turn count, not about a whole job; change the turn count and the multiple moves. That is the one derivation in the genre you can audit, and auditing it shows it does not measure what the other two ranges measure. The framework posts that do compare shapes ([Sashido](https://www.sashido.io/en/blog/agentic-workflows-roi-without-expensive-agents), [LindleyLabs](https://lindleylabs.com/blog/agent-or-pipeline-a-decision-framework-for-ai-engineers), [Retool](https://retool.com/resources/ai-workflows-vs-agents)) print formulas and decision trees with no populated variables.

There is a genuine, sourced 10x in this space and it is not the claim I deleted: Anthropic's [cache-read multiplier is exactly 0.1x base input](https://platform.claude.com/docs/en/about-claude/pricing), so a cached input token is precisely ten times cheaper than an uncached one. That applies to the cached-prefix component of input tokens, nothing more.

Rule for the rest of this piece: every ratio printed here is derived on-page from stated assumptions. None is cited.

## Where the money goes: two cost functions, one quadratic

The mechanism first, so every number later is falsifiable by inspection.

The Messages API is stateless. An agent's call \`i\` therefore carries \`In_i = B + (i-1)g\`, where \`B = S + T + P0\` is the system prompt, tool definitions and initial payload, and \`g = a + r\` is the per-turn growth (assistant output plus the tool result appended back). Summing over N calls:

\`\`\`
I(N) = N*B + g*N*(N-1)/2
\`\`\`

The first term is the prefix tax, linear in N. The second is the accumulation tax, quadratic in N. A token produced at turn \`i\` gets re-read as input \`(N - i)\` more times.

The scoped workflow's cost is:

\`\`\`
C_wf = sum over k of [ p_in^k*(s_k + t_k + d_k) + p_out^k*o_k ]
\`\`\`

Linear in K, because step k receives its declared inputs \`d_k\` and never the transcript of steps 1 through k-1. Note the \`^k\` on the price: a workflow can route each step to a different model at no penalty. A single-loop agent pays a full cache re-write of its accumulated prefix every time it switches models mid-conversation, so in practice it pins one model for the whole loop. Per-call routing inside an agent architecture requires a sub-agent boundary, which is itself a scoping decision and costs a fresh prefix per sub-agent.

The decomposition bound is exact rather than rhetorical. Splitting an N-call loop into K scoped segments divides the accumulation term by exactly K, since \`K * g*(N/K)^2/2 = g*N^2/(2K)\`. A three-step split caps the accumulation saving at 3x. Any claimed 10x from a three-step workflow is coming from the prefix tax, payload width or model routing, not from breaking the quadratic.

Tool definitions are a real component of B. Anthropic reports that a five-server MCP setup (GitHub, Slack, Sentry, Grafana, Splunk) [consumes roughly 55,000 tokens of definitions](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool) before the model does any work. At list price that is $0.275 per uncached call on Opus 4.8, and that figure holds the token count constant across the tokenizer boundary described at the end of this piece, so treat it as a floor rather than an estimate.

## The worked example: support triage, every assumption printed

The job: classify a ticket, look up the account, search the KB, draft a reply, review it.

| Parameter | Symbol | Agent value | Workflow value | Where it comes from |
|---|---|---|---|---|
| System prompt | S | 1,500 tok | 250-600 tok per step | Assumption: one do-everything prompt vs four narrow ones |
| Tool definitions | T | 6 tools, 900 tok | 30 tok per step, 120 tok total | Assumption; no vendor publishes a per-tool figure |
| Initial payload | P0 | 600 tok | 600 tok (ticket text) | Same ticket both ways |
| Prefix | B = S+T+P0 | 3,000 tok | n/a (per-step) | Sum of the above |
| Per-turn growth | g = a + r | 1,000 tok | 0 (no transcript carried) | a=300 output, r=700 tool result |
| Calls / steps | N / K | 8 calls | 4 LLM steps + 2 deterministic | Judgment about what the job needs |
| Price | p_in / p_out | $3.00 / $15.00 per MTok | same | [Sonnet 4.6 list price](https://platform.claude.com/docs/en/about-claude/pricing), verified 2026-07-22 |

Every one of those token counts is a stated assumption, not a measurement. None came from a token-counting endpoint.

**Agent ledger, N=8.** Input grows by exactly g = 1,000 per call, so the table is an arithmetic progression from 3,000 to 10,000; only the endpoints and the second row are informative.

| Call | Input tokens | Cumulative input | Output tokens | Running cost |
|---|---|---|---|---|
| 1 | 3,000 | 3,000 | 300 | $0.0135 |
| 2 | 4,000 | 7,000 | 300 | $0.0300 |
| ... | +1,000 each | | 300 | |
| 8 | 10,000 | 52,000 | 300 | $0.1920 |

The 52,000 total matches the closed form: \`8*3,000 + 1,000*(8*7/2) = 24,000 + 28,000\`. Cost: 52,000 x $3/MTok = $0.156 input, plus 2,400 x $15/MTok = $0.036 output. **$0.192 per ticket.**

**Workflow ledger, same job.** The prefix and payload columns are split out, because that split is where the two levers below come from.

| Step | LLM? | Model | System | Tool defs | Declared data | Input | Output | Step cost |
|---|---|---|---|---|---|---|---|---|
| Classify | yes | Sonnet 4.6 | 250 | 30 | 600 | 880 | 60 | $0.00354 |
| Account lookup | no | (deterministic) | 0 | 0 | 0 | 0 | 0 | $0 |
| KB retrieval | no | (deterministic) | 0 | 0 | 0 | 0 | 0 | $0 |
| KB query build | yes | Sonnet 4.6 | 250 | 30 | 70 | 350 | 25 | $0.00143 |
| Draft | yes | Sonnet 4.6 | 600 | 30 | 1,810 | 2,440 | 400 | $0.01332 |
| Review | yes | Sonnet 4.6 | 600 | 30 | 450 | 1,080 | 80 | $0.00444 |
| **Total** | | | **1,700** | **120** | **2,930** | **4,750** | **565** | **$0.02273** |

The draft step's declared data is ticket 600 + label 60 + account facts 250 + top-3 KB excerpts 900 = 1,810.

This ledger prices model tokens only. On the hosted product a terminal workflow node also carries a flat 1 credit ($0.001), which adds $0.006 for these 6 nodes and moves the workflow to $0.0287. Every ratio below is the token-only comparison; the hosted-cost version of the headline is stated where it first matters.

**Headline for this configuration: 8.4x** ($0.192 / $0.02273), or 6.7x once the hosted per-node fee is included. The input-token ratio alone is 10.9x (52,000 / 4,750). The output-token ratio is only 4.2x (2,400 / 565), and that is what drags the blended figure below 10x: both shapes have to actually write the same ~400-token reply.

Two levers survive caching, but shrunk by it. The **prefix tax**: uncached, the agent resends its 3,000-token do-everything prefix eight times (24,000 tokens) against the workflow's 1,820 tokens of system prompts and tool definitions in total, 13.2x. With incremental caching the agent's prefix component falls to \`1.25B + 0.1(N-1)B\` = 5,850 effective tokens, and the lever drops to 3.2x. The **payload width**, on a snapshot basis: at its final call the agent's input contains 7,600 tokens of accumulated payload and tool transcript (600 initial plus 7 x 1,000 of growth) against the workflow's widest single step, whose declared input is 1,810 tokens, 4.2x. That comparison switches accounting basis once caching is on, because the agent's older deltas are re-read at 0.1x: on a cumulative effective-token basis the agent's payload growth costs \`1.25*1,000*7 + 0.1*1,000*21\` = 10,850 tokens against the workflow's 2,930 declared-data tokens, 3.7x. The mechanism behind both is that a declared input is a projection over the raw observation.

## The ratio is a function of N, and N is the whole argument

Under the example's assumptions, agent cost is \`0.0015N^2 + 0.012N\` dollars. Check at N=8: $0.096 + $0.096 = $0.192.

| N (agent calls) | Agent cost | Workflow cost | Ratio | What this N represents |
|---|---|---|---|---|
| 2 | $0.030 | $0.0227 | 1.3x | Short-circuit: "spam, escalate" |
| 4 | $0.072 | $0.0227 | 3.2x | Minimal tool use, no retries |
| 6 | $0.126 | $0.0227 | 5.5x | One lookup retried |
| 8 | $0.192 | $0.0227 | 8.4x | The worked example |
| 12 | $0.360 | $0.0227 | 15.8x | Agent explores the KB |
| 20 | $0.840 | $0.0227 | 37.0x | Wandering, or a genuinely hard ticket |

Solving \`0.0015N^2 + 0.012N = 0.022725R\`: the ratio equals 10 at N = 8.94 calls and equals 3 at N = 3.84 calls. Quoting a cost ratio without quoting N is meaningless.

One fairness constraint on that table: the high-N rows are only legitimate if the job genuinely needs that many calls. An agent that takes 20 calls to do what a workflow does in 4 is exhibiting wandering, which is a competence finding and has to be argued as one, not smuggled into a cost table.

## Cache the agent properly, then compare

Published workflow-versus-agent ratios generally compare against an uncached agent. Caching is where most of the gap goes, so price it before you compare.

Anthropic's [caching multipliers](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) are exact: 5-minute cache write = 1.25x base input, 1-hour write = 2x, cache read = 0.1x. The break-even is published too: the 5-minute cache pays off after a single read (1.25 + 0.1 = 1.35x against 2x uncached); the 1-hour cache needs two reads (2 + 0.2 = 2.2x against 3x).

With incremental multi-turn caching the agent's effective input becomes:

\`\`\`
1.25B + 0.1(N-1)B + 0.1g(N-2)(N-1)/2 + 1.25g(N-1)
\`\`\`

The quadratic coefficient drops from \`p_in*g/2\` to \`0.1*p_in*g/2\`, an exact 10x discount on precisely the term the workflow was beating.

At N=8 the per-call effective input runs 3,750 / 1,550 / 1,650 / 1,750 / 1,850 / 1,950 / 2,050 / 2,150 = 16,700 tokens against 52,000 uncached. That is $0.0501 input plus $0.036 output = **$0.0861**. Caching cuts agent cost 55% and collapses the headline from 8.4x to **3.8x**.

Note what dominates once caching is on: the 1.25x write of each turn's delta, \`1.25 * 1,000 * 7 = 8,750\` of the 16,700 effective tokens, 52%. The surviving workflow advantage is the prefix tax and payload width, not the resend quadratic.

Caching flattens the gap without closing it. At N=20 the cached agent costs $0.241 against $0.840 uncached, still 10.6x the workflow, because 19 cache writes at 1.25x plus 20 turns of generated content are irreducible.

The workflow captures almost none of the same benefit here. The minimum cacheable prefix on Sonnet 4.6 is 1,024 tokens (verified against the caching docs 2026-07-22; it is 512 on Fable 5 and Mythos 5, 2,048 on Opus 4.7, and 4,096 on Opus 4.6, Opus 4.5 and Haiku 4.5). Each workflow step's stable prefix here is its system prompt plus tool definitions, 280 to 630 tokens, under the threshold on every one of those models. Sub-minimum prefixes fail silently: no error is returned and both \`cache_creation_input_tokens\` and \`cache_read_input_tokens\` read 0. Note that routing a step to Haiku 4.5 raises its threshold to 4,096, so the routed configuration below is further from cacheable, not closer.

The actionable fix has a published break-even. Consolidate a high-volume step's prefix above the cacheable minimum for the model it runs on and place the breakpoint after it, so every run of that step reads at 0.1x. On the 5-minute TTL that pays off from the second request onward, and [cache reads refresh the TTL for free](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), so a step hit at least every five minutes stays warm indefinitely at the write price.

One thing caching does not do: cached tokens [still occupy the context window](https://platform.claude.com/docs/en/build-with-claude/context-windows). It changes what you pay for those tokens, not whether they count. It rescues nobody from context exhaustion or context rot.

## The four-cell grid, and where a real 10x actually lives

Routing classify, KB-query and review to Haiku 4.5 ($1/$5) and only the draft to Sonnet 4.6 drops the workflow to $0.0165 per ticket (classify $0.00118 + KB-query $0.000475 + draft $0.01332 + review $0.00148).

| | Workflow, same model ($0.0227) | Workflow, routed ($0.0165) |
|---|---|---|
| **Agent, uncached ($0.192)** | 8.4x | 11.7x |
| **Agent, cached ($0.0861)** | 3.8x | 5.2x |

My default is the top-right cell inverted: cache the agent, route the workflow, and expect 5.2x. Below about N=4 I would not build the workflow at all, because the ratio is under 3x and the build cost does not repay (see the closing section); above about N=12 the quadratic makes the decision for you.

A single-loop agent must run its one pinned model on every call. An Opus 4.8 agent ($5/$25) is not a like-for-like swap of the same token counts, because Opus 4.7 and later use a newer tokenizer that produces roughly 30% more tokens for the same text. Applying that uplift: about 67,600 input and 3,120 output, which is $0.338 + $0.078 = $0.416, against the routed workflow's $0.0165, or 25.3x. That is a routing argument, not a context-window argument.

The blanket claim from context discipline alone, derived: with the agent cached and both shapes on one model, the ratio runs 2.8x at N=6, 3.8x at N=8 and 5.8x at N=12. So roughly 3x to 6x across a plausible N range, and anything above that is a caching or routing decision that has to be stated as one.

Vendor price structure makes routing predictable. Every current Anthropic model prices output at exactly 5x input (Opus 4.8 $5/$25, Sonnet 4.6 $3/$15, Haiku 4.5 $1/$5). Every current OpenAI model prices output at 6x input (gpt-5.6-sol $5.00/$30.00, gpt-5.4 $2.50/$15.00, gpt-5.4-mini $0.75/$4.50), with gpt-5.4-nano the one exception at 6.25x ($0.20/$1.25). [DeepSeek](https://api-docs.deepseek.com/quick_start/pricing) prices output at exactly 2x cache-miss input (deepseek-v4-flash $0.14/$0.28, deepseek-v4-pro $0.435/$0.87). Within a vendor, the input:output mix drives the cost profile more than model choice does. And the batch tier is a fifth, workflow-only lever for non-latency-sensitive steps: a flat 50% discount on both input and output at Anthropic and OpenAI, half the standard rate at Gemini, stacking with the caching multipliers at Anthropic.

## Where this model breaks

| Condition | Effect on the ratio | Magnitude here | Why it happens |
|---|---|---|---|
| Short jobs (N<4) | Collapses, can invert | 1.3x at N=2 | Agent short-circuits; workflow always runs its fixed path |
| Output-dominated job | Tends to 1 | 2.2x for a 5,000-word report at N=8 | Both shapes write the same deliverable |
| Large shared context | Can reverse | 5D vs 1.95D on a 50k doc | Workflow re-sends per step unless it caches the doc first |
| Parallelizable breadth-first research | Favours multi-agent | +90.2% on one vendor eval | Autonomy buys coverage the pipeline cannot enumerate |
| Tool search (deferred loading) | Shrinks prefix advantage | vendor claims >85% definition cut | Agent captures prefix saving without re-architecting |
| Toolset > 30-50 tools | Favours workflow, on correctness | not priced | Tool-selection accuracy degrades |
| Model-dependent tool surcharge | Shifts B | 290 to 804 tok across models | Fixed system-prompt cost before any schema |
| Server-side tool charges | Outside the token model | $10 per 1,000 web searches | Per-call billing, not per-token |
| Tokenizer change / price reversion | Invalidates dated figures | ~30% more tokens; $2/$10 to $3/$15 | New tokenizer from Opus 4.7 onward; Sonnet 5 intro pricing ends 31 Aug 2026 |

Four of those deserve expansion.

**Output-dominated jobs.** A 5,000-word report is roughly 6,700 output tokens, $0.1005 at $15/MTok on both sides. Holding the example's inputs (agent $0.156, workflow $0.01425), the ratio is $0.2565 / $0.1145 = 2.2x, and it keeps falling as the deliverable grows.

**Large shared context** is the reversal case. If every step needs the same 50k-token document, a five-step workflow re-sends it five times (5D) while a cached eight-call agent pays 1.25D + 7 x 0.1D = 1.95D. The workflow only wins if it puts the document first, before the step-specific instruction, and caches it (1.25D + 4 x 0.1D = 1.65D).

**Parallelizable research is the strongest published case against this whole thesis.** Anthropic reports that a multi-agent system, a Claude Opus 4 lead orchestrating Claude Sonnet 4 subagents, [outperformed single-agent Claude Opus 4 by 90.2%](https://www.anthropic.com/engineering/multi-agent-research-system) on their internal research eval, and in the same post that agents use roughly 4x the tokens of a chat interaction while multi-agent systems use roughly 15x. That is autonomy buying a large quality win at a large cost multiple, and it is also an agent architecture doing per-step model routing across a sub-agent boundary. Anthropic's own precondition is the honest framing: it pays only when the task value covers the multiplier, and it is a poor fit where all agents need the same context or the work has many dependencies.

**Tool search** is the strongest counter-argument to the prefix-tax case specifically. Anthropic states deferred tool loading [typically cuts tool-definition context by over 85%](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool), loading only the 3-5 tools needed, which lets a do-everything agent capture much of the prefix saving without being re-architected. That is a vendor claim with no disclosed methodology, and it should be treated as one. Anthropic's own trigger: use tool search at 10 or more tools, or when definitions exceed 10k tokens. The same page states tool-selection accuracy degrades once you exceed 30-50 available tools, which gives the scoping argument a reliability leg that does not depend on the token math at all.

## Cost per successful run, and the condition that inverts the argument

The comparison that actually matters is \`C/q\`: cost divided by per-shape success rate. A 20% workflow re-run rate multiplies its cost by 1.2 and cuts the 8.4x headline to 7.0x. An agent that recovers in-context instead pays a few extra, quadratically priced, turns.

Repeat-run consistency collapses faster than headline accuracy. In the original 2024 [tau-bench retail](https://arxiv.org/abs/2406.12045) paper, the then-best function-calling agents were inconsistent enough that pass^8 fell under 25%. Production means running the same job many times, so pass^k is the correct metric, not pass@1, and that structural point is what carries here rather than any 2024 absolute rate.

Success also decays with task length in a way that makes scope reduction superlinear in reliability. [Toby Ord's half-life model](https://www.tobyord.com/writing/half-life) predicts the 80% success horizon is about 1/3 of the 50% horizon, 90% about 1/7 and 99% about 1/70; the author is explicit that the model is fitted to one task suite and that its generality is unknown. [METR's measurements](https://arxiv.org/abs/2503.14499) show 80% time horizons roughly 5x shorter than 50% horizons, which is steeper than the half-life model's 3x, so the two bracket the effect rather than confirm each other. And the failure is structural, not merely a lower score: the [HORIZON study](https://arxiv.org/html/2604.11978v1) of 3,100+ trajectories attributes 72.5% of failures to process-level causes (environment error, instruction error, planning error, accumulated history) and reports an abrupt transition from partial robustness to near-systematic failure. That same study argues decomposition alone is not the fix: it calls for hierarchical planning and execution-time verification, not merely splitting the task up.

The strongest opposing model is [Zartis's](https://www.zartis.com/ai-agent-cost-optimisation-why-token-cost-is-the-wrong-number-to-optimise/):

\`\`\`
total_cost_per_task = (token_cost + infrastructure_cost) / reliability_rate
                      + failure_rate * human_remediation_cost
\`\`\`

Their worked example makes a 5x-more-expensive-per-call architecture ($0.05 against $0.01) 5.7x cheaper overall (~$8,835/day against ~$50,100/day) once reliability rises from 70% to 95%. Their token counts, hourly rates and remediation minutes are that article's stated assumptions, not measurements, and their two architectures differ in context width rather than in autonomy. The structure of the argument still holds.

Solve it on these numbers. Workflow $0.0227, cached agent $0.0861, delta $0.0634 per ticket. If a failure costs \`H\` in human remediation and the agent's success rate exceeds the workflow's by \`dq\`, the agent wins when \`dq * H > 0.0634\`. At a $100/hour analyst and 10 minutes per remediation, H = $16.67, so a success-rate advantage of 0.38 percentage points is enough. At 5 minutes and $80/hour, H = $6.67 and the threshold is 0.95 points. Say that plainly: **at any nontrivial human-remediation rate, the token ratio stops being the deciding term.** The 3.8x this break-even was built on is a rounding error against a one-point difference in success rate, and even the uncached 8.4x only needs a 1.02-point advantage to flip (delta $0.1693 against H = $16.67). That cuts both ways, and it is the reason the reliability argument for scoping (shorter horizons, fewer tools, verified inter-step contracts) matters more than the cost argument this whole article just derived.

Nor does spending more buy accuracy. On [GAIA](https://hal.cs.princeton.edu/gaia), an agent using o3 Medium cost $2,828.54 for 28.48% accuracy while Gemini 2.0 Flash cost $7.80 for 32.73%. In the same [evaluation programme](https://arxiv.org/abs/2510.11977), higher reasoning effort reduced accuracy in the majority of the 36 model-benchmark settings tested.

## N is an outcome, not an input

Everything above treats N as a parameter. In production it is emergent, which is why the ratio has a long tail.

The tail is not a gradual climb, it is a step function, and that is what makes it hard to guard. One production incident has the shape on record: a run cruised for several iterations at roughly 70k prompt and 700 completion tokens each, cheap enough that an average-based projection kept approving the next one, and then a single iteration burst to about 2M tokens. A moving average dilutes precisely that.

The bound that catches it does not average at all:

\`\`\`
projectedNext       = max(growth projection, last delta x 2, worstCaseSingleIter)
worstCaseSingleIter = cost(full context window, full max output)
\`\`\`

That second term is invariant to the growth pattern, which is the entire point. Priced on an Opus-class row of 200k context and 64k max output at $15/$75 per MTok, one worst-case iteration is 200,000 x $15/MTok + 64,000 x $75/MTok = $3.00 + $4.80 = $7.80. A single iteration of that model can plausibly cost more than a small account balance, so the guard trips on the first cruise iteration rather than betting on the average.

Cost estimation fails expensive rather than cheap for the same reason: a model with no pricing row falls back to $15/$75 per MTok, the highest tier in the snapshot, because an earlier near-zero fallback silently bypassed the budget guard altogether.

A run's ending has to be classified before cost per success can be computed at all. One production taxonomy enumerates exactly 10 stop reasons in 3 terminal categories: success (COMPLETED); partial (MAX_ITERATIONS, TIMEOUT, BUDGET_EXHAUSTED, LOOP_DETECTED, STOPPED_BY_USER), defined as "terminated cleanly but did not complete the task as planned, output is usable but truncated or early"; and failure (CANCELLED, NO_TOOLS, ERROR, INACTIVITY_TIMEOUT). TIMEOUT and INACTIVITY_TIMEOUT land in different categories deliberately: working past a wall-clock budget is partial, producing no token, thinking, tool call or tool result inside the watchdog window is failure.

The deterministic-step anchor makes the comparison concrete. A terminal workflow node, completed or failed, costs a flat 1 credit ($0.001) on the hosted product; only skipped nodes are free, and self-hosted records the same 1-credit ledger row for observability but never deducts it, because the balance is unlimited. At Sonnet 4.6's list $3/MTok, one credit is 333 tokens of list-price input; on the hosted product the 1.8x cloud LLM margin makes it about 185 tokens, so any prompt above roughly 186 tokens costs more than an entire deterministic step. Only 4 of roughly 60 palette node types (Agent, Classify, Guardrail, Browser Agent) invoke an LLM at all.

## How to run this on your own numbers

1. **Measure the tokens.** Every count in the example above is an assumption. Replace them with the provider's token-counting endpoint against real ticket text, real tool schemas and a real KB excerpt.
2. **Measure N from existing traces, do not estimate it.** The ratio is roughly quadratic in N, so a wrong N is a squared error in the headline.
3. **Classify a month of terminated runs by stop reason and terminal category** before quoting any cost-per-success figure. Partial and failure endings have different remediation costs and only one of the three categories counts as a successful run.

Two things this model does not contain, and neither should be inferred from it. It says nothing about output quality: it prices tokens, and there is no success-rate measurement behind any figure in it. And it ignores engineering cost, which is the term that decides most of these calls in practice. My own estimate, stated as an assumption like everything else here: a five-step workflow with declared inter-step contracts costs roughly three engineer-days to build and half a day a month to maintain, against half a day to wire one agent to six tools. At the same $100/hour used for remediation above, that is about $2,000 more up front and about $400/month more recurring. Against the cached-agent delta of $0.0634 per ticket, the up-front gap alone needs roughly 31,500 tickets to repay, and the maintenance gap needs roughly 6,300 tickets a month on top. Below that volume, the row of the break-even table you are on is the one that says build the agent.
`;

export default content;
