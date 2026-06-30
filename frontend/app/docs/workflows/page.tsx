import { Boxes, Webhook, Bot } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Workflows',
  description:
    'How LiveContext workflows are structured and executed: nodes, edges and ports, the prefix system, label normalization, branching and parallelism, loops, execution modes, and per-step reliability policies.',
  path: '/docs/workflows',
});

export default function WorkflowsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Workflows"
        lead="A workflow is a graph of nodes connected by edges. You can build it by chatting or by editing the canvas directly. This page is the model underneath: how nodes are named and wired, how data flows, and how branches and parallelism actually behave."
      />

      <DocsProse>
        <h2>Nodes, edges &amp; ports</h2>
        <p>
          Every node has a normalized key of the form <code>prefix:label</code>. The prefix marks the
          category; there are seven:
        </p>
        <DocsTable
          head={['Prefix', 'Category', 'Examples']}
          rows={[
            [<code key="p">trigger:</code>, 'Entry points', 'webhook, schedule, chat, form, manual, table'],
            [<code key="p">mcp:</code>, 'Integration / tool steps', 'an API call from the catalog'],
            [<code key="p">table:</code>, 'Built-in spreadsheet operations', 'find, create, update, delete rows'],
            [<code key="p">agent:</code>, 'AI nodes', 'Agent, Classify, Guardrail'],
            [<code key="p">core:</code>, 'Control flow & utilities', 'Decision, Loop, Split, Transform, Wait, HTTP, Response'],
            [<code key="p">interface:</code>, 'Web pages', 'a page rendered in an iframe'],
            [<code key="p">note:</code>, 'Canvas annotations', 'documentation only'],
          ]}
        />
        <p>
          <strong>Edges</strong> connect nodes (<code>from → to</code>) and set execution order.
          Branching <code>core:</code> nodes expose named <strong>ports</strong> you connect from
          separately - each port goes to exactly one successor:
        </p>
        <DocsTable
          head={['Node', 'Ports']}
          rows={[
            ['Decision', <code key="p">if · elseif_0 · elseif_1 · ... · else</code>],
            ['Switch', <code key="p">case_0 · case_1 · ... · default</code>],
            ['Loop', <code key="p">body · iterate · exit</code>],
            ['Fork', <code key="p">branch_0 · branch_1 · ...</code>],
            ['User Approval', <code key="p">approved · rejected · timeout</code>],
            ['Classify', <code key="p">category_0 · category_1 · ...</code>],
            ['Guardrail', <code key="p">pass · fail</code>],
          ]}
        />

        <h3>Label normalization</h3>
        <p>
          Node keys are derived from your label by a fixed rule: accents are transliterated, the text
          is lowercased, every non-alphanumeric character becomes <code>_</code>, repeated underscores
          collapse, and leading/trailing underscores are trimmed. So <em>&ldquo;My-API Call&rdquo;</em>{' '}
          becomes <code>mcp:my_api_call</code>. This is why a reference must use the{' '}
          <strong>normalized</strong> form - <code>{'{{mcp:my_api_call.output.data}}'}</code>, never{' '}
          <code>{'{{mcp:My-API Call.output.data}}'}</code>.
        </p>
        <Callout variant="warn">
          Normalization is case- and punctuation-insensitive, so two labels that differ only in case
          or spacing (&ldquo;My Loop&rdquo; and &ldquo;my-loop&rdquo;) collapse to the same key and
          collide. Give nodes distinct names.
        </Callout>

        <h2>How data flows</h2>
        <p>
          Connecting nodes sets order, not data. To pass a value, reference it with{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. Expressions are evaluated server-side at run
          time and can reach into nested fields. A node can read only from its{' '}
          <strong>ancestors</strong> - never from a sibling branch or a step that hasn&apos;t run.
        </p>
        <CodeBlock language="text">{`{{trigger:webhook.output.userId}}        → a field from the trigger payload
{{mcp:fetch_user.output.email}}          → the "Fetch user" step's email field
{{mcp:fetch_user.output.data.user.id}}   → nested field access
{{core:summary.output.transformed.total}} → a Transform node's computed field`}</CodeBlock>
        <p>
          Expressions also support built-in functions for everyday shaping - dates, numbers, strings,
          collections - for example <code>now()</code>, <code>formatDate()</code>, <code>size()</code>,{' '}
          <code>isEmpty()</code>, <code>join()</code>, and <code>formatCurrency()</code>.
        </p>

        <h2>Branching &amp; parallelism</h2>
        <p>This is the part worth getting exactly right:</p>
        <ul>
          <li>
            <strong>Decision</strong> evaluates boolean conditions top-to-bottom and takes the{' '}
            <strong>first true</strong> branch - exactly one. Put the most specific condition first.
          </li>
          <li>
            <strong>Switch</strong> matches a value against case values and runs the{' '}
            <strong>first matching case</strong> - also exactly one (the rest are skipped). Decision
            branches on booleans; Switch branches on a value.
          </li>
          <li>
            <strong>Fork</strong> runs <strong>all</strong> branches in parallel with no condition -
            different tasks on the same data.
          </li>
          <li>
            <strong>Split</strong> fans a list into <strong>N parallel item contexts</strong> on a{' '}
            single path - the same task on each item, with <code>{'{{item}}'}</code> and{' '}
            <code>{'{{current_index}}'}</code> available inside the body.
          </li>
          <li>
            <strong>Merge</strong> waits for <strong>all</strong> predecessors (AND only - there is no
            OR) and synchronizes; <strong>Aggregate</strong> collects a Split&apos;s items into arrays.
          </li>
        </ul>
        <Callout variant="info">
          A skipped node counts as <em>resolved</em>, so a Merge proceeds once every predecessor is
          either completed or skipped. Use Fork → Merge for parallel different tasks, and Split →
          Aggregate for processing a list.
        </Callout>
        <p>
          The runtime <code>{'{{item}}'}</code> / <code>{'{{current_index}}'}</code> values live only
          inside the split body - they are <strong>not</strong> persisted. Downstream of the split you
          can still read the persisted <code>{'{{core:label.output.items}}'}</code> array; if you need
          a single item&apos;s value later, write it to a table before the split ends.
        </p>

        <h2>Loops</h2>
        <p>
          A <strong>Loop</strong> repeats its <code>body</code> while a condition holds, re-evaluating
          the condition each pass, then continues from <code>exit</code>. It carries a max-iterations
          safety limit, and its exit reason is readable as{' '}
          <code>{'{{core:label.output.reason}}'}</code> - one of <code>condition_false</code>,{' '}
          <code>max_iterations_reached</code>, or <code>error</code>. Route <code>exit</code> into a
          Decision when success and &ldquo;gave up&rdquo; need different handling. Inside the body,{' '}
          <code>{'{{core:label.output.iteration}}'}</code> is 1-based while{' '}
          <code>{'{{core:label.output.current_index}}'}</code> is 0-based.
        </p>

        <h2>Execution modes</h2>
        <p>
          In <strong>Automatic</strong> mode (the default) every node runs as soon as all its
          predecessors are resolved; independent ready nodes run concurrently. In{' '}
          <strong>Step-by-step</strong> mode you advance one node at a time - useful for debugging the
          graph. Some nodes <em>pause</em> the run on a signal (a wait timer, an approval, a webhook,
          or a blocking interface) and resume when the signal is resolved - see{' '}
          <a href="/concepts">Core concepts</a>.
        </p>

        <h2>Per-step reliability</h2>
        <p>
          Any executable node can carry an optional execution policy: <strong>retries</strong> with a
          backoff delay, a per-attempt <strong>timeout</strong>, <strong>continue-on-failure</strong>{' '}
          (run successors even if the final attempt fails), and <strong>execute-once</strong> (inside a
          split, run only for the first item). Only the final attempt is persisted and billed.
          Continue-on-failure isn&apos;t allowed on single-branch control nodes like Decision and
          Switch.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            The full catalog of node types, grouped, with their ports.
          </Card>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            Every way a run can start.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            The AI nodes: models, tools, budgets, guardrails.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
