// Markdown body for "Give every agent a budget it cannot exceed". Plain string module.
const content = `Most AI cost horror stories share one root cause: an agent with no ceiling. It looped, it retried, it dragged a huge context around, and nobody found out until the bill did. The fix is not a smarter model. It is a hard budget on every agent, enforced before the spend happens, not after.

## Why unbounded agents are a financial risk

An autonomous agent decides its own next step. That is the feature, and it is also the exposure. Three failure modes turn a normal task into an open tap.

**Loops.** The agent tries something, it does not work, it tries a variation, and it keeps going. Without a limit it will burn calls chasing a goal it cannot reach.

**Retries.** A flaky tool or a rate limit triggers a retry. Retries stack. What looked like one call becomes twenty, each one paying full context cost.

**Long contexts.** Every model call resends the whole conversation so far. A task that accumulates history pays more on each step than the one before. The last call in a long run can cost many times the first.

None of these are rare. They are the normal behavior of a system given a goal and no ceiling. A budget turns that open-ended risk into a known, capped number.

## What a per-agent budget should cap

A budget is only useful if it stops work when it is hit. It should cap the things that actually drive cost and runtime:

- **Total spend.** A hard ceiling in credits or tokens. When the agent reaches it, it stops. No overrun, no "just a little more."
- **Number of model calls.** Caps the loop directly. An agent that cannot make a twenty-first call cannot loop forever.
- **Tool calls.** Some tools cost money or hit external quotas. Cap how many times an agent can reach for them.
- **Wall-clock time.** A stuck agent should not run for an hour. Time it out.

The rule that makes a budget real: when the cap is reached, the agent halts and the workflow handles it. It does not silently keep going, and it does not fail quietly. It stops, and the run records that it stopped because it hit its budget.

## Scope the tools and data an agent can touch

Budget is half the answer. Scope is the other half, and it lowers cost before any cap is needed.

An agent that can see everything will try to use everything. Give it the whole database and it reasons over the whole database, and you pay for the tokens. Give it only the tools and the data the step needs, and it stays small by construction.

For a classify step, that means the message text and a tool to return a label. Nothing else. For a draft step, the message and the category. A tightly scoped agent is cheaper on every call because its context is small, and it is safer because it cannot wander into data or actions that are not its job.

Scope narrows the blast radius. Budget caps what is left. You want both.

## Set budgets per agent and per run

One number is not enough. You need budgets at two levels.

**Per agent.** Each step gets its own ceiling sized to its job. A quick classification should have a tiny budget. A research step that reads several documents gets more. Sizing each agent to its actual work means one greedy step cannot spend the whole allowance.

**Per run.** The whole workflow also gets a ceiling. Even if every individual agent stays within its own budget, a run that fans out into hundreds of parallel branches can add up. A run-level cap protects against the sum, not just the parts.

Together they give you a predictable envelope: a known worst case per step and a known worst case for the run. That is what turns "AI cost" from a surprise into a line item you can plan around.

## Monitor spend per agent and per tool

Budgets stop runaway cost. Monitoring tells you where cost actually lives so you can tune it.

Track spend at a fine grain:

- **Per agent.** Which step costs the most? Often it is one node doing more than it needs to, carrying too much context, or using a bigger model than the task requires.
- **Per tool.** Which tool calls dominate? A single expensive external API called on every item can quietly become the bulk of the bill.
- **Per run.** What does a typical run cost, and what does a bad one cost? The gap between them is where your loops and retries hide.

With this view you tune deliberately. Trim a step's context. Drop it to a cheaper model where quality allows. Add a deduplication guard so a tool is not called twice for the same item. Each change is measurable because you can see the number move.

## Put it together

Runaway AI cost is a design problem, not a modeling problem. You solve it structurally.

Scope each agent to the tools and data its job needs. Give each agent a hard budget it cannot exceed. Put a ceiling on the whole run. Watch spend per agent and per tool, and tune where the money actually goes.

Do that and cost stops being the thing that keeps you from shipping. It becomes a number you set on purpose, enforce automatically, and can defend line by line.
`;

export default content;
