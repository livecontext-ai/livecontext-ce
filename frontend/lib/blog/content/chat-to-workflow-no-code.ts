// Markdown body for "From a chat message to a working automation". Plain string module.
const content = `You do not need to write code to build an AI automation. You need to say, in plain language, what you want to happen. The tool turns that sentence into a workflow you can see, run, and change.

That is the whole promise of no-code AI automation: describe the job, get a working system, keep control of it.

## Start with the outcome, not the steps

The habit people bring from older automation tools is thinking in steps first. Which trigger, which node, which field maps to which. That is backwards here.

Start with the outcome. Say what "done" looks like.

"When a support email comes in, read it, decide if it is a bug, a billing question, or general, draft a reply in the right tone, and put the draft in a review queue for a human."

That one sentence is enough to begin. You described the goal and the shape of the work. The tool fills in the plumbing.

## You get a graph, not a black box

When you describe the job, the tool builds a readable graph: a trigger, a few steps, the branches between them. You can look at it and understand it in one pass. This matters more than it sounds.

A lot of AI tools hide the work. You type a request, something happens, and you cross your fingers. When it goes wrong, you have nothing to inspect.

Here you see every node. You see where the email enters, where the classification happens, which branch a billing question takes, where the draft gets written, and where it waits for a human. Nothing is implied. If a step exists, it is on the canvas.

## Refine by chatting, or by hand

The first version is rarely the final one. Refining is where no-code earns its keep.

You have two ways to change the workflow, and you can mix them freely:

- **Keep chatting.** "Also tag anything mentioning a refund as urgent." The tool adds the branch and wires it in.
- **Edit the nodes directly.** Open the classify step and adjust the categories. Open the draft step and tighten the tone. Rename a branch. Move a step earlier.

Chatting is fast for structural changes. Direct editing is precise for small tweaks. Neither locks you out of the other. The graph is the source of truth, and both paths write to the same graph.

## Each step is scoped, which keeps it cheap

A workflow is not one big agent doing everything. It is a set of small steps, and each step only sees what it needs.

The classify step sees the email text and returns a category. That is all it needs, so that is all it gets. The draft step sees the email and the category. The review step sees the draft.

Because each step gets a narrow slice of context instead of the whole history, the tokens stay small and the cost stays low. The same job runs about ten times cheaper than handing everything to one do-everything agent and hoping it stays on track. You did not design that saving by hand. It falls out of building the job as a scoped graph.

## When you still reach for a code node

No-code covers most of the work. It does not have to cover all of it, and pretending otherwise is where these tools earn a bad name.

Reach for a code node when the logic is genuinely mechanical and exact:

- Reshaping a payload into the exact structure another step expects.
- A precise calculation, a date math rule, a threshold with no fuzziness.
- Parsing a format the built-in steps do not recognize.

These are the cases where a few lines of code are clearer and more reliable than a paragraph of instructions to a model. The point is not to avoid code. The point is to not write code for the parts a description handles better. Use language for judgment. Use a code node for exactness.

## A concrete example: support inbox triage

Walk through the support example end to end.

**Trigger.** A new email lands in the support inbox.

**Classify.** A scoped agent reads the email and returns one label: bug, billing, or general. It sees the email and nothing else.

**Branch.** The graph splits three ways on that label. This is a real branch you can see, not a hidden decision. A bug goes one way, billing another, general a third.

**Draft.** On each branch, a step writes a reply in the tone that fits. The billing branch can pull the account status first. The bug branch can attach a link to the status page.

**Review.** Every draft lands in a queue. A human reads it, edits if needed, and approves. Nothing reaches a customer without that approval.

**Audit.** Every run leaves a trail: what came in, which label it got, which branch it took, what was drafted, who approved.

You built that by describing it. You can read it because it is a graph. You can change it by chatting or by editing. And when someone asks why a particular email got the reply it did, you can point at the exact path it took.

That is what no-code AI automation should mean. Not a magic box you trust blindly, but a system you describe in words and then hold in your hands.
`;

export default content;
