import { Boxes, Webhook, Braces } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Workflows',
  description:
    'Build workflows on the canvas, then understand how they run: nodes, edges and ports, data flow, branching, parallelism, loops, joins, pausing, reliability, versions and production.',
  path: '/docs/workflows',
});

export default function WorkflowsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Workflows"
        lead="A workflow is a directed graph of nodes joined by edges. You can build it by chatting or by editing the canvas directly. This page shows how to build one in the canvas, then explains the model underneath: how nodes are named and wired, how data flows, and how branching, parallelism, loops, joins, pausing, reliability, and versions behave."
      />

      <DocsProse>
        <h2>Build a workflow in the canvas</h2>
        <p>
          The canvas is the visual editor of a workflow. You can also ask the assistant in chat to build
          or change a workflow for you; both edit the same plan.
        </p>
        <Steps>
          <Step n={1} title="Create the workflow">
            From your workflows list, click <strong>Create Workflow</strong>, enter a <strong>Name</strong>{' '}
            (the <strong>Description</strong> is optional), then click <strong>Create Workflow</strong>. The
            empty canvas opens with a chat prompt (&ldquo;How can I help you?&rdquo;) and a{' '}
            <strong>Trigger</strong> button to pick a first trigger.
          </Step>
          <Step n={2} title="Add nodes from the palette">
            Click <strong>Add node</strong> (the <strong>+</strong> button at the top right of the canvas) to
            open the node palette. It has five categories: <strong>Triggers</strong>,{' '}
            <strong>Integrations</strong>, <strong>AI</strong>, <strong>Flow</strong>, and <strong>Core</strong>,
            plus a search box. Click a node to add it, or drag it onto the canvas. To insert a node between
            two connected nodes, hover the edge between them and click its <strong>Add node</strong> button.
          </Step>
          <Step n={3} title="Connect the nodes">
            Drag from a node&apos;s output handle to the next node. Each output port of a branching node
            (for example the <code>if</code> port of an <strong>If / else</strong>) leads to one node only; to
            run several nodes in parallel from it, add a <strong>Fork</strong>. Drawing an edge back to a node
            that already ran creates a loop edge (see <a href="#loops">Loops</a>).
          </Step>
          <Step n={4} title="Configure each node in the inspector">
            Click a node to open its inspector. The <strong>Edit</strong> tab holds the node&apos;s settings;
            reference data from earlier nodes with <code>{'{{...}}'}</code> expressions (see{' '}
            <a href="/expressions">Expressions &amp; variables</a>). In the canvas <strong>Settings</strong>{' '}
            (gear button in the canvas toolbar) you choose where the inspector docks (
            <strong>Floating</strong> or <strong>Side panel</strong>) and what a node click opens (
            <strong>Settings only</strong> or <strong>Settings, input and output</strong>).
          </Step>
          <Step n={5} title="Fix validation issues">
            Validation runs as you edit. When something is missing, an info icon appears next to{' '}
            <strong>Add node</strong> with a count such as &ldquo;1 error, 2 warnings&rdquo;. Open it and
            click an entry to jump to the node concerned.
          </Step>
          <Step n={6} title="Save">
            Click <strong>Save</strong> in the page header. Every save creates a new version of the workflow.
          </Step>
          <Step n={7} title="Run it">
            Click <strong>Run</strong> in the header to run the workflow automatically; its arrow offers{' '}
            <strong>Run (auto)</strong> and <strong>Step-by-step (debug)</strong>. You can also start from a
            specific trigger with the play button under that trigger node. Starting a run from the editor
            saves your changes first. Switch the canvas between editing and runs with the toggle at the top of
            the canvas (tooltips <strong>Edition</strong> and <strong>Runs</strong>); in runs mode the
            inspector adds a <strong>Run data</strong> tab with the node&apos;s input and output.
          </Step>
          <Step n={8} title="Put a version in production">
            When the workflow behaves as expected, set a version as production so that its triggers fire
            for real (see <a href="#versions-and-production">Versions and production</a>).
          </Step>
        </Steps>
        <Callout variant="tip" title="Test without calling real services">
          A node&apos;s inspector has a <strong>Mock output</strong> section: a custom JSON output, the
          catalog&apos;s example response, or a simulated error. Editor runs return the mock instead of
          executing the node. Production runs are never mocked.
        </Callout>
        <p>
          Right-click a node or the empty canvas for more actions, such as <strong>Duplicate</strong>,{' '}
          <strong>Delete</strong>, <strong>Add node</strong>, <strong>Auto-layout</strong>, or{' '}
          <strong>Fit view</strong>.
        </p>

        <h2>Nodes, edges and ports</h2>
        <p>
          Every node has a normalized key of the form <code>prefix:label</code>. The prefix marks the
          category; there are seven:
        </p>
        <DocsTable
          caption="Node key prefixes"
          rowHeaders
          head={['Prefix', 'Category', 'Examples']}
          rows={[
            [<code key="p">trigger:</code>, 'Entry points', 'webhook, schedule, chat, form, manual, table, workflow, error'],
            [<code key="p">mcp:</code>, 'Integration steps', 'an API call from the catalog'],
            [<code key="p">table:</code>, 'Built-in table operations', 'find, create, update, delete rows'],
            [<code key="p">agent:</code>, 'AI nodes', 'Agent, Classify, Guardrail, Browser Agent, Generate'],
            [<code key="p">core:</code>, 'Control flow and utilities', 'If / else, While, Split, Transform, Wait, HTTP Request'],
            [<code key="p">interface:</code>, 'Web pages', 'a page rendered in an iframe'],
            [<code key="p">note:</code>, 'Canvas annotations', 'never executes, never affects the graph'],
          ]}
        />
        <p>
          An <strong>edge</strong> has the shape <code>{'{ from, to }'}</code>, plus an optional{' '}
          <code>backEdge</code> marker for a loop edge. Edges set <strong>execution order only</strong>,
          never data. Branching nodes expose named <strong>output ports</strong>, written as a suffix on the
          reference (<code>core:label:port</code>), and each port leads to exactly one successor:
        </p>
        <DocsTable
          caption="Output ports of branching nodes"
          rowHeaders
          head={['Node', 'Connectable output ports']}
          rows={[
            ['If / else (Decision)', <code key="p">if, elseif_0, elseif_1, ..., else</code>],
            ['Switch', <code key="p">case_0, case_1, ..., default</code>],
            ['Option', <code key="p">choice_0, choice_1, ...</code>],
            ['Fork', <code key="p">branch_0, branch_1, ...</code>],
            ['While (Loop)', <code key="p">body, exit</code>],
            ['User Approval', <code key="p">approved, rejected, timeout</code>],
            ['Classify', <code key="p">category_0, category_1, ...</code>],
            ['Guardrail', <code key="p">pass, fail</code>],
            ['Split, Merge, Transform, Wait, Aggregate', 'none'],
          ]}
        />
        <Callout variant="info">
          A While loop has only <strong>two</strong> connectable outputs: <code>body</code> and{' '}
          <code>exit</code>. <code>iterate</code> is not a third output: it is the <strong>input</strong>{' '}
          that the last step of the body connects into to close the loop (
          <code>{'mcp:last_step → core:loop:iterate'}</code>). Split spawns parallel item contexts
          internally instead of branching through ports.
        </Callout>

        <h3>Label normalization</h3>
        <p>
          Node keys are derived from your label by a fixed rule: accents are transliterated to ASCII,
          the text is lowercased, every non-alphanumeric character becomes <code>_</code>, repeated
          underscores collapse, and leading and trailing underscores are trimmed. So{' '}
          <em>&ldquo;My-API Call&rdquo;</em> becomes <code>mcp:my_api_call</code>. Write references with
          the <strong>normalized</strong> form: <code>{'{{mcp:my_api_call.output.data}}'}</code>.
        </p>
        <Callout variant="warn">
          Two labels that differ only in case or punctuation (&ldquo;My Loop&rdquo; and
          &ldquo;my-loop&rdquo;) collapse to the same key and collide. Give nodes distinct names.
        </Callout>

        <h2>How data flows</h2>
        <p>
          Connecting nodes sets execution <strong>order</strong>, not data. To pass a value, reference
          it with <code>{'{{prefix:label.output.field}}'}</code>, resolved at run time. A node can read
          only the outputs of nodes that ran before it on its path: triggers, earlier steps, and the
          current item inside a split body. It <strong>cannot</strong> read its descendants, a parallel
          sibling branch, itself, or an unconnected node.
        </p>
        <CodeBlock title="Reference examples">{`{{trigger:webhook.output.payload.userId}}  → a field of the webhook request body
{{mcp:fetch_user.output.email}}            → the "Fetch user" step's email field
{{mcp:fetch_user.output.data.user.id}}     → nested field access
{{mcp:fetch_user.output.items[0]}}         → array index access
{{core:summary.output.transformed.total}}  → a Transform node's computed field`}</CodeBlock>
        <p>
          A webhook trigger puts the request body under <code>payload</code>; its other outputs are{' '}
          <code>headers</code>, <code>query</code>, <code>method</code>, <code>triggered_at</code>, and{' '}
          <code>triggered_by</code>.
        </p>
        <p>
          A value written as a single, whole <code>{'{{...}}'}</code> expression keeps its type (a number
          stays a number, an object stays an object); an expression embedded in surrounding text always
          yields a string. Expressions also offer built-in functions such as <code>now()</code>,{' '}
          <code>formatdate()</code>, <code>coalesce()</code>, and <code>json()</code>. See{' '}
          <a href="/expressions">Expressions &amp; variables</a> for the syntax and the full function list.
        </p>

        <h2>Readiness rules</h2>
        <p>
          A node runs when all of its predecessors have <strong>resolved</strong> (completed or skipped):
        </p>
        <ul>
          <li>
            <strong>Triggers</strong> are ready at the start of a run. A trigger with several successors
            makes an implicit Fork.
          </li>
          <li>
            Any other node becomes ready once <strong>all</strong> of its predecessors are resolved. Several
            incoming edges make an implicit <strong>Merge</strong> (AND); several outgoing edges make an
            implicit <strong>Fork</strong>.
          </li>
          <li>
            You don&apos;t need an explicit Fork or Merge node to get that behavior: the edges alone decide
            it.
          </li>
        </ul>

        <h2>Branching: exactly one path</h2>
        <p>
          If / else, Switch, and Option are <strong>mutually exclusive</strong>: each activates exactly one
          branch and skips the rest.
        </p>
        <DocsTable
          caption="Branching nodes"
          rowHeaders
          head={['Node', 'Selects on', 'Rule', 'Main outputs']}
          rows={[
            [
              'If / else (Decision)',
              'boolean conditions',
              'top to bottom, the first true condition wins',
              <><code>selected_branch</code>, <code>selected_branch_index</code>, <code>skipped_branches</code>, <code>evaluations</code></>,
            ],
            [
              'Switch',
              'a value compared with each case',
              'the first matching case wins, else default',
              <><code>selected_branches</code> (a string, the matched label), <code>selected_case_index</code>, <code>skipped_branches</code>, <code>evaluations</code></>,
            ],
            [
              'Option',
              'one expression per choice port',
              'the first true choice wins',
              <><code>selected_choice</code>, <code>selected_label</code>, <code>selected_choice_index</code>, <code>skipped_branches</code>, <code>evaluations</code></>,
            ],
          ]}
        />
        <p>
          Put the most specific condition first. Because a branching node selects exactly one port, a
          failed branching node has no port to route through, so{' '}
          <strong>continue-on-failure cannot be enabled on If / else, Switch, or Option</strong>.
        </p>

        <h2>Parallelism: Fork and Split</h2>
        <p>
          <strong>Fork</strong> runs <strong>all</strong> of its branches in parallel, with no condition. An
          explicit Fork exposes <code>branch_0</code>, <code>branch_1</code>, and so on; an implicit Fork is
          just several edges leaving one node. Parallel branches <strong>cannot see each other&apos;s
          outputs</strong> while running.
        </p>
        <p>
          <strong>Split</strong> fans a list into parallel item contexts on a <strong>single path</strong>:
          the same body runs once per item. It evaluates its <code>list</code> expression once. Inside the
          body, <code>current_item</code> and <code>current_index</code> (0-based) are available, also as the
          shorthands <code>{'{{item}}'}</code> and <code>{'{{index}}'}</code>. They are{' '}
          <strong>runtime-only</strong>: nothing downstream of the split can read them.
        </p>
        <CodeBlock title="Split references">{`{{core:process_orders.output.current_item}}   → the current item (inside the body only)
{{item}}                                       → shorthand for current_item
{{core:process_orders.output.items}}           → the persisted list, readable downstream
{{core:process_orders.output.item_count}}      → how many items were spawned`}</CodeBlock>
        <DocsTable
          caption="Split output fields"
          rowHeaders
          head={['Field', 'Meaning']}
          rows={[
            ['items', 'the evaluated list'],
            ['item_count', 'number of items spawned'],
            ['split_id', 'identifier of this split'],
            [
              'spawn_reason',
              <><code key="a">items_spawned</code> or <code key="b">empty_list</code></>,
            ],
            ['terminated', 'always true once the split has spawned its items'],
          ]}
        />
        <Callout variant="warn" title="maxItems caps and truncates">
          When a Split&apos;s <code>maxItems</code> is unset or 0, it caps at 100 items and a longer list is
          silently truncated to the first 100. <code>maxItems</code> can also be an expression; it must then
          resolve to a positive whole number, or the Split fails.
        </Callout>
        <Callout variant="warn" title="A failing item never stops its siblings">
          Split items run independently, and the run continues past a failed item. React to failed items
          downstream (for example after an Aggregate) instead of expecting the split to stop.
        </Callout>

        <h2 id="loops">Loops</h2>
        <p>There are two ways to repeat steps.</p>
        <h3>The While node</h3>
        <p>
          <strong>While</strong> repeats its <code>body</code> while its condition holds, re-evaluating it
          after every pass, then routes to <code>exit</code>. Connect the last step of the body into{' '}
          <code>core:label:iterate</code> to close the loop. Its safety limit, <code>maxIterations</code>,
          defaults to 10 (range 1 to 10,000) and can also be an expression that resolves to a positive whole
          number.
        </p>
        <h3>A loop edge</h3>
        <p>
          On the canvas, drawing an edge from a node back to a node that already ran (for example from the{' '}
          <code>else</code> port of an If / else back to a fetch step) creates a dashed{' '}
          <strong>loop edge</strong>, with no While node. Select it to open the <strong>Loop Edge</strong>{' '}
          panel: <strong>Condition (SpEL)</strong> is re-evaluated before each new pass (leave it empty to loop
          every time the edge is taken), and <strong>Max iterations</strong> defaults to the inherited limit
          of 10.
        </p>
        <h3>How a loop ends</h3>
        <DocsTable
          caption="Loop termination reasons"
          rowHeaders
          head={['reason', 'When', 'Result']}
          rows={[
            [<code key="r">condition_false</code>, 'the condition said stop', 'the run continues through exit'],
            [
              <code key="r">iterations_exhausted</code>,
              'a While with no condition ran its maxIterations passes ("repeat N times")',
              'the run continues through exit',
            ],
            [
              <code key="r">max_iterations_reached</code>,
              'the limit was reached while the condition still asked for another pass',
              <><strong>the run fails</strong> and exit is not taken</>,
            ],
          ]}
        />
        <Callout variant="warn" title="A limit that is too low fails the run">
          Size <code>maxIterations</code> above the number of passes your data needs. Reaching it while the
          loop still wants to continue is treated as a failure, not as a quiet exit.
        </Callout>
        <DocsTable
          caption="While output fields"
          rowHeaders
          head={['Field', 'Meaning']}
          rows={[
            ['iteration', '0 on the first pass, then incremented; readable inside the body'],
            ['maxIterations', 'the configured limit'],
            ['terminated', 'whether the loop has finished'],
            ['enter_body', 'whether another pass is starting'],
            ['selected_path', 'the port the loop routes to'],
            ['reason', 'present once terminated (see the table above)'],
          ]}
        />

        <h2>Joins: Merge and Aggregate</h2>
        <p>
          <strong>Merge</strong> (an explicit node, or any node with several incoming edges) is{' '}
          <strong>always AND</strong>: it waits for <strong>every</strong> predecessor. There is no OR mode.
          Its outputs describe the join (<code>merged_branches</code>, <code>sources</code>,{' '}
          <code>source_count</code>, <code>success_count</code>); read each branch&apos;s data through that
          branch&apos;s own key, for example <code>{'{{mcp:api_call.output.data}}'}</code>.
        </p>
        <p>
          <strong>Aggregate</strong> collects the item contexts spawned by a Split back into one output. It
          waits in a <code>collecting</code> state until every expected item has arrived. Its output is{' '}
          <code>aggregated_count</code> (alias <code>count</code>) plus one list per field you configure (each{' '}
          <code>label → expression</code> pair produces its own list).
        </p>

        <h2>Pausing the run</h2>
        <p>
          <strong>Wait</strong> pauses for a duration. In the canvas you set it in milliseconds, up to 10
          minutes, with presets from 100 ms to 10 m. Up to 3000 ms it sleeps inline and a cancel still takes
          effect within about 100 ms; longer waits register a timer and resume automatically when it expires.
          Outputs: <code>status</code>, <code>waited_ms</code>, <code>started_at</code>,{' '}
          <code>completed_at</code>, plus <code>duration_ms</code> and <code>expires_at</code> on the timer
          path.
        </p>
        <p>
          <strong>User Approval</strong> pauses the run until someone responds, then routes to{' '}
          <code>approved</code>, <code>rejected</code>, or <code>timeout</code>. It supports several required
          approvals (<code>requiredApprovals</code>, at least 1) and an optional list of approver roles. It{' '}
          <strong>times out after 24 hours</strong> unless you set another timeout. An optional context
          template is rendered when the node pauses, shown to the approver, and kept as the output{' '}
          <code>approval_context</code>.
        </p>
        <DocsTable
          caption="User Approval output fields"
          rowHeaders
          head={['Field', 'Meaning']}
          rows={[
            ['approver_roles', 'the roles allowed to respond'],
            ['required_approvals', 'how many approvals were required'],
            ['expires_at', 'when the approval times out'],
            ['approval_context', 'the rendered context shown to the approver'],
            ['selected_port', 'approved, rejected, or timeout'],
          ]}
        />
        <p>
          <strong>Exit</strong> ends <strong>only its own branch</strong>: parallel Fork or Split branches
          keep running, and the exited branch counts as successful. It takes an optional{' '}
          <code>reason</code> (default &ldquo;Branch exited&rdquo;) and outputs <code>reason</code>,{' '}
          <code>status</code> (<code>exited</code>), and <code>exited_at</code>.
        </p>

        <h2>Per-step reliability policy</h2>
        <p>
          Any executed node (<code>mcp:</code>, <code>table:</code>, <code>agent:</code>, <code>core:</code>,{' '}
          <code>interface:</code>; not triggers or notes) can carry an optional reliability policy. A node
          with no policy runs once.
        </p>
        <DocsTable
          caption="Reliability policy fields"
          rowHeaders
          head={['Field', 'Default', 'What it does']}
          rows={[
            ['retryCount', '0', 'additional attempts after a failure; total attempts = retryCount + 1'],
            ['retryBackoffMs', '0', 'delay between attempts; blocks only the executing branch or item'],
            [
              'continueOnFailure',
              'false',
              'on final failure the node is still marked FAILED, but its successors run instead of being skipped',
            ],
            [
              'timeoutMs',
              '0 (no limit)',
              'a limit per attempt; on expiry the attempt fails and retries apply',
            ],
            [
              'executeOnce',
              'false',
              'inside a Split, run only for the first item and skip the rest; no effect outside a split',
            ],
          ]}
        />
        <Callout variant="info">
          <strong>Only the final attempt is billed</strong>: one logical node execution costs one platform
          credit however many retries it took. An agent node&apos;s model usage is billed per call,
          independently of this.
        </Callout>
        <Callout variant="warn">
          Retries <strong>repeat side effects</strong> (an email send, a payment call), which is why retrying
          is opt-in. A timed-out attempt may also keep running in the background: its side effects are not
          cancelled or rolled back.
        </Callout>
        <p>
          Some combinations are rejected up front instead of failing at run time: <code>continueOnFailure</code> on If /
          else, Switch, or Option; <code>executeOnce</code> on Split, Aggregate, Merge, or While; and any
          negative value.
        </p>

        <h2>Execution modes and node statuses</h2>
        <p>
          In <strong>automatic</strong> mode every node runs as soon as its predecessors are resolved, and
          independent ready nodes run concurrently. In <strong>step-by-step</strong> mode you advance one node
          at a time, which is useful for debugging.
        </p>
        <p>
          Some nodes <strong>pause</strong> the run on a signal (a long wait, a user approval, a webhook wait,
          or a blocking interface) and resume once the signal resolves. See{' '}
          <a href="/runs">Runs &amp; execution</a> for runs, epochs, and re-running steps.
        </p>
        <DocsTable
          caption="Node statuses"
          rowHeaders
          head={['Status', 'Meaning']}
          rows={[
            ['PENDING', 'predecessors have not all resolved yet'],
            ['READY', 'all predecessors resolved, about to execute'],
            ['RUNNING', 'currently executing'],
            ['COMPLETED', 'finished successfully (terminal)'],
            ['FAILED', 'finished with an error (terminal)'],
            ['SKIPPED', 'not taken, for example the other branch of an If / else (terminal)'],
            ['AWAITING_SIGNAL', 'paused on a timer, approval, webhook wait, or blocking interface'],
            ['WAITING_TRIGGER', 'waiting for its trigger to fire'],
            ['COLLECTING', 'an Aggregate waiting for more Split items'],
          ]}
        />

        <h2 id="versions-and-production">Versions and production</h2>
        <p>
          Every save creates a new <strong>version</strong>. Open <strong>Version History</strong> with the
          arrow next to <strong>Save</strong> to see each version with its date, node count, and number of
          runs. From there you can click an earlier version to restore it on the canvas, rename a version,
          or use the pin icon to <strong>Set as production</strong>.
        </p>
        <p>
          The <strong>production</strong> version is the one real traffic runs on. Production triggers
          (<strong>Webhook</strong>, <strong>Scheduler</strong>, <strong>Tables</strong>, <strong>Chat</strong>, <strong>Form</strong>, and <strong>Workflows</strong> triggers) fire only into the production
          version; while no version is in production, they do not fire. Runs you start from the editor do not
          need a production version.
        </p>
        <Steps>
          <Step n={1} title="Choose the version">
            Use the pin button under a trigger node or in the canvas toolbar (it targets the version on the
            canvas), or the pin icon of a version in <strong>Version History</strong>.
          </Step>
          <Step n={2} title="Confirm">
            Click <strong>Set as production</strong>. If the canvas has unsaved changes, the button offers{' '}
            <strong>Save &amp; set as production</strong>, which saves a new version first. If another version
            is already in production, the dialog confirms the move from one version to the other.
          </Step>
        </Steps>
        <Callout variant="info">
          You do not need to run a version before setting it as production. If it has no run yet, one is
          created for it; nothing executes and no credits are used. Every trigger fire then adds a new epoch
          to that production run.
        </Callout>
        <p>
          <strong>Remove from production</strong> (the same pin control on the production version) stops the
          production triggers until you set a version again. See <a href="/triggers">Triggers</a> for how each
          trigger type fires.
        </p>

        <h2>Troubleshooting</h2>
        <h3>An HTTP Request returned an error but the node is green</h3>
        <p>
          A non-2xx response (404, 500, 502...) does <strong>not</strong> fail an HTTP Request node. The node
          completes with <code>output.success</code> set to <code>false</code>, <code>output.status</code>{' '}
          set to the status code, and <code>output.data</code> holding the error body, and the rest of the
          workflow runs. Only transport errors (DNS, connection, timeout) fail the node. Check the response
          explicitly, for example with an If / else on <code>{'{{core:call_api.output.success}}'}</code>.
        </p>
        <h3>A file collected by Aggregate cannot be used downstream</h3>
        <p>
          Values collected by an Aggregate after a Split are converted to text: numbers become strings, and
          objects, including files, become text that later nodes cannot use as a file. To pass files or typed
          objects onward, collect them in a <strong>Code</strong> node instead, which keeps real JSON types.
        </p>
        <h3>A sub-workflow step succeeded but produced nothing</h3>
        <p>
          A <strong>Sub-Workflow</strong> node reports success as soon as the child workflow&apos;s run cycle
          has finished, even if a node inside the child failed. Check the child&apos;s actual output rather
          than the node&apos;s status. The child&apos;s outputs are keyed by the child node&apos;s key{' '}
          <strong>without its prefix</strong>:{' '}
          <code>{'{{core:call_child.output.result.step_result.output.transformed.url}}'}</code>, not{' '}
          <code>result.core:step_result</code>.
        </p>
        <h3>A loop failed with max_iterations_reached</h3>
        <p>
          The loop hit its limit while its condition still asked for another pass. Raise{' '}
          <strong>Max iterations</strong> (or <code>maxIterations</code> on the While node), or check that the
          condition eventually becomes false.
        </p>
        <h3>A reference resolves to nothing</h3>
        <p>
          Check the node key (the <strong>normalized</strong> label), the <code>.output.</code> segment, and
          that the referenced node runs before this one on the same path. An expression that fails to
          evaluate resolves to empty without an error; see{' '}
          <a href="/expressions">Expressions &amp; variables</a>.
        </p>

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            Every node type, grouped, with its ports and outputs.
          </Card>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            Every way a run can start.
          </Card>
          <Card icon={Braces} title="Expressions & variables" href="/expressions">
            The template syntax, functions, and workspace variables.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
