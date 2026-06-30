import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Core concepts',
  description:
    'The mental model behind LiveContext: workflows and runs, nodes and edges, triggers, control flow, agents, interfaces, tables, credits, and how execution and signals work.',
  path: '/docs/concepts',
});

export default function ConceptsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Core concepts"
        lead="A handful of terms show up everywhere in LiveContext. Learn these once and the rest of the docs read easily."
      />

      <DocsProse>
        <p>
          At its core, a LiveContext automation is a <strong>workflow</strong>: a graph of{' '}
          <strong>nodes</strong> connected by <strong>edges</strong>. A <strong>trigger</strong>{' '}
          starts a <strong>run</strong>, nodes execute in dependency order, and data flows forward
          through template expressions. Everything else - agents, interfaces, tables - is a kind of
          node.
        </p>

        <h2>Workflows &amp; runs</h2>
        <p>
          <strong>Workflow</strong> - the automation itself: a directed graph of nodes you build in
          the canvas. A single workflow can have several triggers; each trigger has its own
          independent graph.
        </p>
        <p>
          <strong>Run</strong> - one execution of a workflow, created when a trigger fires. A run has
          a status, records every step&apos;s output, and stays browsable afterwards.
        </p>
        <p>
          <strong>Epoch</strong> - one trigger fire. Each time a trigger fires it opens a new epoch;
          results accumulate so you can browse every past fire. <strong>Spawn</strong> - a
          re-execution <em>within</em> the same epoch (a loop iteration or a retry). Epochs come from
          new triggers; spawns happen inside a run.
        </p>

        <h2>Nodes, edges &amp; ports</h2>
        <p>
          <strong>Node</strong> - one step. Every node is identified by a normalized key of the form{' '}
          <code>prefix:label</code> - for example <code>mcp:send_email</code> or{' '}
          <code>core:check_status</code>. The prefix tells you the kind of node:
        </p>
        <ul>
          <li><code>trigger:</code> - entry points (webhook, schedule, chat, form, manual, table)</li>
          <li><code>mcp:</code> - integration / tool operations (an API call, an HTTP request)</li>
          <li><code>agent:</code> - AI nodes (Agent, Guardrail, Classify)</li>
          <li><code>core:</code> - control flow and utilities (Decision, Loop, Transform, Wait...)</li>
          <li><code>table:</code> - built-in spreadsheet operations (find, create, update, delete rows)</li>
          <li><code>interface:</code> - web pages, and <code>note:</code> - canvas notes</li>
        </ul>
        <p>
          <strong>Edge</strong> - a directed connection (<code>from → to</code>) that sets execution
          order. <strong>Port</strong> - branching nodes expose named outputs you connect from
          separately, like <code>:if</code> / <code>:else</code> on a Decision or <code>:body</code> /{' '}
          <code>:iterate</code> / <code>:exit</code> on a Loop. Each port connects to exactly one
          successor.
        </p>
        <Callout variant="info">
          Connecting two nodes sets <strong>order only</strong> - it does not pass data. To use an
          earlier step&apos;s value, reference it with{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. A node can read only from nodes that ran{' '}
          <strong>before it on its path</strong> (ancestor-only scope); sibling branches can&apos;t see
          each other&apos;s output until after they merge.
        </Callout>

        <h2>Triggers</h2>
        <p>
          A <strong>trigger</strong> is what starts a run: a <strong>webhook</strong> (an HTTP call),
          a <strong>schedule</strong> (a recurring time), a <strong>chat</strong> message, a{' '}
          <strong>form</strong> submission, a <strong>manual</strong> click, or a{' '}
          <strong>table</strong> row change. A workflow needs at least one trigger, and each trigger
          runs its own graph. See <a href="/triggers">Triggers</a> for the full list.
        </p>

        <h2>Control flow</h2>
        <p>
          Control-flow nodes decide which successors run. The difference between them is the single
          thing people get wrong most often, so it&apos;s worth memorizing:
        </p>
        <DocsTable
          head={['Node', 'What it does', 'Ports']}
          rows={[
            [<strong key="d">Decision</strong>, 'Takes exactly ONE branch (first matching condition wins).', <code key="p">if · elseif_N · else</code>],
            [<strong key="s">Switch</strong>, 'Matches a value against cases and runs the FIRST matching case (the rest are skipped) - exclusive, like Decision, but chosen by value.', <code key="p">case_N · default</code>],
            [<strong key="f">Fork</strong>, 'Runs ALL branches in parallel - no condition.', <code key="p">branch_N</code>],
            [<strong key="m">Merge</strong>, 'Waits for ALL predecessors before continuing (AND only - there is no OR).', '-'],
            [<strong key="sp">Split</strong>, 'Fans a list into N parallel contexts, one per item.', '-'],
            [<strong key="l">Loop</strong>, 'Repeats its body until the exit condition is met.', <code key="p">body · iterate · exit</code>],
          ]}
        />
        <p>
          Inside a <strong>Split</strong>, each parallel branch gets its own{' '}
          <code>{'{{item}}'}</code> and <code>{'{{current_index}}'}</code>. These are runtime-only and
          disappear once the split finishes - if you need an item&apos;s value later, save it to a
          table or a variable before the split ends.
        </p>

        <h2>Agents, interfaces &amp; tables</h2>
        <p>
          <strong>Agent</strong> - an AI node that calls a model to reason, decide, or generate, with
          a scoped set of tools and a credit budget it can&apos;t exceed. Variants include{' '}
          <strong>Guardrail</strong> (validate/filter) and <strong>Classify</strong> (route by
          category). See <a href="/agents">Agents</a>.
        </p>
        <p>
          <strong>Interface</strong> - a web page (rendered in an iframe) that displays workflow data
          and collects input. It can <em>block</em> the run (a wizard that waits for the user to
          continue) or just display and let the run proceed. See{' '}
          <a href="/interfaces">Interfaces &amp; apps</a>.
        </p>
        <p>
          <strong>Table</strong> - a built-in spreadsheet your workflow reads, searches, and writes.
          Rows have a flexible schema; a table can also be a trigger (run when a row changes) or
          shared memory between steps. See <a href="/tables">Tables &amp; data</a>.
        </p>

        <h2>Execution &amp; signals</h2>
        <p>
          A workflow runs in one of two modes: <strong>Automatic</strong> (every ready node runs as
          soon as its inputs are available - the default) or <strong>Step-by-step</strong> (you
          advance one node at a time, useful for debugging). A node becomes <em>ready</em> once all
          of its predecessors have completed or been skipped.
        </p>
        <p>A run moves through these statuses:</p>
        <DocsTable
          head={['Status', 'Kind', 'Meaning']}
          rows={[
            [<code key="s">PENDING</code>, 'Active', 'Created, not yet started.'],
            [<code key="s">WAITING_TRIGGER</code>, 'Active', 'Waiting for its trigger input.'],
            [<code key="s">RUNNING</code>, 'Active', 'Executing nodes.'],
            [<code key="s">AWAITING_SIGNAL</code>, 'Active', 'Paused on a signal (timer, approval, webhook, interface).'],
            [<code key="s">PAUSED</code>, 'Active', 'Suspended; can be resumed.'],
            [<code key="s">COMPLETED</code>, 'Terminal', 'Finished successfully.'],
            [<code key="s">PARTIAL_SUCCESS</code>, 'Terminal', 'Finished with some branches/items failed.'],
            [<code key="s">FAILED</code>, 'Terminal', 'Ended on an error.'],
            [<code key="s">CANCELLED / TIMEOUT / SKIPPED</code>, 'Terminal', 'Cancelled, timed out, or skipped.'],
          ]}
        />
        <p>
          A <strong>signal</strong> is a point where a run pauses until something happens. Some
          signals block the run from finishing; an interface only blocks when it asks the user to
          continue:
        </p>
        <DocsTable
          head={['Signal', 'Blocks the run?', 'Resolves when...']}
          rows={[
            ['Wait timer', 'Yes', 'the delay elapses.'],
            ['User approval', 'Yes', 'someone approves or rejects.'],
            ['Webhook wait', 'Yes', 'the awaited webhook arrives.'],
            ['Interface', 'Only with a "continue" action', 'the user advances the page.'],
            ['Agent execution', 'Yes', 'the queued agent finishes.'],
            ['Browser takeover', 'Yes', 'the user hands control back.'],
          ]}
        />

        <h2>Credits &amp; budgets</h2>
        <p>
          Work that costs money - model calls, certain integration calls - consumes{' '}
          <strong>credits</strong>. Every agent can be given a <strong>budget</strong> it cannot
          exceed. When an agent spawns sub-agents, their budgets are reserved from the parent&apos;s,
          so the total spend of a whole agent tree is capped by the top budget. See{' '}
          <a href="/agents">Agents</a> for the full model.
        </p>

        <CodeBlock language="text">{`{{trigger:webhook.output.body}}        → the payload a webhook received
{{core:check_amount.output.result}}    → a core node's result
{{table:find_users.output.items}}      → rows a table query returned
{{agent:summarize.output.response}}    → an agent's generated text`}</CodeBlock>
      </DocsProse>
    </>
  );
}
