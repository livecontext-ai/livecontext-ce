import { Workflow, Bot, Boxes } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Chat',
  description:
    'Chat is the conversational builder in LiveContext. Describe what you want, the assistant builds the workflow step by step, runs it, inspects the results, and edits it as you give feedback - all in one thread.',
  path: '/docs/chat',
});

export default function ChatPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Chat"
        lead="Chat is where you build and run automations by talking. You describe the outcome you want; the assistant assembles the workflow, wires it up, runs it, and edits it as you give feedback - without leaving the conversation."
      />

      <DocsProse>
        <h2>What you can ask</h2>
        <p>In a single thread, you can ask the assistant to:</p>
        <ul>
          <li><strong>Build</strong> a workflow from a description of the job.</li>
          <li><strong>Change</strong> a step - swap an integration, adjust a condition, shorten a prompt.</li>
          <li><strong>Run</strong> the workflow and show you the results.</li>
          <li><strong>Inspect</strong> a past run to explain what happened or why it failed.</li>
          <li><strong>Connect</strong> an integration, add a table, or wrap the workflow in an app.</li>
        </ul>

        <h2>How a build conversation flows</h2>
        <p>
          As it builds, the assistant always starts with an <strong>entry point</strong> - a trigger -
          then adds the steps that do the work and wires them together. It keeps you informed at each
          stage (adding the trigger, adding steps, connecting them, fixing anything invalid, ready to
          save) and tells you what it just did and what it suggests next. You stay in control: nothing
          runs or saves unless you ask.
        </p>
        <Callout variant="info">
          A workflow always begins with a trigger. If you describe steps before there&apos;s a way to
          start the workflow, the assistant adds the trigger first - every automation needs an entry
          point.
        </Callout>

        <h2>Running and debugging from chat</h2>
        <p>
          Ask the assistant to run the workflow and the results stream back into the thread, step by
          step, with each node&apos;s output. If something fails, ask{' '}
          <em>&ldquo;why did that fail?&rdquo;</em> - it reopens the run and reads it node by node, so
          it can tell you which step broke and why (a bad credential, a missing field, an empty
          result) and offer a fix.
        </p>
        <CodeBlock language="text">{`You:        Run it with a test contact.
Assistant:  ✓ Contact form received  {email: "test@acme.com"}
            ✓ Get customer            {name: "Dana", plan: "premium"}
            ✓ Decision: premium?      → yes
            ✗ Send welcome email      SMTP auth failed: invalid credentials
            The run stopped at "Send welcome email" - the mail credential
            looks invalid. Want me to point it at a different one?`}</CodeBlock>

        <h2>Plain steps vs. agents</h2>
        <p>
          Two kinds of steps do the work. <strong>Deterministic steps</strong> - an integration call,
          an HTTP request, a data transform, a bit of code - do exactly one predictable thing.{' '}
          <strong>Agents</strong> reason: they decide, summarize, classify, or handle open-ended
          input. Reach for an agent when the task genuinely needs judgement; use a plain step when the
          operation is fixed. It keeps your workflow faster, cheaper, and easier to audit.
        </p>
        <Callout variant="tip">
          Iterate out loud. &ldquo;Make the summary shorter,&rdquo; &ldquo;only do this for VIP
          customers,&rdquo; &ldquo;add a step that logs it&rdquo; - the assistant edits the existing
          workflow rather than starting over, and you can run again immediately to see the effect.
        </Callout>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            What the assistant is building under the hood - structure, branching, parallelism.
          </Card>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            The catalog of building blocks the assistant can place.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Configure the AI steps: model, tools, budget, and guardrails.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
