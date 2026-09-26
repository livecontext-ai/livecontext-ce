import { BookOpen, Workflow, Zap } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Core concepts',
  description:
    'The mental model behind LiveContext: workflows, runs, nodes, edges, triggers, control flow, versions and production, agents, interfaces, tables, signals, and credits.',
  path: '/docs/concepts',
});

export default function ConceptsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Core concepts"
        lead="A handful of terms show up everywhere in LiveContext. Learn these once and the rest of the docs read easily. Each term is also defined in the Glossary."
      />

      <DocsProse>
        <p>
          At its core, a LiveContext automation is a <strong>workflow</strong>: a directed graph of{' '}
          <strong>nodes</strong> connected by <strong>edges</strong>. A <strong>trigger</strong> starts
          a <strong>run</strong>, nodes execute in dependency order, and data flows forward through
          template expressions. Everything else (agents, interfaces, tables) is a kind of node. Short
          definitions of every term live in the <a href="/glossary">Glossary</a>.
        </p>

        <h2>Workflows and runs</h2>
        <p>
          A <strong>workflow</strong> is the automation itself: a graph of nodes you build in the canvas
          or by describing it in chat. A workflow can have several triggers, and each trigger starts its
          own part of the graph.
        </p>
        <p>
          A <strong>run</strong> is one execution of a workflow. It has a status, records every
          step&apos;s output, and stays browsable afterwards. When the same trigger fires again, the new
          results are added to the run as a new <strong>epoch</strong>, so you can browse every past
          fire. A <strong>spawn</strong> is a re-execution inside the same epoch, for example a loop
          iteration or a retry. See <a href="/runs">Runs &amp; execution</a>.
        </p>

        <h2>Nodes, edges, and ports</h2>
        <p>
          A <strong>node</strong> is one step. Every node is identified by a normalized key of the form{' '}
          <code>prefix:label</code>, for example <code>mcp:send_email</code> or{' '}
          <code>core:check_status</code>. The label is the name you give the step, lowercased with spaces
          turned into underscores. The prefix tells you the kind of node:
        </p>
        <DocsTable
          caption="Node prefixes and the kind of node each marks"
          head={['Prefix', 'Kind of node']}
          rowHeaders
          rows={[
            [<code key="t">trigger:</code>, 'Entry points that start a run (see Triggers below).'],
            [<code key="m">mcp:</code>, 'Operations of a catalog integration, such as sending a Gmail message.'],
            [<code key="a">agent:</code>, 'AI nodes: Agent, Browser Agent, Guardrail, Classify, and Generate.'],
            [
              <code key="c">core:</code>,
              'Control flow and utilities: Decision, Switch, Loop, Fork, Merge, Split, Aggregate, Transform, Wait, Code, HTTP Request, Sub-workflow, and more.',
            ],
            [<code key="tb">table:</code>, 'Built-in spreadsheet operations: find, create, update, delete rows.'],
            [<code key="i">interface:</code>, 'Web pages shown to people.'],
            [<code key="n">note:</code>, 'Notes on the canvas. They are never executed.'],
          ]}
        />
        <p>
          An <strong>edge</strong> is a directed connection from one node to the next. It sets execution{' '}
          <strong>order only</strong>. Branching <code>core:</code> nodes expose named{' '}
          <strong>ports</strong> (written <code>core:label:port</code>), and each port leads to its own
          successor. Decision uses <code>if</code>, <code>elseif_N</code>, and <code>else</code>. Switch
          uses <code>case_N</code> and <code>default</code>. Loop uses <code>body</code> and{' '}
          <code>exit</code>, with <code>iterate</code> as the input the body loops back into. Fork uses{' '}
          <code>branch_N</code>.
        </p>
        <Callout variant="info" title="Edges do not pass data">
          To use an earlier step&apos;s value, reference it with{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. A node can only read nodes that ran{' '}
          <strong>before it on its own path</strong>. Parallel branches cannot see each other&apos;s
          output until after they merge.
        </Callout>

        <h2>Triggers</h2>
        <p>A <strong>trigger</strong> is what starts a run. There are eight kinds:</p>
        <DocsTable
          head={['Trigger', 'Starts a run when']}
          rowHeaders
          caption="The eight trigger types"
          rows={[
            ['Webhook', 'an HTTP request reaches its URL.'],
            ['Manual', 'you start it yourself.'],
            ["Chat", "someone sends a message to the workflow's chat."],
            ['Tables', 'a row is created, updated, or deleted in a table.'],
            ['Scheduler', 'a recurring time comes around.'],
            ["Form", "someone submits the workflow's form."],
            ['Workflows', 'another workflow finishes a cycle without a failed step.'],
            ['Error', 'a cycle of another workflow has a failed step. Use it to alert someone or clean up.'],
          ]}
        />
        <p>
          A workflow needs at least one trigger. See <a href="/triggers">Triggers</a> for the options of
          each kind.
        </p>

        <h2>Control flow</h2>
        <p>
          Control-flow nodes decide which successors run. The difference between them is the thing
          people get wrong most often, so it is worth memorizing:
        </p>
        <DocsTable
          caption="Control-flow nodes and their ports"
          head={['Node', 'What it does', 'Ports']}
          rowHeaders
          rows={[
            [<strong key="d">Decision</strong>, 'Takes exactly ONE branch: the first condition that matches wins.', <code key="p">if · elseif_N · else</code>],
            [<strong key="s">Switch</strong>, 'Compares a value against cases and runs the FIRST matching case. Exclusive like Decision, but chosen by value.', <code key="p">case_N · default</code>],
            [<strong key="f">Fork</strong>, 'Runs ALL branches in parallel, with no condition.', <code key="p">branch_N</code>],
            [<strong key="m">Merge</strong>, 'Waits for ALL incoming branches before continuing. There is no "first one wins" mode.', 'none'],
            [<strong key="sp">Split</strong>, 'Fans a list out into one parallel context per item on the same branch.', 'none'],
            [<strong key="ag">Aggregate</strong>, 'The counterpart of Split: collects the per-item results back into one output with lists.', 'none'],
            [
              <strong key="l">Loop</strong>,
              'Runs its body again and again while its condition is true, up to a maximum number of iterations (10 by default). It needs a condition, a maximum, or both.',
              <code key="p">body · exit</code>,
            ],
          ]}
        />
        <p>
          Any node with several outgoing edges also runs them all in parallel, and any node with several
          incoming edges waits for all of them, exactly like Fork and Merge.
        </p>

        <h3>Split item variables</h3>
        <p>
          Inside a <strong>Split</strong>, each parallel branch can read the item it is working on. These
          values exist only while the branch runs:
        </p>
        <DocsTable
          caption="Variables available inside a Split body"
          head={['Expression', 'Value']}
          rows={[
            [<code key="1">{'{{item}}'}</code>, 'the current item'],
            [<code key="2">{'{{index}}'}</code>, '0-based index of the current item'],
            [<code key="3">{'{{items}}'}</code>, 'the whole list being split'],
            [<code key="4">{'{{core:<split_label>.output.current_item}}'}</code>, 'the current item, addressed through the Split node (add .field to read one field)'],
            [<code key="5">{'{{core:<split_label>.output.current_index}}'}</code>, 'the 0-based index, addressed through the Split node'],
          ]}
        />
        <Callout variant="warn">
          There is no bare <code>{'{{current_index}}'}</code>: use <code>{'{{index}}'}</code>. After the
          split, the item variables are gone. To keep the per-item results, collect them with an{' '}
          <strong>Aggregate</strong> node, or save them to a table.
        </Callout>

        <h2>Versions and production</h2>
        <p>
          Every time you save a workflow, LiveContext keeps a new <strong>version</strong>. You can browse,
          rename, and restore versions from <strong>Version History</strong>, next to the{' '}
          <strong>Save</strong> button.
        </p>
        <p>
          Choosing <strong>Set as production</strong> on a version <strong>pins</strong> it and puts the
          workflow live: the version is tagged <strong>production</strong>, and the triggers that fire on
          their own (schedules, webhooks, forms, chat endpoints, table changes, and other workflows) start
          running on that version. You can keep editing and saving without affecting what runs, then move
          production to a newer version when it is ready.
        </p>
        <Callout variant="warn" title="Without a production version">
          A workflow with no production version does not fire on its own: its schedules and webhooks stay
          silent until a version is set as production. You can still run it yourself, from the builder or
          by asking in chat. <strong>Remove from production</strong> takes a live workflow off the air the
          same way.
        </Callout>

        <h2>Composing workflows</h2>
        <p>Workflows can call and follow each other:</p>
        <ul>
          <li>
            A <strong>Sub-workflow</strong> node (<code>core:</code>) calls another workflow and waits for
            its result. The called workflow must already have an active run: set a production version
            on it, or run it once, before a parent calls it.
          </li>
          <li>
            A <strong>Workflows</strong> trigger fires when another workflow finishes a cycle without a
            failed step. An <strong>Error</strong> trigger fires when a cycle of another workflow has a
            failed step.
          </li>
        </ul>
        <p>
          See <a href="/nodes">Node reference</a> for the Sub-workflow options.
        </p>

        <h2>Agents, interfaces, and tables</h2>
        <p>
          An <strong>agent</strong> is an AI worker that calls a model to reason, decide, or write, using
          a scoped set of tools within a credit budget it cannot exceed. Inside a workflow it is an Agent
          node. Related AI nodes include <strong>Guardrail</strong> (validate or filter),{' '}
          <strong>Classify</strong> (route by category), <strong>Browser Agent</strong> (act on web
          pages), and <strong>Generate</strong> (create an image, video, or audio file). See{' '}
          <a href="/agents">Agents</a>.
        </p>
        <p>
          An <strong>interface</strong> is a web page that displays workflow data and collects input. A
          page with a continue action <em>blocks</em> the run until the person continues. Otherwise it
          only displays, and the run proceeds. See <a href="/interfaces">Interfaces &amp; apps</a>.
        </p>
        <p>
          A <strong>table</strong> is a built-in spreadsheet your workflows read, search, and write. Rows
          have a flexible schema, and a row change can start a workflow through a <strong>Tables</strong> trigger. See{' '}
          <a href="/tables">Tables &amp; data</a>.
        </p>

        <h2>Execution modes</h2>
        <p>
          A workflow runs in one of two modes. <strong>Automatic</strong> (the default) executes every
          node as soon as its inputs are ready. <strong>Step-by-step</strong> pauses after each node so you
          can advance one step at a time, which is useful for debugging. Either way, a node becomes ready
          once all of its predecessors have completed or been skipped.
        </p>

        <h2>Run statuses</h2>
        <p>
          A run is always in one of 11 statuses: 5 active and 6 terminal. Once a status is terminal, it
          does not change again.
        </p>
        <DocsTable
          caption="Run statuses"
          head={['Status', 'Kind', 'Meaning']}
          rowHeaders
          rows={[
            [<code key="s">PENDING</code>, 'Active', 'Created, not started yet.'],
            [<code key="s">RUNNING</code>, 'Active', 'Executing nodes.'],
            [<code key="s">PAUSED</code>, 'Active', 'Suspended. The only status that can be resumed.'],
            [<code key="s">WAITING_TRIGGER</code>, 'Active', 'Waiting for its trigger to fire.'],
            [<code key="s">AWAITING_SIGNAL</code>, 'Active', 'Paused on a signal (see below).'],
            [<code key="s">COMPLETED</code>, 'Terminal', 'Finished successfully.'],
            [<code key="s">FAILED</code>, 'Terminal', 'Ended on an error.'],
            [<code key="s">PARTIAL_SUCCESS</code>, 'Terminal', 'A node verdict: the node collected items and some succeeded while others failed. Runs and epochs no longer end this way (older runs may still show it): an epoch with a failed step ends FAILED.'],
            [<code key="s">SKIPPED</code>, 'Terminal', 'The run or branch was skipped.'],
            [<code key="s">CANCELLED</code>, 'Terminal', 'Cancelled by a user.'],
            [<code key="s">TIMEOUT</code>, 'Terminal', 'Ran out of time.'],
          ]}
        />

        <h2>Signals: when a run pauses</h2>
        <p>
          A <strong>signal</strong> is a point where a run pauses (status <code>AWAITING_SIGNAL</code>)
          until something happens. There are six kinds:
        </p>
        <DocsTable
          caption="Signals that pause a run"
          head={['Signal', 'Blocks the run?', 'Resolves when']}
          rowHeaders
          rows={[
            ['Wait timer', 'Yes', 'the delay elapses. A wait of 3 seconds or less runs inline and never pauses the run.'],
            ['User approval', 'Yes', 'someone approves or rejects.'],
            ['Webhook wait', 'Yes', 'the awaited external webhook arrives.'],
            ['Interface', 'Only with a continue action', 'the person advances the page.'],
            ['Agent execution', 'Yes', 'a queued agent finishes (only when the agent runs in the background).'],
            ['Browser takeover', 'Yes, always', 'you hand control back to the browser agent.'],
          ]}
        />

        <h2>Credits and budgets</h2>
        <p>
          Work that costs money (model calls, generations, some integration calls) consumes{' '}
          <strong>credits</strong>. Every agent can be given a <strong>budget</strong> it cannot exceed.
          When an agent starts sub-agents, their budgets are reserved from the parent&apos;s, so the total
          spend of a whole agent tree is capped by the top budget. When a budget runs out, the agent
          stops and reports which limit it reached. See <a href="/agents">Agents</a> and{' '}
          <a href="/billing">Plans &amp; billing</a>.
        </p>
        <p>
          The cloud and the self-hosted Community Edition run the same engine but meter usage
          differently. See <a href="/">Overview</a> for the comparison.
        </p>

        <h2>Template expressions</h2>
        <p>
          Everywhere a node reads another step&apos;s output, it uses the same form:{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. Some examples:
        </p>
        <DocsTable
          caption="Example template expressions"
          head={['Expression', 'Reads']}
          rows={[
            [<code key="1">{'{{trigger:webhook.output.payload}}'}</code>, 'the body a webhook trigger received'],
            [<code key="2">{'{{core:check_amount.output.result}}'}</code>, 'the result of a core node'],
            [<code key="3">{'{{table:find_users.output.items}}'}</code>, 'the rows a table query returned'],
            [<code key="4">{'{{agent:summarize.output.response}}'}</code>, 'the text an agent generated'],
          ]}
        />
        <CodeBlock title="Using an expression in a message">{`New order from {{trigger:webhook.output.payload.email}}`}</CodeBlock>
        <p>
          See <a href="/expressions">Expressions &amp; variables</a> for the full syntax, functions, and
          workflow variables.
        </p>

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Build, branch, and version workflows in the canvas.
          </Card>
          <Card icon={Zap} title="Triggers" href="/triggers">
            The options of each of the eight trigger types.
          </Card>
          <Card icon={BookOpen} title="Glossary" href="/glossary">
            Every term, defined in a sentence or two.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
