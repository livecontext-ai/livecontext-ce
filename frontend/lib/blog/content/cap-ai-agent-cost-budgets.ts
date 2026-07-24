// Markdown body for "The budget that actually stops the agent". Plain string
// module (see the-niche-data-advantage.ts for the rationale).
//
// This post and size-an-ai-agent-budget.ts are two halves of one piece of
// research: this one is ENFORCEMENT (what a budget object is made of, why an
// in-run cap can only stop the call AFTER the expensive one, what each stack can
// actually enforce), the other is SIZING (what number to put in the box). They
// cross-reference each other; keep both links working if either is edited.
//
// The cost model itself is deliberately NOT re-derived here. That is
// workflow-beats-do-everything-agent.ts. One sentence and a pointer, no more.
//
// Vendor facts (OpenAI soft-by-default spend limits and the opt-in hard toggle,
// Anthropic's Enterprise-only Spend Limits API and its "informational, not
// transactional" caveat, the per-tier monthly caps, framework defaults and
// terminal behaviours) were verified against live documentation on 2026-07-22.
// Framework defaults move often: re-verify Table 3 before any material edit.
//
// Product figures come from this repo:
//   backend/agent-service/.../budget/  (guard chain, projection, reservations)
//   mcp/bridge/lib/budgetGuards.js + shared/contracts/budget-guard-fixtures.json
//   shared/contracts/agent-stop-reason.json
const content = `## An alert is not a ceiling

A monitor is asynchronous and post-hoc: it tells you what you already spent, so it cannot be the enforcement layer. A ceiling is synchronous and pre-execution: it refuses the next call. Reconciliation and stop-reason telemetry still matter, but for sizing the cap and detecting one that is too tight, not for stopping work.

Here is the test to run before reading further, and it needs no threshold: pull the denial records for your configured cap over the last observation window. Has it ever refused anything? A number that has never denied anything is not a control, it is a comment.

Provider-level caps are backstops, not first-line enforcement:

- OpenAI's project and org spend limit is a **soft budget by default**: it notifies and requests keep flowing. A hard ceiling exists, but as a separate opt-in toggle, which then returns HTTP 429 until the limit is raised or resets ([spend limits guide](https://developers.openai.com/api/docs/guides/spend-limits)).
- Anthropic's [Spend Limits API](https://platform.claude.com/docs/en/manage-claude/spend-limits-api) is Claude Enterprise only, explicitly unavailable to Claude Platform (Console) organisations, and supports \`monthly\` as the only period (resetting 00 UTC on the first of the calendar month). It caps human seat usage, not agent API spend.
- Anthropic's docs also disqualify provider spend as a gate: \`period_to_date_spend\` "may read as '0' if the spend reading is temporarily unavailable; treat it as informational, not transactional."
- Anthropic does enforce a monthly cap per usage tier (Start $500, Build $1,000, Scale $200,000, Custom uncapped) that pauses API usage until the next month ([rate limits](https://platform.claude.com/docs/en/api/rate-limits)). A real ceiling, but org-wide and monthly: one runaway run can consume it and convert a cost bug into an org-wide outage.

Per-step cost grows super-linearly because context accumulates, which is why counting steps does not bound dollars. That derivation lives in the companion cost-model article. For sizing inputs only, Anthropic reports that agents use roughly 4x the tokens of chat and multi-agent systems roughly 15x ([multi-agent research system](https://www.anthropic.com/engineering/multi-agent-research-system)).

**Disclosure.** The implementation details, constants and denial messages below come from LiveContext's \`agent-service\`, the platform this blog belongs to. Read them as one system's choices, verifiable in its community-edition source, not as surveyed field practice.

## The five parts of a budget object

A budget is not a number. It is an object with five parts, and a budget missing any one of them fails in a specific, diagnosable way.

**1. Scope.** The level the ledger is kept at. Four exist in this shipped system: tenant/account balance (macro), agent/step (micro), \`parent_reservation\` (an ancestor in the caller chain refuses to fund a child spawn), and per-run/per-epoch. A denial that does not name which scope fired is undebuggable.

**2. Unit.** Dollars, tokens, or mere counts (turns, supersteps, tool calls). Counts float in money terms. Only tokens or money is a budget.

**3. Enforcement point.** Post-hoc reconciliation, pre-iteration projection, pre-spawn reservation, or admission cap on inputs. Each has a different overshoot bound (Table 1).

**4. Reservation policy.** Whether the budget is decremented after the fact or held before the work starts. This is the only part that makes parallel fan-out safe.

**5. Terminal response.** What the caller receives at the instant the cap is hit. Five distinct behaviours exist in the wild and they are not interchangeable.

**Table 1: Enforcement points and their overshoot bound**

| Enforcement point | When it runs | What it can refuse | Worst-case overshoot | Safe for parallel fan-out? |
|---|---|---|---|---|
| Post-hoc reconciliation / alerting | After the call settles | Nothing | Unbounded | No (detection, not enforcement) |
| Pre-iteration projection | Before the next model call | The next iteration | One iteration (up to 40x the first iteration on a browser step) | No |
| Pre-spawn reservation | Before a child starts | The whole child | Zero for the child | Yes |
| Admission cap on inputs | Before the prompt is assembled | Oversized context / output | Bounds the iteration itself | Yes (composes with the others) |

Two design decisions people treat as configuration but which belong to the object:

**Guard ordering is scope design.** This implementation runs exactly two guards, \`TenantBudgetGuard\` then \`AgentBudgetGuard\`, first-deny-wins with short-circuit, for two documented reasons: tenant exhaustion makes agent budget moot, and the tenant guard is placed first as an early reject before the downstream credit-reservation round trip.

**Period is a sizing decision.** A cumulative accumulator makes the cap a lifetime total, so a long-lived agent silently approaches exhaustion over months. Weekly or monthly resets make the same number a rate. Resets can be resolved lazily at execution start with a compare-and-set update rather than by a scheduler (\`BudgetResolver\` modes: cumulative, weekly, monthly; unknown values treated as cumulative).

One semantic trap worth checking in your own stack: this platform's agent-facing tool-parameter help still reads "Each LLM iteration costs 1 credit" while the guard compares a monetary projection against that same \`credit_budget\` field. Two other help strings hedge it as "at least one credit" and "more than 1 credit in practice", so the docs contradict each other as well. A rule of thumb in the docs and a monetary comparison in the code is a bug class, not a wording nit.

## You cannot stop the call you are already making

Token consumption is only known after a call completes. No in-run budget can prevent a single expensive call from busting the cap; it can only prevent the next one. The realized worst case is therefore **budget plus one iteration**, not budget. Say that plainly instead of implying a hard cap.

The gating formula as stated in the shared cross-language fixture file:

\`\`\`
projectedNext = max(
    growthProj,
    lastDeltaProj * LAST_DELTA_SAFETY_FACTOR,
    worstCaseSingleIter
)
deny iff (runCostSoFar + projectedNext > balance)
      OR (runCostSoFar >= balance)

LAST_DELTA_SAFETY_FACTOR = 2.0
RATE_DIVISOR             = 1000
ROUND_DECIMALS           = 6 (HALF_UP, per subterm)
\`\`\`

Two caveats before you copy it. The two Java guards implement \`>=\` on the projection comparison, not \`>\`; the JS twin implements \`>\`. At exact equality of the projected total they disagree, and no fixture case sits on that boundary. And the agent-scope comparison is not two terms but four:

\`\`\`
totalProjected = consumedBeforeRun
               + creditsReserved
               + runCostSoFar
               + nextProjected
deny iff totalProjected >= totalBudget
\`\`\`

\`creditsReserved\` is credits currently locked by in-flight sub-agents, so a parent's own loop is throttled by what its children are holding.

Each projection branch is non-redundant:

- \`growthProj\` (average tokens per completed iteration) catches a steady ramp.
- \`lastDeltaProj\` (last iteration's delta times 2) catches a burst that an average dilutes.
- \`worstCaseSingleIter\` (full context window times full max output at the model's rates) is invariant to the growth pattern and catches a step-function jump on iteration 1.

The worst-case branch does the real work. At opus-class pricing (15 / 75 USD per 1M) with a 200K context and 64K max output:

\`\`\`
worstCaseSingleIter = 200 * 15 + 64 * 75
                    = 3,000 + 4,800
                    = 7,800 credits      (1 credit = $0.001)
\`\`\`

Any balance below 7,800 credits is protected against that burst iteration by the worst-case branch and by nothing else.

The second deny condition, \`runCostSoFar >= balance\`, is logically redundant: whenever the projection is positive, the first condition already covers it. It exists purely so the denial names the real failure mode instead of surfacing as a projection overshoot.

The cost formula, for reproducibility:

\`\`\`
inputCost  = inputRate  * promptTokens     / 1000
outputCost = outputRate * completionTokens / 1000
total      = inputCost + outputCost + fixedCost
\`\`\`

Rates are USD per 1M tokens; the \`/1000\` converts to a credit unit where 1 credit = $0.001. Round each subterm to 6 decimals before summing, or two implementations of the same formula will drift.

Three honest constraints on this mechanism:

**The per-agent guard needs two completed iterations.** With one sample, \`lastDelta == runCost == growth\`, so \`lastDelta * 2 = 2 * runCost\`, and any first iteration consuming more than \`budget/3\` would self-deny iteration 2 even when the next call is legitimately small. The tenant guard has no such gate: it projects from iteration 1, where growth and lastDelta are both zero, so only the worst-case branch binds there. That is by design, and it is why the iteration-1 ceiling belongs to the worst-case branch.

**Staleness compounds the gap.** A balance re-fetched every 5 iterations (dropping to every iteration when cost rates are unreliable) adds a staleness window on top of the one-iteration projection gap. An adaptive variant refreshes every iteration once burn rate exceeds 70% of balance.

**The unknown-model fallback is a real decision with a bug history.** Fail pessimistic on rates (fall back to the highest tier, 15 / 75 USD per 1M) but lenient on the ceiling (leave context window null so \`worstCase\` returns null and the guard drops to growth-only). A prior fallback of 0.015 / 0.075 silently bypassed the guard entirely.

The guard's own comments carry the admission: an atomic per-turn reservation layer was prototyped and reverted, because an overshoot of at most one iteration was judged acceptable in exchange for a simpler call path. And the pre-check is explicitly "a snapshot, not authoritative": post-execution reconciliation still runs, and the two can disagree.

## The moment of impact: what the caller actually gets

A budget kill is classified as \`PARTIAL\`, not \`FAILURE\`, in this platform's stop-reason contract: usable-but-truncated output. It does not raise, and a token-producing budget kill is persisted with execution status \`COMPLETED\`, so only the \`stop_reason\` column carries the detail. Two qualifications, because half-truths here are how a too-tight cap stays invisible: a zero-token budget kill is persisted as \`FAILED\`, and the daily metrics rollup does count every budget-stopped run into its failure count. What is genuinely invisible is the shape of the damage, not the fact of it. If you watch only error rates, a too-tight cap surfaces months later as a quality regression.

**Table 2: Where each stop reason is decided (6 of the contract's 10 values)**

| Stop reason | Terminal category | Where it is decided | What the caller must do |
|---|---|---|---|
| \`MAX_ITERATIONS\` | partial | Post-hoc, after the loop exits | Treat output as truncated; raise n or budget |
| \`TIMEOUT\` | partial | Post-hoc, after the loop exits | Actively working, past wall clock; resume or widen |
| \`BUDGET_EXHAUSTED\` | partial | Pre-iteration guard, before the call | Read \`budgetScope\` (\`tenant\`, \`agent\`, \`parent_reservation\`, \`browser\`), decide refill vs resize |
| \`LOOP_DETECTED\` | partial | Mid-iteration, after tool calls are parsed | Inspect the repeated signature; the task is malformed |
| \`STOPPED_BY_USER\` | partial | Cancel channel | Keep partial output |
| \`INACTIVITY_TIMEOUT\` | failure | Watchdog, not the loop; a post-pass reclassifies \`STOPPED_BY_USER\` | Went silent, had to be killed; investigate the hang |

\`BUDGET_EXHAUSTED\` is the only value carrying a scopes array. A budget stop that does not tell you which ceiling fired forces you to guess.

Denial should not be an exception. A workable implementation breaks out of the loop and records structured metadata: the stop reason, plus \`budgetScope\`, plus a \`denialReason\` string naming which projection branch fired:

\`\`\`
tenant balance X would be exceeded
(run=A + next=B [growth=..., lastDelta=..., worstCase=...])
\`\`\`

Use the same keys on the sync and streaming paths so the metrics cannot drift.

Across the surveyed field, five terminal behaviours exist and they are not interchangeable:

1. **Exception**: \`MaxTurnsExceeded\` (OpenAI Agents SDK), \`GraphRecursionError\` (LangGraph), \`UsageLimitExceeded\` (Pydantic AI), \`ModelCallLimitExceededError\` (LangChain).
2. **Typed branchable result**: AutoGen's \`stop_reason\` on the \`TaskResult\`, the Claude Agent SDK's \`error_max_budget_usd\` subtype, LangChain's \`exit_behavior='end'\` with an injected AI message.
3. **Silent truncation with HTTP 200**: Anthropic's \`max_tokens\` sets \`stop_reason: "max_tokens"\` and returns success ([Messages API](https://platform.claude.com/docs/en/api/messages)).
4. **HTTP 429 rejection**: OpenAI's opt-in hard limit. Anthropic documents 429 only for \`rate_limit_error\` and puts billing problems at 402, so no status code is documented for its monthly tier spend cap; confirm that one against your own logs.
5. **Best-effort degraded answer**: CrewAI's \`max_iter\`, where the agent "must provide its best answer" ([CrewAI agents](https://docs.crewai.com/en/concepts/agents)).

A semantics conflict worth checking in your own stack: [LiteLLM's iteration budgets](https://docs.litellm.ai/docs/a2a_iteration_budgets) return 429 with error type \`budget_exceeded\`, and by HTTP convention 429 means retry later. For a time-resetting org cap that is defensible, since waiting does eventually make the request satisfiable. For a per-run or per-agent budget it is wrong: waiting never satisfies it, and standard SDK retry logic will hammer the wall. LiteLLM is the one confirmed instance here, not a proven class. Check whatever your client's retry policy does with a 429.

What should survive the stop is the other half of the contract. The [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/agent-loop) is the closest thing to a reference design: the \`result\` field (the final answer) is present only on the \`success\` subtype, but every error subtype still carries \`total_cost_usd\`, \`usage\`, \`num_turns\` and \`session_id\`. You lose the answer, not the session. Note the asymmetry: a single-shot \`query()\` raises after yielding the error result, while a streaming-input session stays alive.

Why this matters commercially, from an [incident report](https://github.com/anthropics/claude-code/issues/68430): the operator's only options were to "let it run and watch it burn the session budget on a recursive loop that will never succeed" or "kill it and lose everything, including legitimate work completed by early agents." A cap that discards partial work converts a cost problem into a total-loss problem, which is precisely why operators disable caps.

A parent-side refusal should follow the same rule: not a thrown error but a synthesized failure result naming the ancestor and the scope.

\`\`\`
Cannot spawn child 'X': ancestor agent <id> has
insufficient free budget for reservation N
(scope=parent_reservation, BUDGET_EXHAUSTED)
\`\`\`

Finally, make the cap introspectable from inside the agent. The shipped response shape:

\`\`\`
budget.{ unlimited, total, consumed,
         consumed_own, consumed_from_subagents,
         reserved_for_subagents, free,
         reset_mode, last_reset }

free = max(total - consumed - reserved_for_subagents, 0)
\`\`\`

On the unlimited branch \`total\` and \`free\` are null and \`reserved_for_subagents\` is returned as 0. The explicit rule: if \`free\` is below a child's budget, the spawn fails with \`scope=parent_reservation\`.

## What each stack can and cannot enforce

**Table 3: What each stack can actually enforce** (scoped to the platforms surveyed; Google ADK and LlamaIndex were not)

| Stack | Unit enforced | Default value | Behaviour at the ceiling | Propagates to sub-agents? |
|---|---|---|---|---|
| [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/python) | USD per run (\`max_budget_usd\`), plus turns | Both unlimited | Typed result subtype \`error_max_budget_usd\` / \`error_max_turns\`, session preserved | \`usage\` excludes subagent tokens; \`total_cost_usd\` includes them |
| Anthropic Messages API | Tokens (\`max_tokens\`) | No default; you must set it | HTTP 200 with \`stop_reason: "max_tokens"\`, truncated | N/A |
| OpenAI (account) | USD per month | Soft by default | Notification, or 429 if hard limit opted in | N/A |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/running_agents/) | Turns ([\`DEFAULT_MAX_TURNS = 10\`](https://github.com/openai/openai-agents-python/blob/main/src/agents/run_config.py)) | 10 | Raises \`MaxTurnsExceeded\` | Not documented |
| [LangGraph](https://docs.langchain.com/oss/python/langgraph/graph-api) | Supersteps (\`recursion_limit\`) | Docs conflict: 1000 since v1.0.6 in the OSS graph runtime, 25 in the SDK \`Config\` schema and field reports | Raises \`GraphRecursionError\` | Two documented propagation bugs (below) |
| [LangChain middleware](https://reference.langchain.com/python/langchain/agents/middleware/model_call_limit/ModelCallLimitMiddleware) | Call counts only, no token or cost budget | Both limits \`None\` | Configurable: \`exit_behavior='end'\` injects a message, \`'error'\` raises | Not applicable |
| [Pydantic AI](https://pydantic.dev/docs/ai/api/pydantic-ai/usage/) | Tokens, requests, tool calls | \`request_limit=50\`, token limits \`None\` | Raises \`UsageLimitExceeded\`; optional pre-flight check | Not documented |
| AutoGen ([conditions](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.conditions.html), [teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html)) | Tokens (\`TokenUsageTermination\`) | Team defaults: \`termination_condition=None\`, \`max_turns=None\` | Typed \`TaskResult\` with a \`stop_reason\` string | Team-scoped |
| [CrewAI](https://docs.crewai.com/en/concepts/agents) | Iterations (\`max_iter\`) | Docs say 20, source says 25 | Agent "must provide its best answer" | Not documented |

Five things this table says that prose would bury:

**Almost everything defaults to unbounded.** Claude Agent SDK \`max_turns\` and \`max_budget_usd\` are both no-limit; [AutoGen teams](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.teams.html) state plainly that the group chat "will run indefinitely"; Anthropic's Enterprise seat spend limits default to unlimited when no default exists at any level (the API tier caps, by contrast, always apply).

**The one cost knob in the survey with no default is Anthropic's \`max_tokens\`**, which the Messages API schema requires you to set explicitly. It is also the one whose breach returns HTTP 200 with truncated content. The schema now also documents setting it to 0 to warm the prompt cache, so mandatory does not mean meaningful ceiling.

**The survey's only per-run dollar ceiling is enforced against an estimate.** Anthropic's cost-tracking page warns that \`total_cost_usd\`, the exact figure \`max_budget_usd\` is compared against, consists of "client-side estimates, not authoritative billing data" computed from a price table bundled at build time, and says "Do not bill end users or trigger financial decisions from these fields." It is also evaluated between turns, so spend can exceed the configured limit by one turn. That is the same budget-plus-one-iteration guarantee, in the best-designed product in the field.

**LangChain has no token or cost budget at all.** \`ModelCallLimitMiddleware\` and \`ToolCallLimitMiddleware\` cap call counts, both default to \`None\`, and a maintainer [confirmed the token-budget gap in July 2026](https://forum.langchain.com/t/a-proposal-to-add-token-usage-budgets-to-langchain-agents-via-a-new-middleware-since-the-existing-limiters-only-cap-call-count-not-tokens/4147). Its \`exit_behavior\` parameter is nonetheless the cleanest configurable failure mode in the field and worth copying.

**Pydantic AI is the only stack with a pre-flight check**: \`count_tokens_before_request\` (default \`False\`) calls the provider's token-counting API to reject an over-budget request before it is billed. It also ships a trap: \`request_limit\` silently defaults to 50, so setting \`input_tokens_limit\` alone inherits a 50-request cap unless you pass \`request_limit=None\`.

**Propagation is the number one way a ceiling becomes decorative.** Two documented cases: [LangChain deepagents #1698](https://github.com/langchain-ai/deepagents/issues/1698), where \`SubAgentMiddleware\` invoked subagents without the \`config\` parameter so they always ran at the default recursion limit regardless of a parent set to 150; and [langgraphjs #1524](https://github.com/langchain-ai/langgraphjs/issues/1524), where \`withConfig\` \`recursionLimit\` is silently ignored and the resulting error message tells you to set the very key being ignored.

Two metering traps that silently defeat naive budget code, both from [Anthropic's cost-tracking doc](https://code.claude.com/docs/en/agent-sdk/cost-tracking): the \`usage\` field counts only the top-level loop and excludes subagent tokens (while \`total_cost_usd\` and \`model_usage\` include them), and parallel tool calls emit multiple assistant messages sharing one message id with identical usage, so a meter summing per-message usage double-counts and trips early. Deduplicate by id.

Rate limits are not spend limits and can reward the expensive path: cached input tokens bill at 10% but do not count toward input-token-per-minute limits on most models, and \`max_tokens\` does not factor into output-token-per-minute limits at all ([rate limits](https://platform.claude.com/docs/en/api/rate-limits)).

## Loop guards bound n; budgets bound cost given n

A loop detector and a budget answer different questions. The detector bounds how many iterations happen; the budget bounds what those iterations may cost. Neither substitutes for the other.

Real thresholds from a shipped detector, with two independent trigger conditions:

| Condition | Key | Escalation rungs | Hard stop |
|---|---|---|---|
| Identical calls | tool name + sorted arguments, hashed | warn at 5 | 15 |
| Consecutive calls | total tool calls, any signature | 15, 25, 35 | 40 |

The consecutive ceiling is deliberately high so legitimate batch operations are not killed. Both hard stops are per-agent configurable, and the intermediate rungs are **derived** (identical warn = \`ceil(stop/3)\` min 2; consecutive rungs = \`ceil(stop * 3/8)\`, \`5/8\`, \`7/8\`) so the severity ladder stays monotonic at any custom value, with minimum stops enforced.

The ladder is not just logging: each rung injects a message back into the agent's context before the stop, escalating from an informational note through "1 iteration left, STOP tools, RESPOND NOW" to termination. The stated design intent is that repetitive patterns should be automated as workflows rather than looped.

The coverage gap worth naming: that detector only counts four tool names. Every other tool call is invisible to both counters, so a loop over an untracked tool never produces \`LOOP_DETECTED\`. Check the equivalent coverage in your own stack before trusting a loop guard.

Do not rely on the model to notice its own waste. RedundancyBench annotated 200 trajectories (filtered from the successful runs collected) with over 8,000 annotated steps, and the best automated step-level detection of redundant steps scored 24.88% (72.50% at trajectory level) ([arXiv 2605.29893](https://arxiv.org/abs/2605.29893)). The cap has to be mechanical.

Other run-bounding defaults from the same implementation, as a reference point: max iterations 100, execution timeout 3600 s, max tokens 16,000 per turn, and a 5-minute inactivity watchdog whose per-agent override accepts only 0 (disabled) or 10 to 7200 seconds, so a stray value cannot arm a seconds-scale watchdog.

Wall-clock deserves a line as the cap of last resort. A documented incident consumed 4 million tokens in under 5 minutes ([claude-code #68619](https://github.com/anthropics/claude-code/issues/68619)), faster than any per-turn or balance-refresh sampling would react. That is inference from one incident, not sourced best practice, but the arithmetic is hard to argue with.

## The test for a real ceiling

Six points, each answerable from your own logs:

1. Does a denial name the scope that fired?
2. Is the check synchronous and before the next call?
3. Is the terminal response typed, non-retryable, and does it carry the cost ledger plus a resume handle?
4. Does the cap propagate to sub-agents, proven by a test that sets a parent limit and asserts a child inherits it?
5. Is the granularity ratio \`g\`, the budget divided by the bounded worst-case iteration, at least 3? The companion article on sizing derives that floor and shows that most per-step money caps fail it.
6. Has the cap ever actually denied, in the observed window?

The honest guarantee: an in-run budget bounds cost to **the budget plus one iteration**, not to the budget. A pre-spawn reservation is the only mechanism with zero overshoot, and it only covers the child.

If the same formula exists in two runtimes, parity is worth engineering. A shared fixture file of named cases consumed by both a JUnit parameterized test and a Node test runner is the cheapest way to stop the two from drifting, and rounding must be matched subterm by subterm. Note the limit: a fixture only covers the cases it contains. A fixture that primes explicit rates never exercises the unknown-model fallback path on either side, which is exactly where the two implementations described here diverged by an order of magnitude, and a fixture that instantiates only the tenant guard never notices that the two agent guards use different comparison operators.

State what is not known. No published base rate exists for how often production agents run away. The strongest catalog explicitly disclaims prevalence and claims only existence and recurrence across independently developed projects. Reason from mechanism and magnitude instead of inventing a frequency.

And be realistic about magnitude. Per the incident rows in the same 2026 catalog ([arXiv 2606.04056](https://arxiv.org/abs/2606.04056)), documented overruns cluster in the hundreds to low thousands of dollars: around $2,150 unintended spend in one case, $235 in four days by a single user, a 70% overshoot past an optimizer budget. Compare that with the most-republished runaway anecdote in the field, ["We spent $47,000 running AI agents"](https://todatabeyond.substack.com/p/we-spent-47000-running-ai-agents), which names no company, produces no invoice, repo, config or logs, and was then amplified under a second byline and through a dozen SEO posts citing each other. Its own weekly figures are $127, $891, $6,240 and $18,400, which sum to $25,658, not $47,000, and a four-week cost ramp contradicts the "11-day loop" in the same post. The real risk profile is silent, recurring and mid-four-figures.
`;

export default content;
