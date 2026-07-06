import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Core concepts',
  description:
    'The mental model behind LiveContext: workflows and runs, nodes and edges, triggers, control flow, agents, interfaces, tables, execution modes, run statuses, signals, and credits.',
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
          At its core, a LiveContext automation is a <strong>workflow</strong>: a directed graph of{' '}
          <strong>nodes</strong> connected by <strong>edges</strong>. A <strong>trigger</strong>{' '}
          starts a <strong>run</strong>, nodes execute in dependency order, and data flows forward
          through template expressions. Everything else, agents, interfaces, tables, is a kind of
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

        <h2>Nodes, edges &amp; ports</h2>
        <p>
          <strong>Node</strong> - one step. Every node is identified by a normalized key of the form{' '}
          <code>prefix:label</code>, for example <code>mcp:send_email</code> or{' '}
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
          <strong>Edge</strong> - a directed connection (<code>from → to</code>) that sets execution{' '}
          <strong>order only</strong>. It does not pass data by itself, and only <code>core:</code>{' '}
          branching nodes expose named <strong>ports</strong> (format <code>core:label:port</code>);
          each port connects to exactly one successor. Decision uses <code>if</code> /{' '}
          <code>elseif_N</code> / <code>else</code>, Switch uses <code>case_N</code> /{' '}
          <code>default</code>, Loop uses <code>body</code> / <code>exit</code> (with{' '}
          <code>iterate</code> as the close-back input), and Fork uses <code>branch_N</code>.
        </p>
        <Callout variant="info">
          Connecting two nodes sets order only, it does not pass data. To use an earlier step&apos;s
          value, reference it with <code>{'{{prefix:label.output.field}}'}</code>. A node can read
          only from nodes that ran <strong>before it on its own path</strong> (ancestor-only scope);
          sibling branches can&apos;t see each other&apos;s output until after they merge.
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
            [<strong key="s">Switch</strong>, 'Matches a value against cases and runs the FIRST matching case (the rest are skipped), exclusive like Decision, but chosen by value.', <code key="p">case_N · default</code>],
            [<strong key="f">Fork</strong>, 'Runs ALL branches in parallel, no condition.', <code key="p">branch_N</code>],
            [<strong key="m">Merge</strong>, 'Waits for ALL predecessors before continuing (AND only, there is no OR).', '-'],
            [<strong key="sp">Split</strong>, 'Fans a list into N parallel contexts, one per item.', '-'],
            [<strong key="l">Loop</strong>, 'Repeats its body until the exit condition is met.', <code key="p">body · exit</code>],
          ]}
        />

        <h3>Split runtime variables</h3>
        <p>
          Inside a <strong>Split</strong>, each parallel branch is enriched with per-item context.
          These values are <strong>runtime-only</strong> and are never persisted to the database:
        </p>
        <DocsTable
          head={['Form', 'Value']}
          rows={[
            [<code key="1">{'{{item}}'}</code>, 'the current item'],
            [<code key="2">{'{{index}}'}</code>, '0-based index of the current item'],
            [<code key="3">{'{{items}}'}</code>, 'the whole list being split'],
            [<code key="4">{'{{core:<split_label>.output.current_item}}'}</code>, 'scoped form of the current item, also readable as .field on it'],
            [<code key="5">{'{{core:<split_label>.output.current_index}}'}</code>, 'scoped form of the 0-based index'],
          ]}
        />
        <Callout variant="warn">
          There is no bare <code>{'{{current_index}}'}</code>. The bare index variable is{' '}
          <code>{'{{index}}'}</code>; <code>current_index</code> only exists as the scoped form{' '}
          <code>{'{{core:<split_label>.output.current_index}}'}</code>. The fields that DO survive
          the split and stay available for inspection afterwards are <code>items</code>,{' '}
          <code>item_count</code>, <code>split_id</code>, <code>spawn_reason</code>,{' '}
          <code>terminated</code>, and <code>resolved_params</code>; <code>current_item</code> and{' '}
          <code>current_index</code> are not among them, so if you need an item&apos;s value later,
          save it to a table or a workflow variable before the split ends.
        </Callout>

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

        <h2>Execution modes</h2>
        <p>
          A workflow runs in one of two modes. <strong>Automatic</strong> (the default) executes
          every ready node as soon as its inputs are available. <strong>Step-by-step</strong> pauses
          the run after each node, including control nodes, so a user can manually advance one step
          at a time, useful for debugging. Either way, a node becomes <em>ready</em> once all of its
          predecessors have completed or been skipped.
        </p>

        <h2>Runs, epochs &amp; spawns</h2>
        <p>
          <strong>Epoch</strong> - one trigger fire. Each time a trigger fires it opens a new epoch;
          results accumulate so you can browse every past fire. <strong>Spawn</strong> - a
          re-execution <em>within</em> the same epoch (a loop iteration or a retry). Epochs come from
          new triggers; spawns happen inside a run.
        </p>
        <p>
          Under the hood, run state is stored per-trigger in an immutable snapshot: a{' '}
          <code>DagState</code> tracks the current epoch, the current spawn, how many times the
          trigger has fired, and a map of every epoch&apos;s state (which nodes completed, failed,
          were skipped, are running, are ready, or are awaiting a signal, plus decision branches,
          loops, and splits).
        </p>

        <h2>Run statuses</h2>
        <p>
          A run status is exactly one of 11 values: 5 active (non-terminal) and 6 terminal. Once a
          run reaches a terminal status it does not change again for that run.
        </p>
        <DocsTable
          head={['Status', 'Kind', 'Meaning']}
          rows={[
            [<code key="s">PENDING</code>, 'Active', 'Created, not yet started (also the fallback for unknown/blank input).'],
            [<code key="s">RUNNING</code>, 'Active', 'Executing nodes.'],
            [<code key="s">PAUSED</code>, 'Active', 'Suspended; the only status that can be resumed.'],
            [<code key="s">WAITING_TRIGGER</code>, 'Active', 'Waiting for its trigger input.'],
            [<code key="s">AWAITING_SIGNAL</code>, 'Active', 'Paused on a signal (timer, approval, webhook, interface, agent execution, or browser takeover).'],
            [<code key="s">COMPLETED</code>, 'Terminal', 'Finished successfully.'],
            [<code key="s">FAILED</code>, 'Terminal', 'Ended on an error.'],
            [<code key="s">PARTIAL_SUCCESS</code>, 'Terminal', 'Finished with some branches or items failed.'],
            [<code key="s">SKIPPED</code>, 'Terminal', 'The run or branch was skipped.'],
            [<code key="s">CANCELLED</code>, 'Terminal', 'Cancelled by a user.'],
            [<code key="s">TIMEOUT</code>, 'Terminal', 'Timed out.'],
          ]}
        />
        <p>
          Terminal (6): <code>COMPLETED</code>, <code>SKIPPED</code>, <code>FAILED</code>,{' '}
          <code>PARTIAL_SUCCESS</code>, <code>CANCELLED</code>, <code>TIMEOUT</code>. Active /
          non-terminal (5): <code>PENDING</code>, <code>RUNNING</code>, <code>PAUSED</code>,{' '}
          <code>WAITING_TRIGGER</code>, <code>AWAITING_SIGNAL</code>.
        </p>

        <h2>Signals &amp; pausing a run</h2>
        <p>
          A <strong>signal</strong> is a point where a run pauses (status{' '}
          <code>AWAITING_SIGNAL</code>) until something happens. There are exactly six signal types:
        </p>
        <DocsTable
          head={['Signal', 'Blocks the run?', 'Resolves when...']}
          rows={[
            ['Wait timer', 'Yes', 'the delay elapses. Durations over 3 seconds register a signal and yield; durations of 3 seconds or less run inline with no signal.'],
            ['User approval', 'Yes', 'someone approves or rejects.'],
            ['Webhook wait', 'Yes', 'the awaited external webhook arrives.'],
            ['Interface', 'Only with a "continue" action', 'the user advances the page (blocking only when the interface has a continue action; otherwise it auto-advances).'],
            ['Agent execution', 'Yes', 'the queued agent finishes (only in async/queued execution).'],
            ['Browser takeover', 'Yes, always', 'the user hands control back or resumes.'],
          ]}
        />
        <Callout variant="info">
          The wait-timer threshold is 3 seconds exactly. A <code>Wait</code> node with a duration of{' '}
          3 seconds or less sleeps inline and never shows up as an <code>AWAITING_SIGNAL</code> run;
          anything longer registers a real signal so the run can be paused and resumed without tying
          up a worker.
        </Callout>

        <h2>Credits &amp; budgets</h2>
        <p>
          Work that costs money, model calls, certain integration calls, consumes{' '}
          <strong>credits</strong>. Every agent can be given a <strong>budget</strong> it cannot
          exceed. When an agent spawns sub-agents, their budgets are reserved from the parent&apos;s,
          so the total spend of a whole agent tree is capped by the top budget. See{' '}
          <a href="/agents">Agents</a> for the full model.
        </p>
        <p>
          When a budget is hit, the agent stops for reason <code>BUDGET_EXHAUSTED</code>, and which
          level was exceeded is reported as a <code>budgetScope</code>: <code>tenant</code>,{' '}
          <code>agent</code>, <code>parent_reservation</code> (an ancestor refused the cascade
          reservation needed to spawn a sub-agent), or <code>browser</code> (a per-user browser-agent
          quota: concurrent sessions or steps per day).
        </p>

        <h2>Cloud vs Community Edition</h2>
        <p>
          Two edition families surface differently in the product: a self-hosted Community Edition
          (CE) and a managed cloud. The running <strong>Version</strong> card, the &quot;About&quot;
          entry, and the &quot;Update available&quot; prompt are <strong>CE-only</strong>; a cloud
          build never surfaces a version at all. CE installs poll the cloud&apos;s public update feed
          and show an amber &quot;Update available&quot; (red for a security fix) when they are
          behind the latest release.
        </p>

        <h2>Template expressions</h2>
        <p>
          Everywhere a node reads another step&apos;s output, it uses the same template form:{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. See{' '}
          <a href="/workflows">Workflows</a> for the full expressions reference.
        </p>
        <CodeBlock language="text">{`{{trigger:webhook.output.body}}        → the payload a webhook received
{{core:check_amount.output.result}}    → a core node's result
{{table:find_users.output.items}}      → rows a table query returned
{{agent:summarize.output.response}}    → an agent's generated text`}</CodeBlock>
      </DocsProse>
    </>
  );
}
