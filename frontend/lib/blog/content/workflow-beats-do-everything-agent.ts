// Markdown body for "Why a scoped workflow beats one big agent". Plain string module.
const content = `The demo of a single autonomous agent is always impressive. You give it a goal, it thinks, it calls tools, it comes back with an answer. Then you put it in production and the invoice arrives, the results wobble, and nobody can tell you why it did what it did.

The problem is not the model. The problem is the shape. One agent doing everything is the wrong shape for most real work.

## Cost: context is the meter

Every time an agent calls the model, it resends its context. The instructions, the history, every tool result so far. A do-everything agent accumulates all of that in one long conversation, and the context grows with every step.

You pay for that context on every call. A ten-step task does not cost ten small calls. It costs ten calls that each carry a growing pile of everything that came before.

A workflow breaks the job into scoped steps and feeds each one only what it needs. The classify step sees the message. The draft step sees the message and the category. The send step sees the approved draft. No step drags the whole history along.

Feed each agent a narrow slice instead of the whole transcript and the token count drops hard. In practice the same job runs about ten times cheaper. That is not a trick. It is the direct result of not paying to resend context that a given step never uses.

## Control: deterministic branching versus free-styling

A do-everything agent decides its own path at runtime. Sometimes it takes the right one. Sometimes it invents a new one. You are trusting a probabilistic system to make the same routing choice every time, and it will not.

A workflow makes the routing explicit. A billing question goes down the billing branch because the graph says so, not because the model felt like it this run. The fuzzy judgment (is this billing or a bug?) still happens inside a step. The structural decision (what happens to a billing item) is fixed.

That split is the whole point. Let the model do what only a model can do, which is read and judge. Do not let it improvise the parts you need to be reliable.

## Auditability: a path you can point at

When one agent does everything in a single loop, the record is a wall of reasoning and tool calls. Reconstructing what actually happened is archaeology.

A workflow gives you a run you can read. Here is the input. Here is the branch it took. Here is what each step received and returned. Here is the cost of each step. Here is who approved before it sent. When someone asks why a customer got a particular reply, you answer from the trail instead of guessing.

## Debugging: a bounded surface

A big agent that fails gives you one giant failure to stare at. Was it the plan, a bad tool result, a lost instruction twenty turns back? You cannot isolate it, because everything shares one context.

A workflow fails at a node. The draft step produced the wrong tone, so you open the draft step. Its inputs are right there. You change that step, rerun, and leave the rest untouched. Small, bounded, and repeatable, the way normal software debugging works.

## Be fair: when one agent is the right call

Scoped workflows are not always the answer, and pretending otherwise is its own kind of hype.

Reach for a single autonomous agent when:

- **The task is genuinely open-ended.** Exploratory research, or debugging where the next move depends entirely on the last result. You cannot draw the branches in advance because they do not exist yet.
- **The path is short and cheap.** A one-shot lookup or a quick draft does not need a graph. A graph would be overhead.
- **You are still discovering the shape.** Early on, let an agent roam and watch what it actually does. The stable parts of that behavior are exactly what you later lift into a workflow.

The honest rule: if you can draw the steps, build a workflow. If you genuinely cannot draw them yet, an agent is the right tool, for now.

## The hybrid: workflow orchestrates, agents do the fuzzy parts

The best production systems are not one or the other. They are a workflow with agents inside it.

The workflow owns the structure: the triggers, the branches, the merges, the approvals, the retries, the budget on each step. It is deterministic where determinism matters.

Inside individual nodes, agents do the work that needs judgment: classify this message, draft this reply, extract these fields, summarize this document. Each of those agents is scoped. It gets a clear input, a small tool set, a budget it cannot exceed, and it returns a clear output to the step that follows.

You get the cost profile of scoped steps, the reliability of explicit branching, and the reasoning of a model exactly where reasoning helps. The agent handles the fuzzy sub-task. The workflow handles everything else, and it ships as an app you can run, monitor, and hand to someone else.

Start by asking which parts of your job actually need judgment. Wrap those in scoped agents. Wire the rest as a graph. That is the shape that survives contact with production.
`;

export default content;
