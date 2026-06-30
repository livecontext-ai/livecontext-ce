import { Workflow, LayoutPanelLeft, ShieldCheck } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Agents',
  description:
    'Agents in LiveContext: scoped tool access, model and reasoning-effort selection, temperature and limits, credit budgets that cascade to sub-agents, the nine stop reasons, and built-in metrics.',
  path: '/docs/agents',
});

export default function AgentsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Agents"
        lead="An agent is an AI step that reasons over a prompt, optionally calls tools, and returns a result. What makes agents safe to put in production is that you scope exactly what they can touch and cap exactly what they can spend - with a full record of everything they did."
      />

      <DocsProse>
        <h2>Model &amp; reasoning</h2>
        <p>
          Each agent runs a model you choose, from providers including Anthropic, OpenAI, Google,
          Mistral, and DeepSeek; the default is a fast, low-cost model. A few settings shape its
          behaviour:
        </p>
        <ul>
          <li><strong>Temperature</strong> (0.0-2.0, default 0.7) - lower is more deterministic, higher more creative.</li>
          <li><strong>Max tokens</strong> per turn (default 4096) - automatically capped to the model&apos;s own ceiling.</li>
          <li><strong>Max tool iterations</strong> (default 10) - how many tool-call cycles before the agent must answer.</li>
        </ul>
        <p>
          <strong>Reasoning effort</strong> is an optional dial for models that support extended
          thinking. It maps to a thinking-token budget; on models that don&apos;t support it, the
          setting is simply ignored.
        </p>
        <DocsTable
          head={['Effort', 'Thinking budget (approx.)']}
          rows={[
            ['minimal', '1k tokens'],
            ['low', '4k tokens'],
            ['medium', '8k tokens'],
            ['high', '16k tokens'],
            ['xhigh', '32k tokens (most-capable models only)'],
          ]}
        />

        <h2>Tools &amp; scope</h2>
        <p>An agent&apos;s tool access is deliberate, and it&apos;s a deny-by-default safety boundary:</p>
        <ul>
          <li><strong>All tools</strong> - leave the tool list unset; the agent may call any connected tool.</li>
          <li><strong>No tools</strong> - an explicit empty selection; the agent is a pure reasoner.</li>
          <li><strong>Selective</strong> - list specific tools; the agent can call only those.</li>
        </ul>
        <Callout variant="info">
          Tools are invoked by the agent at run time - they are <strong>not</strong> edges in the
          workflow graph. Scoping is also how you keep an agent away from data it shouldn&apos;t see:
          if a table or tool isn&apos;t in its scope, the agent can&apos;t reach it, even within the
          same workflow.
        </Callout>

        <h2>Credit budgets</h2>
        <p>
          Work an agent does - model calls, certain tool calls - costs <strong>credits</strong>, scaling
          with the tokens used and the model&apos;s rates. Give an agent a <strong>budget</strong> and
          it cannot exceed it; a guard projects the next step&apos;s cost and stops the agent before it
          overspends.
        </p>
        <p>
          Budgets <strong>cascade</strong>. When an agent spawns a sub-agent, credits are reserved from
          every ancestor up the chain, so the total spend of an entire agent tree can never exceed the
          top budget. When a sub-agent finishes, its actual cost is settled up the chain and any unused
          reservation is refunded. You can see, for any agent, how much it spent on its own work versus
          on its descendants.
        </p>
        <Callout variant="tip">
          Set an explicit budget on every production agent. An agent with no budget can spend without
          limit - fine for a quick experiment, risky for anything that runs unattended.
        </Callout>

        <h2>Why an agent stopped</h2>
        <p>Every execution ends with one of nine stop reasons, in three groups:</p>
        <DocsTable
          head={['Stop reason', 'Outcome', 'Meaning']}
          rows={[
            [<code key="r">COMPLETED</code>, 'Success', 'Finished with nothing left to do.'],
            [<code key="r">MAX_ITERATIONS</code>, 'Partial (usable)', 'Hit the tool-iteration cap.'],
            [<code key="r">TIMEOUT</code>, 'Partial', 'Exceeded its time limit.'],
            [<code key="r">BUDGET_EXHAUSTED</code>, 'Partial', 'Ran out of credits (scope: tenant, agent, parent reservation, or browser).'],
            [<code key="r">LOOP_DETECTED</code>, 'Partial', 'Repeated the same tool calls - stopped to avoid a loop.'],
            [<code key="r">STOPPED_BY_USER</code>, 'Partial', 'You cancelled it.'],
            [<code key="r">CANCELLED</code>, 'Failure', 'Cancelled by the system.'],
            [<code key="r">NO_TOOLS</code>, 'Failure', 'No tools were available to begin.'],
            [<code key="r">ERROR</code>, 'Failure', 'An error during execution.'],
          ]}
        />
        <p>
          A <em>partial</em> stop still leaves usable output - the agent did real work before it
          stopped - whereas a <em>failure</em> does not. When you hit <code>BUDGET_EXHAUSTED</code>,
          the scope tells you which limit bit: the tenant pool, this agent&apos;s own budget, an
          ancestor refusing to reserve for a sub-agent, or the concurrent-browser quota.
        </p>

        <h2>Metrics &amp; audit</h2>
        <p>
          Every run is recorded: token usage, duration, success/failure, and which tools were called.
          Counts roll up per agent (total executions, successes, failures, tokens, average duration,
          last run) so you can watch behaviour over time and catch a regression early.
        </p>

        <h2>Related AI nodes</h2>
        <p>
          <strong>Guardrail</strong> and <strong>Classify</strong> are specialized agent nodes -
          Guardrail validates or sanitizes content (and branches <code>pass</code> / <code>fail</code>),
          Classify routes input into one category by meaning. See the{' '}
          <a href="/nodes">Node reference</a>.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">How agent nodes sit in the graph.</Card>
          <Card icon={ShieldCheck} title="Node reference" href="/nodes">Guardrail, Classify, and the rest.</Card>
          <Card icon={LayoutPanelLeft} title="Interfaces" href="/interfaces">Let agents drive or hand off to a UI.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
