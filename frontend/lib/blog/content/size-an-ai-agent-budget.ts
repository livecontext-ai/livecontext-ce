// Markdown body for "How to size an agent budget you can actually enforce".
// Plain string module (see the-niche-data-advantage.ts for the rationale).
//
// The sizing half of the pair whose other half is cap-ai-agent-cost-budgets.ts
// (enforcement). They cross-reference each other; keep both links working.
//
// Everything here is DERIVED, not measured. The archetype parameter sets in
// Table 4a are constructed on purpose so every downstream column can be
// reproduced by the reader, and the article says so twice. Prices are an
// illustrative catalog snapshot, and the method is price-independent: do not
// "refresh" them into live vendor pricing without re-deriving every dependent
// figure in Tables 4b and 5.
//
// The load-bearing results, each recomputable from the generating model:
//   - the safety factor S = (n_q / n_p50)^alpha, with alpha measured per archetype
//   - the enforceability floor g = B_step / worst-case-iteration >= 3
//   - the compounding false-kill rate 1 - q^k across k capped steps
//   - the sample size 11/(1-q) needed before a tail quantile can be quoted
//   - the pooled run margin 1 + (S_step - 1)/sqrt(M) for an independent fan-out
//
// Product figures (the reservation cascade, the loop-detector rungs, the
// defaults) come from backend/agent-service/.../budget/ in this repo.
const content = `A companion article argues that most agent budgets are numbers that have never refused a single call, and works through the enforcement machinery: what a budget object is made of, why an in-run cap can only ever stop the call after the expensive one, and what each stack can actually enforce. This one answers the question that comes next. You are convinced the ceiling should be real. What number do you put in the box?

The short answer is that you cannot pick it by intuition, because the quantity you are bounding is right-skewed, superlinear in iteration count, and spans three orders of magnitude across step types. The long answer is the rest of this article: a generating model you can reproduce, a derived safety factor, a floor below which a money cap cannot be enforced at all, and the sample size you need before you are allowed to quote a tail quantile.

**Disclosure.** The implementation constants and the reservation mechanism described below come from LiveContext's \`agent-service\`, the platform this blog belongs to. Read them as one system's choices, verifiable in its community-edition source, not as surveyed field practice. Prices are illustrative catalog snapshots; the method is price-independent.

## Sizing a per-step budget you can compute

A step running \`n = k+1\` model iterations with \`k\` tool calls has an expected cost driven by the fixed prompt \`P0\`, the input payload \`I\`, the tokens \`r\` returned per tool result, the output \`O\` per turn, and an accumulation term proportional to \`n(n-1)/2\`. The generating model for every row below:

\`\`\`
prompt_i = (P0 + I) + (i-1) * (O_turn + r)      i = 1..n
\`\`\`

**Table 4a: Per-step archetype parameters.** These are **constructed parameter sets, not measured production traces**, published so every derived column can be reproduced.

| Step archetype | P0 + I | r per tool result | O per turn | n |
|---|---|---|---|---|
| Classify | 1,000 | n/a | 30 | 1 |
| Retrieval-augmented draft | 2,000 | 6,000 | 60 tool turn, 500 final | 2 |
| Multi-tool research | 2,500 | 3,000 | 80 tool turn, 800 final | 7 |
| Long-document summarise | 120,300 | n/a | 1,500 | 1 |
| Browser step | 1,800 | 8,000 | 120 action turn, 250 final | 13 |

**Table 4b: Per-step sizing.** Prices come from a repo catalog snapshot and are illustrative, not live provider pricing. The method is price-independent. The last column uses a flat S=3 as an illustrative factor; the next section replaces it with a derived one.

| Step archetype | Model class, list rate ($/1M in, out) | Tokens in / out | Iterations n | Expected cost | Largest single iteration (x first) | Budget at S=3 (flat) |
|---|---|---|---|---|---|---|
| Classify | flash-lite class, 0.25 / 1.50 | 1,000 / 30 | 1 | $0.00030 | 1.0x | $0.0009 |
| Retrieval-augmented draft | haiku class, 1.00 / 5.00 | 10,060 / 560 | 2 | $0.01286 | 4.6x | $0.0386 |
| Multi-tool research | sonnet class, 3.00 / 15.00 | 82,180 / 1,280 | 7 (6 tool calls) | $0.2657 | 8.6x | $0.797 |
| Long-document summarise | flash class, 0.30 / 2.50 | 120,300 / 1,500 | 1 | $0.0398 | 1.0x | $0.1195 |
| Browser step | gpt-5.4 class, 2.50 / 15.00 | 656,760 / 1,690 | 13 (12 actions, 8,000-token snapshots) | $1.667 | 40x | $5.00 |

The 40x first-to-last ratio on the browser step drives the design: a moving-average projection under-projects the killing iteration by more than an order of magnitude. That is why a projection needs a worst-case branch that ignores the growth pattern entirely, as the companion article derives.

### The safety factor is derived, not guessed

\`\`\`
S = (n_q / n_p50) ^ alpha        where alpha = dlogC / dlogn

alpha in [1, ~2.3]: it approaches 1 for single-shot
steps, approaches 2 for accumulation-dominated steps,
and exceeds 2 when the first iteration is cheap
relative to the accumulated context.

alpha:  classify ~1.0 | long-doc ~1.0 | RAG draft 1.77
        multi-tool research 1.81 | browser 2.03
\`\`\`

A step whose p99 uses twice the tool calls of its p50 needs S around 2.0 if single-shot, but 3.4 to 4.1 if tool-heavy. Guessing "2x" systematically under-sizes exactly the steps that need margin. These alphas are tangents at the operating point; a reader measuring the secant over an observed n range will get a slightly larger number. Secant check against the exact model: research n 7 to 14 costs 3.66x (tangent predicts 2^1.81 = 3.51); browser n 13 to 26 costs 4.06x (tangent predicts 2^2.03 = 4.08).

Corollary: **doubling allowed iterations roughly quadruples the money ceiling.** "Let us bump max iterations a bit" is a 4x budget decision.

That also makes an iteration cap a poor money cap. At a platform default of 100 max iterations, the browser archetype's ceiling is 40,374,000 prompt tokens = $101.11 for one step (versus $1.667 expected), and the research archetype's is 15,496,000 tokens = $46.62 (versus $0.266). As computed data points rather than a range: 7.7x the expected n leaves 61x money headroom on the browser step; 14.3x leaves 175x on the research step.

### The enforceability floor

Because the per-agent projection needs two samples, and self-denies when one iteration exceeds \`budget/3\`, the granularity ratio must satisfy:

\`\`\`
g = B_step / cost_of_one_iteration  >=  3
\`\`\`

so the floor for a per-step budget is 3x the worst-case iteration. Against the model's unbounded worst-case iteration, none of the five budgets clears it: classify $0.0009 vs floor $1.04 (g = 0.003), RAG draft $0.0386 vs $1.56 (g = 0.074), research $0.797 vs $4.68 (g = 0.51), long-doc $0.1195 vs $1.39 (g = 0.26), browser $5.00 vs $8.76 (g = 1.71).

**Table 5: The enforceability floor** (illustrative catalog prices and context windows; substitute your own)

| Model class | Worst-case iteration, unbounded context | Minimum enforceable budget (3x) | Worst-case iteration under 30K/2K admission caps | Minimum enforceable budget under caps |
|---|---|---|---|---|
| flash-lite | $0.348 | $1.04 | $0.0105 | $0.032 |
| haiku | $0.520 | $1.56 | $0.040 | $0.120 |
| flash | $0.464 | $1.39 | $0.014 | $0.042 |
| sonnet | $1.560 | $4.68 | $0.120 | $0.360 |
| gpt-5.4 | $2.920 | $8.76 | $0.105 | $0.315 |

Any per-step money cap below the unbounded-column floor is bookkeeping, not enforcement. The fix is **admission caps on the inputs**, not a bigger budget: capping admitted prompt at 30K tokens and \`max_tokens\` at 2K collapses the floor 13 to 33x.

But admission caps change the step's own cost profile, so \`B_step\` has to be re-derived under them, and the caps have to be compatible with the step in the first place:

- **Research step**: compatible as written. Its largest iteration prompt is about 21K, under the 30K cap, so its budget survives unchanged and g rises from 0.51 to 6.6.
- **Browser step**: breaches 30K at roughly iteration 4 (each snapshot adds 8,120 tokens). Trim to the last three snapshots and the largest iteration falls to about 26K, expected cost to $0.754, the S=3 budget to $2.26, and g to 21.5.
- **Long-document step**: a 30K prompt cap refuses it outright, since its single iteration is 120K tokens. Capping admitted prompt at its own input size still leaves g = 2.8, below the floor. Its n is fixed at 1, so the control there is input size itself, not a money cap.

The two-regime rule: steps below the crossover are bounded by construction (n fixed at 1 or 2, small \`I\`) and should be controlled by capping inputs; steps above it are the only ones where a money cap does real work. **Cap inputs on cheap steps, cap money on expensive ones.**

One clarification about what "off by default" means in this implementation, because it is easy to get backwards. The worst-case ceiling is **always on** for any model whose catalog row carries a context window and max output tokens: both guards take \`max(growth, lastDelta*2, worstCase)\` unconditionally. What ships off by default is the separate fail-**closed** behaviour for models *missing* that metadata (\`requireCtxWindow\`). The documented reason is a migration window: legacy pricing snapshots without those columns would otherwise deny every chat turn.

### Quantiles, samples, and compounding false kills

Choosing the quantile is choosing the false-kill rate. If \`B_step\` is the q-quantile of observed legitimate cost, the per-step false-kill rate is exactly \`1-q\` by construction. That is a product decision, not a statistics decision.

Reconcile that with the safety factor before you use both: the S formula above is the estimator you use when the *cost* tail is unmeasurable but the *n* tail is known. The q you pick for the quantile and the \`n_q\` you feed into S must be the same quantile. For a fixed-n step, \`n_q / n_p50 = 1\` and S degenerates to 1, so the quantile has to come from input-size variance instead.

Sample size before you can quote a tail quantile: \`1/sqrt(n(1-q))\` is the relative standard error of the tail *exceedance count*, so a plus-or-minus 30% estimate of that count needs \`n ~ 11/(1-q)\`. p90 needs about 111 runs, p95 about 220, p99 about 1,100, p99.5 about 2,200. Treat those as lower bounds: the error on the quantile's dollar *value* depends on the density in the tail, and for a right-skewed cost distribution it is materially worse. Below roughly 200 runs you cannot honestly claim a p99, and should size from the structural worst case instead.

Per-step false kills compound. With k steps each capped at their own p99, and assuming independent per-step costs and that every run executes all k steps, the fraction of runs hitting a cap somewhere is \`1 - q^k\`:

\`\`\`
k = 3   ->  3.0%
k = 10  ->  9.6%
k = 20  -> 18.2%

A p95 per-step cap across 10 steps kills 40.1% of runs.

To hit a 1% run-level target:  q_step = (1 - target)^(1/k)
  k=3  -> p99.666 | k=10 -> p99.900 | k=50 -> p99.980
\`\`\`

Positive correlation across steps (one oversized run-level input inflating several at once) lowers the true rate, so treat these as the pessimistic end.

Size off the distribution, not the mean: per-step cost is right-skewed, the mean sits around p70, and sizing off it kills roughly 30% of legitimate step executions, which is \`1 - 0.7^k\` of runs.

Collect five fields per step execution: prompt tokens, completion tokens, tool-call count, model id, terminal stop reason. Four of the five are exactly what a pre-iteration guard already consumes, so if you can enforce you can measure.

For calibrating n, two independent anchors: Anthropic's own scaling rules (simple fact-finding 1 agent with 3 to 10 tool calls; direct comparisons 2 to 4 subagents with 10 to 15 calls each; complex research more than 10 subagents), and an average GitHub-issue-fixing trajectory reaching a peak context of 48.4K tokens after 40 steps, with about 1.0M tokens accumulated across the trajectory ([arXiv 2509.23586](https://arxiv.org/html/2509.23586v1)). Against those, the widely reported 25-superstep default (still the langgraph-sdk schema default, roughly 12 tool calls in a ReAct loop) kills real work, while a 200-step cap does nothing.

**The procedure:**

1. Collect step executions with the five fields.
2. Compute the required per-step quantile from your run-level target: \`q_step = (1 - target)^(1/k)\`.
3. Check whether your sample supports it: you need at least \`11/(1-q_step)\` runs, which at k=10 and a 1% run-level target is roughly 11,000. **If it does not, stop here and size from the bounded structural worst case (Table 5) instead.** Use the quantile you *can* estimate only to detect that the structural cap is far too loose, not to set the cap. This is the common case, and pretending otherwise is how a p95-sized cap ends up killing 40% of runs.
4. If the sample does support it, measure alpha by regressing \`log(cost)\` on \`log(n)\`, and set \`B_step\` at \`q_step\`.
5. Check \`g >= 3\` against the **bounded** worst-case iteration. If it fails, add admission caps rather than raising the budget, and re-derive \`B_step\` under those caps.
6. Set the run cap (next section).
7. Deliberately over-feed one step and confirm the stop reason fires.

## Run caps, fan-out, and why summing the step caps is wrong

The correct run bound is over node **executions** on the worst-case path, not over nodes:

\`\`\`
B_run = max over execution paths P of  sum over nodes v in P of  m_v * B_v

m_v = M inside a split of width M
    = L for a loop body
    = 1 otherwise

Exclusive branches contribute max, not sum.
\`\`\`

Summing per-step caps is wrong three ways, and they point in opposite directions:

1. **It under-counts by exactly M on the fanned subgraph.** A 3-node pipeline summing to $0.837 has a real worst case of $41.78 when the last two nodes sit inside a 50-wide split.
2. **It over-counts exclusive branches** that can never both execute.
3. **It is statistically unreachable.** For 10 independent lognormal steps with \`p99/p50 = 3\`, each capped at 3x its median, the sum of caps sits around 1.88x the true p99 of the run total. Treat that multiplier as directional: real step costs are partially correlated, which shrinks the gap. A cap that essentially cannot fire also cannot catch a moderate structural failure.

**Pooling.** An independent fan-out needs a *smaller* relative margin, by \`sqrt(M)\`:

\`\`\`
S_run = 1 + (S_step - 1) / sqrt(M)

S_step = 3:  M=5 -> 1.89 | M=10 -> 1.63 | M=50 -> 1.28 | M=200 -> 1.14
\`\`\`

Worked: 50 branches of the research step (mean $0.2657) sized naively as \`M * B_step\` = $39.85, but the pooled cap is $17.00, a 2.3x tighter run cap at the same risk. State the independence assumption loudly: if branch cost is driven by a run-level property such as one oversized input fanned to every branch, the costs are fully correlated and the saving vanishes entirely.

Per-step and per-run caps have different jobs, which is what sets their sizes. The step cap is tuned, expected to fire occasionally, and truncates one step's output. The run cap is a circuit breaker that should fire approximately never, and every firing is an incident to investigate (M exploded, a loop re-entered the fan-out, an input was 100x normal).

**Fan-out needs admission control, not interception.** A run cap enforced as a running check kills branches mid-flight and yields a nondeterministic partial result set: 50 branches at $0.836 each under a $10 run cap complete 11 of 50; under $20, 23 of 50; and which ones win depends on start order. Reserving the full \`M * b\` before spawning turns that into "refused to run", which is explicit and retryable.

The reservation mechanism, as implemented in \`BudgetReservationService\`:

- At spawn, the child's requested amount is reserved atomically on **every ancestor** in the caller chain inside one transaction. The first refusal throws, so the transaction rolls back every earlier ancestor's update. No manual compensation exists.
- The invariant bought: the sum of \`consumed\` across all descendants of A stays within A's budget at every depth, with no runtime tree walk on the hot path.
- Each per-ancestor reservation is a single conditional SQL UPDATE with no SELECT-then-UPDATE, so there is no TOCTOU. It increments the reserved column only when the ancestor's free budget covers the request:

  \`\`\`
  free = credit_budget - credits_consumed - credits_reserved
  UPDATE ... SET credits_reserved = credits_reserved + :req
   WHERE id = :ancestor
     AND (credit_budget IS NULL OR free >= :req)
  \`\`\`

  Success is decided by the returned row count being 1. An unlimited ancestor matches with a no-op write and also returns 1.
- Reservation sizing: an explicit request wins (negative rejected); otherwise the default is the **minimum free budget across every ancestor**, or zero if every ancestor is unlimited.
- Settlement walks the same chain once at child termination and, per ancestor, refunds the held reservation and books the actual cost in a single update, writing \`consumed\` and \`consumed_from_subagents\` with the same delta in the same transaction so the invariant holds by construction. The reservation columns are marked non-updatable at the ORM level so a dirty flush cannot silently rewrite them.
- Leaked reservations are swept **at boot**, not by a timeout: a stateless worker cannot own any reservation predating it, so every non-zero held reservation present at startup is definitionally orphaned and is cleared in one update. The sweep must never fail startup.
- The caller chain itself lives on a reserved key of the credentials map, nearest-first, absent for root invocations (so the cascade is a no-op at the root) and prepended by the spawning agent for each child.

Independent evidence that the lock, not the number, is what makes a cap real: in a controlled experiment reported in a 2026 preprint cataloging 63 incidents ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), a racy asyncio Python budget counter overshot the cap 30 out of 30 times, while a properly locked Python counter and a Rust affine-typed budget each overshot 0 out of 30.

The parent sizing constraint that follows: to spawn M children each holding cap b, the parent needs **free** budget of at least \`M * b\` at spawn time, not the expected total. For the research archetype at M=50 and b=$0.797, the parent needs $39.85 free even though the run will actually cost around $13.3. Size a parent on expected spend and only \`1/S\` of the branches get funded: at S=3, free budget of $13.29 divided by a $0.797 reservation funds 16 of 50 spawns and refuses 34.

## Reading the logs: symptom to wrong dimension

**Table 6: Symptom to the budget dimension that is wrong**

| What you see | Which dimension is wrong | Confirming signal | Where it is treated |
|---|---|---|---|
| Invisible kill: normal-looking completions, systematically shorter output | Terminal response / observability | Stop-reason distribution shows partial stops while the persisted status reads COMPLETED | Companion article, the moment of impact |
| Too tight: budget stops at iteration 2 or 3 | Sizing quantile / safety factor | Killed runs' token counts sit near p50, inputs look ordinary | The safety factor is derived |
| Decorative: zero budget stops over a long window | Cap magnitude | No denials in the observed window; max observed run cost far below the cap | Companion article, the opening test |
| Overshoot / late cap: realized cost exceeds the cap by a consistent, model-sized amount in the tail | Enforcement point / projection | Worst exactly where the step is most expensive | Companion article, on the one-iteration gap |
| Wrong scope binding: denials are ~100% the coarse level | Scope / guard ordering | Per-step caps never bind | Companion article, the five parts of a budget object |
| Fan-out starvation: N branches spawned, fewer than N executions | Parent sizing / reservation policy | Denials attributed to the parent's reservation, not the child's budget | Run caps and fan-out |
| Reservation leak: rising refusals over days, same inputs and same M | Reservation lifecycle | Held reservations never settled | Run caps and fan-out |
| Wrong unit: identical iteration counts, order-of-magnitude cost spread | Unit | Mean per-iteration cost spans ~430x across archetypes ($0.00030 classify vs $0.128 browser) | Sizing a per-step budget |

A configured budget is not an enforced one, and there is shipped evidence. LiteLLM accepted \`max_budget\` and \`budget_duration\` on models added dynamically through its API, persisted the values, and never enforced them, while the identical config in the startup file worked ([issue #25799](https://github.com/BerriAI/litellm/issues/25799), closed by a later PR). A sibling defect covered budgets that never reset after their duration expired ([#25495](https://github.com/BerriAI/litellm/issues/25495)). Assert enforcement in a test. Do not assume it from the presence of a field.
`;

export default content;
