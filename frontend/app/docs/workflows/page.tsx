import { Boxes, Webhook, Bot } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Workflows',
  description:
    'How LiveContext workflows are structured and executed: nodes, edges and ports, label normalization, data flow, branching and parallelism, loops, joins, pausing, per-step reliability, and execution modes.',
  path: '/docs/workflows',
});

export default function WorkflowsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Workflows"
        lead="A workflow is a directed graph of nodes joined by edges. You can build it by chatting or by editing the canvas directly. This page is the model underneath: how nodes are named and wired, how data actually flows, and exactly how branching, parallelism, loops, joins, pausing, and reliability policy behave."
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
            [<code key="p">core:</code>, 'Control flow &amp; utilities', 'Decision, Loop, Split, Transform, Wait, HTTP, Response'],
            [<code key="p">interface:</code>, 'Web pages', 'a page rendered in an iframe'],
            [<code key="p">note:</code>, 'Canvas annotations', 'non-executable, never affects the graph'],
          ]}
        />
        <p>
          <strong>Edges</strong> have the shape <code>{'{ from, to, input? }'}</code> and set{' '}
          <strong>execution order only</strong>, never data. Branching <code>core:</code> nodes expose
          named <strong>output ports</strong> - a suffix on the reference, <code>core:label:port</code> -
          and each port leads to exactly one successor:
        </p>
        <DocsTable
          head={['Node', 'Connectable output ports']}
          rows={[
            ['Decision', <code key="p">if · elseif_0 · elseif_1 · ... · else</code>],
            ['Switch', <code key="p">case_0 · case_1 · ... · default</code>],
            ['Option', <code key="p">choice_0 · choice_1 · ...</code>],
            ['Fork', <code key="p">branch_0 · branch_1 · ...</code>],
            ['Loop', <code key="p">body · exit</code>],
            ['User Approval', <code key="p">approved · rejected · timeout</code>],
            ['Split, Merge, Transform, Wait, Aggregate, Response', 'none (no edge ports)'],
          ]}
        />
        <Callout variant="warn">
          Loop has only <strong>two</strong> connectable outputs: <code>body</code> and{' '}
          <code>exit</code>. <code>iterate</code> is not a third output - it is the{' '}
          <strong>input</strong> target that the last step of the body connects <em>into</em> to close
          the loop (<code>{'mcp:last_step → core:loop:iterate'}</code>). Split, Merge, Transform, Wait,
          and Aggregate have no ports at all: Split spawns parallel item contexts internally rather than
          branching through ports.
        </Callout>

        <h3>Label normalization</h3>
        <p>
          Node keys are derived from your label by a fixed rule: accents are transliterated to ASCII,
          the text is lowercased, every non-alphanumeric character becomes <code>_</code>, repeated
          underscores collapse, and leading/trailing underscores are trimmed. So{' '}
          <em>&ldquo;My-API Call&rdquo;</em> becomes <code>mcp:my_api_call</code>. References must use
          this <strong>normalized</strong> form - <code>{'{{mcp:my_api_call.output.data}}'}</code>,
          never <code>{'{{mcp:My-API Call.output.data}}'}</code>.
        </p>
        <Callout variant="warn">
          Normalization is case- and punctuation-insensitive, so two labels that differ only in case
          or spacing (&ldquo;My Loop&rdquo; and &ldquo;my-loop&rdquo;) collapse to the same key and
          collide. Give nodes distinct names.
        </Callout>

        <h2>How data flows</h2>
        <p>
          Connecting nodes sets execution <strong>order</strong>, not data. To pass a value, reference
          it with <code>{'{{prefix:label.output.field}}'}</code>, resolved server-side at run time. A
          node can read only the outputs of nodes that already executed before it: triggers, prior
          steps or agents, and <code>current_item</code> inside a loop or split body. It{' '}
          <strong>cannot</strong> read descendants, a parallel sibling branch, itself, or an
          unconnected node.
        </p>
        <p>
          Every node&apos;s output is wrapped in a standard <code>output</code> object, so access is
          uniform across node types, and nested access works the same way everywhere:
        </p>
        <CodeBlock language="text">{`{{trigger:webhook.output.userId}}         → a field from the trigger payload
{{mcp:fetch_user.output.email}}           → the "Fetch user" step's email field
{{mcp:fetch_user.output.data.user.id}}    → nested field access
{{mcp:fetch_user.output.items[0]}}        → array index access
{{core:summary.output.transformed.total}} → a Transform node's computed field`}</CodeBlock>
        <p>
          Both <code>{'{{...}}'}</code> and <code>{'${...}'}</code> syntaxes are accepted;{' '}
          <code>{'{{...}}'}</code> is preferred. A value written as a single, whole{' '}
          <code>{'{{...}}'}</code> expression preserves its original type (a number stays a number, an
          object stays an object); wrapping it inside surrounding text always yields a string.
        </p>
        <p>
          Expressions also support built-in functions for everyday shaping - type casting, math,
          strings, dates, JSON, and general utilities - for example <code>now()</code>,{' '}
          <code>formatDate()</code>, <code>size()</code>, <code>isEmpty()</code>, <code>join()</code>,
          and <code>formatCurrency()</code>.
        </p>

        <h2>Building a workflow: readiness rules</h2>
        <p>
          Node execution is driven purely by whether a node&apos;s predecessors have{' '}
          <strong>resolved</strong> (completed or skipped) - there is no separate scheduler to reason
          about:
        </p>
        <ul>
          <li>
            <strong>Triggers</strong> are always READY at the start of a run. They never get SKIPPED,
            have no predecessor, and can have multiple successors - which makes an implicit Fork.
          </li>
          <li>
            A generic step, agent, or table node becomes READY only once <strong>all</strong> of its
            predecessors are resolved (completed or skipped). It can have multiple predecessors, which
            makes an implicit <strong>Merge</strong> (AND), and multiple successors, which makes an
            implicit <strong>Fork</strong>.
          </li>
          <li>
            You don&apos;t need an explicit Fork or Merge core to get that behavior: multiple edges
            leaving the same node is a Fork, multiple edges arriving at the same node is a Merge,
            whether or not a dedicated core is on the canvas.
          </li>
        </ul>

        <h2>Branching: exactly one path</h2>
        <p>
          Decision, Switch, and Option are <strong>mutually exclusive</strong> - each activates exactly
          one branch and skips the rest:
        </p>
        <DocsTable
          head={['Node', 'Selects on', 'Rule', 'Output']}
          rows={[
            [
              'Decision',
              'boolean conditions',
              'top to bottom, first condition that is true wins',
              <code key="p">selected_branch</code>,
            ],
            [
              'Switch',
              'a matched value (switchExpression vs each case value)',
              'first matching case wins, else default',
              <><code>selected_case</code>, <code>selected_case_index</code>, <code>skipped_cases</code>, <code>skipped_case_labels</code>, <code>switch_value</code>, <code>evaluations</code>, and (on a match) <code>matched_value</code>, <code>match_result</code>, <code>selected_case_label</code></>,
            ],
            [
              'Option',
              'a list of expressions, one per choice port',
              'first-true-wins across choice_0, choice_1, ...',
              '-',
            ],
          ]}
        />
        <p>
          Put the most specific condition first in a Decision, and the first matching case wins in a
          Switch even when a later case would also match. Because a branching node selects exactly one
          port, a failed branching node has no meaningful port to route through -{' '}
          <strong>continue-on-failure cannot be enabled on Decision, Switch, or Option</strong> (rejected
          when the workflow is parsed).
        </p>

        <h2>Parallelism: Fork and Split</h2>
        <p>
          <strong>Fork</strong> runs <strong>all</strong> of its branches in parallel with no condition;
          no branch is ever skipped. An explicit Fork core exposes <code>branch_0</code>,{' '}
          <code>branch_1</code>, ...; an implicit Fork is just multiple edges leaving one node - both
          behave identically. Parallel branches still follow the ancestor-only rule above, so they{' '}
          <strong>cannot see each other&apos;s outputs</strong> while running.
        </p>
        <p>
          <strong>Split</strong> fans a list into N parallel item contexts on a{' '}
          <strong>single path</strong>: the same body runs once per item, rather than running different
          branches like Fork. It evaluates its <code>list</code> expression once, then marks every body
          node READY for every item.
        </p>
        <p>
          Inside the split body, two values are injected per branch: <code>current_item</code> and{' '}
          <code>current_index</code> (0-based), also available as the shorthand{' '}
          <code>{'{{item}}'}</code> / <code>{'{{index}}'}</code>. These are{' '}
          <strong>runtime-only</strong>: they exist for the duration of that item&apos;s branch and are{' '}
          <strong>not persisted</strong>, so nothing downstream of the split can read them.
        </p>
        <CodeBlock language="text">{`{{core:process_orders.output.current_item}}   → the current item (runtime-only, inside the body)
{{item}}                                       → shorthand for current_item
{{core:process_orders.output.items}}           → the persisted array, readable downstream
{{core:process_orders.output.item_count}}       → how many items were spawned`}</CodeBlock>
        <p>Declared, persisted Split output fields (readable downstream of the split):</p>
        <DocsTable
          head={['Field', 'Meaning']}
          rows={[
            ['items', 'the evaluated list'],
            ['item_count', 'number of items spawned'],
            ['split_id', 'identifier for this split run'],
            ['terminated', 'whether the split has finished spawning'],
            [
              'exit_reason',
              <>on termination, <code key="p">empty_list</code> or <code key="p">all_items_processed</code></>,
            ],
          ]}
        />
        <Callout variant="warn">
          <strong>maxItems defaults to 100 with truncation.</strong> If a Split&apos;s{' '}
          <code>maxItems</code> is unset or 0 or less, it caps at 100 items; a longer list is silently
          truncated to the first 100. There is no engine-enforced ceiling above that - size your lists
          accordingly, or pre-filter upstream.
        </Callout>
        <Callout variant="warn">
          <strong>A failing item never stops its siblings.</strong> Split items run independently in
          parallel, and the run continues past a failed item regardless of the{' '}
          <code>splitStrategy</code> label (&ldquo;stop-on-error&rdquo; vs &ldquo;continue-anyway&rdquo;)
          you set: that label is stored and shown in the output for display, but nothing in the engine
          actually halts siblings on it. If you need to react to a failed item, check its result
          downstream (for example after an Aggregate) rather than relying on the strategy setting to
          stop the split.
        </Callout>

        <h2>Loops</h2>
        <p>
          A <strong>Loop</strong> repeats its <code>body</code> while <code>loopCondition</code> holds,
          re-evaluating the condition after every pass, then routes to <code>exit</code>. The last step
          of the body connects back into <code>core:label:iterate</code> to close the loop (see the
          ports callout above).
        </p>
        <DocsTable
          head={['Output field', 'Meaning']}
          rows={[
            ['iteration', '0 on first entry, incrementing each pass'],
            ['maxIterations', 'the configured safety limit'],
            ['terminated', 'whether the loop has finished'],
            ['enter_body', 'whether another pass is starting'],
            ['selected_path', 'which port the loop is routing to'],
            [
              'reason',
              <>present only once terminated: <code key="p">condition_false</code> or{' '}
                <code key="p">max_iterations_reached</code></>,
            ],
          ]}
        />
        <p>
          <code>{'{{core:label.output.iteration}}'}</code> is readable inside the body; per the node
          spec it starts at <code>0</code> on the first pass and increments from there.
        </p>
        <Callout variant="warn">
          <strong>A loop stops on the first body failure - it does not skip and continue.</strong> When
          a step inside the body FAILS, the loop stops immediately (no further iterations), routes to{' '}
          <code>exit</code> (which you can chain into a Decision for error handling), and is treated as
          failed for that path. This is the opposite of Split, which keeps going past a failed item.
        </Callout>

        <h2>Joins: Merge and Aggregate</h2>
        <p>
          <strong>Merge</strong> (an explicit core, or any node with multiple incoming edges) is{' '}
          <strong>always AND-mode</strong>: it becomes ready only once <strong>every</strong>{' '}
          predecessor is resolved (completed or skipped). There is no OR mode. A skipped predecessor
          still counts as resolved, so Merge proceeds once all predecessors are completed or skipped -
          it is itself skipped only when every predecessor was skipped. Merge&apos;s own output is{' '}
          <code>merged_branches</code> (the contributing source keys); you still read each branch&apos;s
          actual data through that branch&apos;s own key, e.g.{' '}
          <code>{'{{mcp:api_call.output.data}}'}</code>.
        </p>
        <p>
          <strong>Aggregate</strong> is the counterpart to Split: it collects the parallel item
          contexts spawned by a Split back into one output, sitting in a <code>collecting</code> state
          until every expected item has arrived (a partial state exposes <code>received</code> and{' '}
          <code>expected</code>). Its finished output is <code>aggregated_count</code> (alias{' '}
          <code>count</code>) plus one list per aggregation field you configure (each{' '}
          <code>label → expression</code> pair produces its own top-level list of collected values).
          There is no fixed <code>total_count</code>/<code>success_count</code>/<code>failed_count</code>
          - only the fields you define.
        </p>

        <h2>Pausing the run</h2>
        <p>
          <strong>Wait</strong> pauses for a duration (e.g. 5s, 5m, 1h, 1d). For durations of 3000 ms or
          less it sleeps inline, in slices of at most 100 ms, so a run cancel signal still takes effect
          within roughly 100 ms. For longer durations it registers a timer signal and yields instead of
          blocking a worker, resuming automatically when the timer expires. Either way the completed
          output is the same: <code>status</code> (<code>completed</code>), <code>waited_ms</code>,{' '}
          <code>started_at</code>, <code>completed_at</code>; the longer, signal-based path additionally
          exposes <code>duration_ms</code> and <code>expires_at</code> while it is pending.
        </p>
        <p>
          <strong>User Approval</strong> registers a signal and pauses the run until someone resolves
          it, then routes to one of its ports: <code>approved</code>, <code>rejected</code>, or{' '}
          <code>timeout</code>. It supports multi-level approval via <code>requiredApprovals</code>{' '}
          (always at least 1) and an optional list of <code>approverRoles</code> to restrict who can
          respond. Left unset, the approval <strong>times out after 24 hours</strong>: whatever{' '}
          <code>timeoutMs</code> you configure is honored, but a value of 0 or less falls back to the
          24-hour default, and expiry routes to the <code>timeout</code> port.
        </p>
        <DocsTable
          head={['Output field', 'Meaning']}
          rows={[
            ['approver_roles', 'the roles allowed to respond'],
            ['required_approvals', 'how many approvals were required'],
            ['expires_at', 'when the approval times out'],
            ['approval_context', 'the resolved approval context shown to the approver (see below)'],
            ['selected_port', 'which port the run took: approved, rejected, or timeout'],
          ]}
        />
        <Callout variant="info">
          User Approval also accepts an optional <code>contextTemplate</code> (a literal string, or one
          containing <code>{'{{...}}'}</code> expressions) rendered when the node pauses and shown to
          the approver alongside the request. The resolved text survives resolution as the node output{' '}
          <code>approval_context</code>, readable downstream as{' '}
          <code>{'{{core:<label>.output.approval_context}}'}</code>. This is a newer capability; if your
          workspace does not yet show <code>approval_context</code> in the node&apos;s output, it has
          not reached your build yet.
        </Callout>
        <p>
          <strong>Exit</strong> ends execution along <strong>only its own branch</strong>: it has no
          successors, so parallel Fork or Split branches keep running unaffected, and the branch that
          exited is marked <strong>successful</strong>, not failed. It takes an optional{' '}
          <code>reason</code> (defaulting to &ldquo;Branch exited&rdquo;) and outputs that same{' '}
          <code>reason</code> plus <code>exited: true</code>.
        </p>

        <h2>Per-step reliability policy</h2>
        <p>
          Any executed node (<code>mcp:</code>, <code>table:</code>, <code>agent:</code>,{' '}
          <code>core:</code>, <code>interface:</code> - not triggers or notes) can carry an optional
          reliability policy, applied uniformly by the engine. A node with no policy takes the
          byte-identical default single-attempt path.
        </p>
        <DocsTable
          head={['Field', 'Default (no-op)', 'What it does']}
          rows={[
            ['retryCount', '0', 'additional attempts after a failure; total attempts = retryCount + 1'],
            ['retryBackoffMs', '0', 'delay between attempts; blocks only the executing branch or item'],
            [
              'continueOnFailure',
              'false',
              'on final failure the node is still marked FAILED, but its successors run instead of cascading skips',
            ],
            [
              'timeoutMs',
              '0 (no bound)',
              'a per-attempt bound; on expiry the attempt fails, flagged policy_timeout, then composes with retries/backoff',
            ],
            [
              'executeOnce',
              'false',
              'inside a Split, run only for item index 0 and skip the rest; a no-op outside a split, and it does not limit loop iterations',
            ],
          ]}
        />
        <Callout variant="info">
          <strong>Only the terminal attempt is persisted and billed</strong> - one logical node
          execution costs one platform credit regardless of how many retries it took. Non-final attempts
          are not billed separately. An agent node&apos;s LLM token cost remains per call, independent
          of this.
        </Callout>
        <Callout variant="warn">
          Retries <strong>re-run side effects</strong> - retrying is opt-in because only you can judge
          whether a step is safe to repeat (an email send, a payment call). A timed-out node may also
          keep running in the background on its worker thread: its side effects are not cancelled or
          rolled back just because the attempt was marked failed for timing out.
        </Callout>
        <p>
          A few combinations are rejected when the workflow is parsed, rather than failing silently at
          run time: <code>continueOnFailure</code> on Decision, Switch, or Option (a branching node
          that fails selects no port to continue through); <code>executeOnce</code> on Split, Aggregate,
          Merge, or Loop; and any negative value for these fields.
        </p>

        <h2>Execution modes</h2>
        <p>
          In <strong>Automatic</strong> mode (the default) every node runs as soon as all of its
          predecessors are resolved; independent ready nodes run concurrently. In{' '}
          <strong>Step-by-step</strong> mode you advance one node at a time for debugging, the run
          surfaces <code>PAUSED</code> / <code>RESUMING</code> status events as you go, and you choose
          the order at a Fork.
        </p>
        <p>
          Some nodes <strong>pause</strong> the run on a signal - a wait timer, a user approval, a
          webhook wait, or a blocking interface - and resume automatically once the signal resolves;
          those nodes report an <code>AWAITING_SIGNAL</code> result while paused. See{' '}
          <a href="/concepts">Core concepts</a> for how runs, epochs, and signals fit together.
        </p>
        <p>Every node in a run carries one of these statuses:</p>
        <DocsTable
          head={['Status', 'Meaning']}
          rows={[
            ['PENDING', 'not yet reachable - predecessors have not all resolved'],
            ['READY', 'all predecessors resolved, about to execute'],
            ['RUNNING', 'currently executing'],
            ['COMPLETED', 'finished successfully (terminal)'],
            ['FAILED', 'finished with an error (terminal)'],
            ['SKIPPED', 'not taken - e.g. the other branch of a Decision (terminal)'],
            ['AWAITING_SIGNAL', 'paused on a wait timer, approval, webhook wait, or blocking interface'],
            ['WAITING_TRIGGER', 'waiting for its own trigger to fire'],
            ['COLLECTING', 'an Aggregate waiting for more Split items to arrive'],
          ]}
        />
        <p>
          The terminal set is <code>COMPLETED</code> / <code>SKIPPED</code> / <code>FAILED</code> - once
          a node reaches one of those, it does not change again for that run.
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
