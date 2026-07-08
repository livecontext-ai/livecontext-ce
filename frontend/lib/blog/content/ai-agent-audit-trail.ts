// Markdown body for "The audit trail every production AI agent needs". Plain string module.
const content = `An AI agent that works in the demo has proven exactly one thing: it can work once. Production asks a harder question. When it does something wrong, and it will, can you find out what happened and why? If the answer is no, you do not have a system you can run. You have a system you are hoping about.

The thing that turns hope into operation is an audit trail. A complete record of what the agent did, on every run, that you can read after the fact.

## Why "it worked in the demo" is not enough

A demo is a single happy path under a friendly input. Production is thousands of runs under inputs you never anticipated. Some fraction go wrong: a wrong classification, a tool that returned garbage, an action taken on the wrong record.

When one of those surfaces, usually as a complaint, you need to answer three questions fast. What did the agent see? What did it do? Why did it choose that? Without a trail you are reconstructing a decision from a probabilistic system after the fact, which is to say you are guessing.

A trail replaces the guess with a record. That is the entire difference between an agent you operate and one you merely deploy.

## What to log

An audit trail is only as good as what it captures. Log enough that a run can be fully replayed on paper, without rerunning it.

- **Inputs.** What actually entered the agent or the step. Not a summary, the real input. Most "the AI is broken" reports turn out to be bad or surprising input, and you cannot see that unless you logged it.
- **Each tool call and its result.** Every tool the agent invoked, with what it passed and what came back. Tool results are where reality enters the run, and where a lot of failures start.
- **Outputs.** What the agent produced at each step and at the end. The final answer, and the intermediate ones that led to it.
- **Cost.** Tokens and spend per step. This is your bill and your early warning for a step that is doing more than it should.
- **The branch or decision taken.** Which path the run followed. A billing item went down the billing branch: record that it did, so you can confirm the routing was right.
- **Who approved.** For any step gated by a human, log who approved, when, and what they saw when they did. Approvals are the backbone of accountability.

Capture those and any run becomes a story you can read start to finish.

## How the trail helps you debug

Debugging without a trail is staring at a bad output and theorizing. Debugging with one is following a path.

You open the failed run. You read the input and it looks normal. You move to the classify step and see it returned the wrong label. You check what it received, and the message was ambiguous in a way you had not considered. The fix is now obvious: sharpen the classify instructions or add a branch for that case. You found it by reading, not by rerunning the whole thing twenty times hoping to reproduce it.

A per-step trail also localizes the problem. You know which node failed, so you change that node and leave the rest alone. The trail turns a vague "the agent is wrong" into a specific, fixable step.

## How the trail helps compliance and trust

Some work has to be explainable to someone outside the team: a customer, an auditor, a regulator, your own leadership. "The AI decided" is not an acceptable answer to any of them.

A trail lets you answer properly. Here is the input the agent received. Here is the rule the branch applied. Here is the human who approved before anything was sent. That is a defensible account of a decision, and it is the same evidence whether the question comes from a curious customer or a formal audit.

Trust inside the team works the same way. People extend an automation more responsibility once they can see exactly what it did last week. The trail is what earns that.

## Retention and reviewing runs

A trail you cannot find or cannot keep is not much of a trail. A few practical notes.

**Retention.** Keep runs long enough to cover the questions you will actually get. Complaints and audits arrive weeks or months after the run, so a window that only holds the last few days is too short. Match retention to how long a decision stays live and to whatever rules govern your data.

**Reviewing.** Do not wait for a complaint to look. Review a sample of normal runs on a schedule. You are checking that branches route as intended, that costs sit where you expect, and that approvals are happening where they should. This is how you catch drift while it is small.

**Fine grain.** Keep the record per step, not just per run. A single final status tells you it failed. A per-step record tells you where and why. The extra detail is exactly what you need on the day something goes wrong.

## The bottom line

A production AI agent is not defined by how well it performs on a good day. It is defined by whether you can explain what it did on a bad one. Log the inputs, every tool call and result, the outputs, the cost, the branch taken, and who approved. Keep those long enough to matter, and review them before you are forced to.

Do that and your agents stop being a black box you defend with a demo. They become systems you can debug, account for, and trust, which is the only kind worth running.
`;

export default content;
