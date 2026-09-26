import { Workflow, Bot, LayoutPanelLeft, Webhook } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Runs & execution',
  description:
    'How a workflow run behaves once it starts: statuses, epochs and spawns, execution modes, run controls, signals and approvals, re-running a step, cost, and the run panel.',
  path: '/docs/runs',
});

export default function RunsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Runs & execution"
        lead="A run is one execution instance of a workflow. This page covers what happens after it starts: the statuses a run moves through, how to pause, stop, cancel, or reactivate it, how signals and approvals hold it, how to re-run a step, and how to read the run panel."
      />

      <DocsProse>
        <h2>What a run is</h2>
        <p>
          Every execution belongs to a <strong>run</strong>, identified by a run id and listed in
          the workflow&apos;s run history. Because every trigger type is reusable, a run usually
          lives for a long time: each time a trigger fires, it opens a new <strong>epoch</strong> on
          the same run and executes the graph again. The run&apos;s start time is when it was
          created; each epoch has its own start time, and the most recent one is the run&apos;s{' '}
          <strong>last fire</strong>.
        </p>
        <h3>Which run a fire lands in</h3>
        <DocsTable
          caption="Which run receives a fire"
          rowHeaders
          head={['Fire', 'Run it lands in']}
          rows={[
            [
              'From the editor',
              'The live run of the same version, when it exists and is compatible. A new run is created only for a new version or when no live run exists.',
            ],
            [
              'From production (webhook, schedule, table, chain, public chat or form)',
              <>The <strong key="p">production run</strong> of the pinned version. See <a key="l" href="/triggers">Triggers</a>.</>,
            ],
            ['Error trigger', 'The handler workflow’s newest live run.'],
          ]}
        />

        <h2>Run statuses</h2>
        <p>
          A run is always in exactly one of 11 statuses: 5 non-terminal (active or idle) and 6
          terminal (finished).
        </p>
        <DocsTable
          caption="Run statuses"
          rowHeaders
          head={['Status', 'Terminal?', 'Meaning']}
          rows={[
            ['PENDING', 'No', 'Created, not started yet.'],
            ['RUNNING', 'No', 'Actively executing.'],
            ['PAUSED', 'No', 'Paused by a user. The only status that can be resumed.'],
            ['AWAITING_SIGNAL', 'No', 'A node is waiting on a signal, such as an approval (see below).'],
            ['WAITING_TRIGGER', 'No', 'Idle between two fires of a reusable trigger.'],
            ['COMPLETED', 'Yes', 'Finished successfully. The only status counted as a success.'],
            ['PARTIAL_SUCCESS', 'Yes', 'A node verdict (some items failed). Runs no longer end with it; older runs may still carry it.'],
            ['SKIPPED', 'Yes', 'Finished with the relevant path skipped.'],
            ['FAILED', 'Yes', 'Finished with a failure.'],
            ['CANCELLED', 'Yes', 'Stopped permanently by a user or an agent.'],
            ['TIMEOUT', 'Yes', 'Ended on a time limit. Counted as a failure.'],
          ]}
        />
        <p>
          Only <code>COMPLETED</code> counts as a success, and only <code>FAILED</code>,{' '}
          <code>CANCELLED</code>, and <code>TIMEOUT</code> count as a failure.{' '}
          <code>PARTIAL_SUCCESS</code> and <code>SKIPPED</code> are terminal but count as neither.
        </p>
        <Callout variant="info">
          The run status <code>TIMEOUT</code> is not the same thing as an agent&apos;s inactivity
          stop. An agent that goes silent past its watchdog window stops with its own reason, shown
          as &ldquo;Stopped (inactivity)&rdquo;. See <a href="/agents">Agents</a> for stop reasons.
        </Callout>

        <h2>Epochs and spawns</h2>
        <p>Two different things can make part of a run happen again, and they are tracked separately:</p>
        <ul>
          <li>
            <strong>Epoch:</strong> a new trigger fire. Epochs start at 0 (before the first fire)
            and go up by one on each fire. Each epoch&apos;s results stay browsable on their own.
          </li>
          <li>
            <strong>Spawn:</strong> a re-execution <em>within</em> an epoch, produced by re-running a
            step (see below). Re-running raises the spawn, never the epoch.
          </li>
        </ul>
        <p>
          Node counts shown for a whole run are <strong>cumulative</strong> across every epoch and
          every spawn: re-running a step never lowers what already happened.
        </p>

        <h2>Execution modes</h2>
        <DocsTable
          caption="Execution modes"
          rowHeaders
          head={['Mode', 'How it runs']}
          rows={[
            ['Automatic (default)', 'Every ready node executes as soon as its predecessors are resolved.'],
            ['Step-by-step', 'The run pauses after each node. You advance every node by hand, including control nodes like Decision.'],
          ]}
        />
        <p>
          In Step-by-step mode, triggers are never executed for you: they still fire on their own.
          After a re-run in Step-by-step mode, the run goes back to <code>PAUSED</code> if non-trigger
          nodes are still ready, or to <code>WAITING_TRIGGER</code> if only triggers remain. For how
          nodes, ports, and branching behave, see <a href="/workflows">Workflows</a>.
        </p>

        <h2>Run controls</h2>
        <p>
          Five actions control a run&apos;s lifecycle. They act on runs in your current workspace:
          in an organization, members can act on the organization&apos;s runs.
        </p>
        <DocsTable
          caption="Run control actions"
          rowHeaders
          head={['Action', 'What it does', 'Works from', 'Resulting status']}
          rows={[
            [
              'Pause',
              'Stops execution where it stands and suspends the workflow’s schedules.',
              'An active run',
              'PAUSED',
            ],
            [
              'Resume',
              'Continues from where the run paused. Completes the run if every step has already settled.',
              'PAUSED only',
              'RUNNING, then a terminal status once finished',
            ],
            [
              'Stop',
              'A graceful stop: closes the running epoch, cancels its pending signals, and returns the run to idle. Triggers stay armed.',
              'RUNNING, PAUSED',
              'WAITING_TRIGGER',
            ],
            [
              'Cancel',
              'A hard, terminal stop: cancels active signals, closes epochs for good, and suspends the workflow’s schedules.',
              'RUNNING, PAUSED, WAITING_TRIGGER, AWAITING_SIGNAL',
              'CANCELLED',
            ],
            [
              'Reactivate',
              'Revives a finished run so its triggers can fire again, and re-enables the schedules a cancel suspended (while the workflow is pinned).',
              'Any terminal status',
              'WAITING_TRIGGER',
            ],
          ]}
        />
        <ul>
          <li>
            <strong>Stop</strong> is refused on a run that is <code>PENDING</code>,{' '}
            <code>WAITING_TRIGGER</code>, or <code>AWAITING_SIGNAL</code>. On a run that already
            finished it only cleans up, with no status change and no error.
          </li>
          <li>
            <strong>Cancel</strong> is the one action that reaches a run parked on a signal. Cancelling
            a run that is already cancelled does nothing.
          </li>
          <li>
            <strong>Reactivate</strong> works from every terminal status, including a failed or
            timed-out run.
          </li>
        </ul>
        <Callout variant="warn" title="Viewer role">
          In an organization, members with the <strong>Viewer</strong> role cannot Cancel or
          Reactivate a run. See <a href="/organizations">Organizations &amp; roles</a>.
        </Callout>
        <p>
          A Stop or Cancel records who stopped the run and why. An agent can stop a run too: a
          graceful stop only closes the running epoch, while a cancel is terminal and suspends the
          schedules, exactly like the buttons.
        </p>

        <h2>Signals and pauses</h2>
        <p>
          A node can hold a run by yielding a <strong>signal</strong>. There are six signal types:
        </p>
        <DocsTable
          caption="Signal types"
          rowHeaders
          head={['Signal', 'Holds the run?', 'Resolved by']}
          rows={[
            ['WAIT_TIMER', 'Yes', 'Time passing (a Wait node’s duration expiring). See the note below.'],
            ['USER_APPROVAL', 'Yes', 'A person approving or rejecting, or the approval timing out.'],
            ['WEBHOOK_WAIT', 'Yes', 'An external HTTP callback arriving.'],
            ['INTERFACE_SIGNAL', 'Only when its action is set to advance the run', 'A user action on an interface page. Otherwise the page displays without holding anything.'],
            ['AGENT_EXECUTION', 'Yes (internal)', 'An agent step running in the background finishing on its own. Not a user-facing pause.'],
            ['BROWSER_USER_TAKEOVER', 'Yes', 'A person taking over a live browser session.'],
          ]}
        />
        <Callout variant="info">
          A Wait of 3 seconds or less runs inline and creates no signal. Only longer durations park
          the run on a <code>WAIT_TIMER</code> signal.
        </Callout>

        <h2>Resolving approvals</h2>
        <p>
          A <code>USER_APPROVAL</code> signal is resolved one at a time or in bulk. When the node
          has a context template, the approver sees the rendered context next to the request.
        </p>
        <DocsTable
          caption="Approval actions"
          rowHeaders
          head={['Action', 'What it does']}
          rows={[
            [
              'Resolve one',
              'Sets one pending approval to approved, rejected, timeout, or cancelled, with an optional comment and extra data. When a node has several pending signals (for example one per item inside a Split), you can target an exact item; otherwise the latest epoch’s signal is resolved, so you never resolve a stale one by accident.',
            ],
            [
              'Resolve all',
              'Resolves every pending approval of a node at once with the resolution you choose (approved or rejected), either within one epoch or across every epoch.',
            ],
            ['Cancel', 'Cancels every pending signal on that node across the whole run.'],
          ]}
        />
        <p>
          Approvals can also be answered from a linked chat channel (Slack, Microsoft Teams,
          Discord, Telegram, or WhatsApp). See <a href="/channels">Chat channels</a>.
        </p>

        <h2>Re-running a step</h2>
        <p>
          You can re-run a step that <strong>completed, failed, is awaiting a signal, or is
          ready</strong>, optionally editing its configuration first. On the canvas, use{' '}
          <strong>Re-run from this step</strong> (Step-by-step) or{' '}
          <strong>Restart from this step (the rest runs again automatically)</strong> (Automatic).
          Re-running:
        </p>
        <ul>
          <li>Raises the epoch&apos;s <strong>spawn</strong>, not the epoch itself.</li>
          <li>Resets that step and everything downstream of it, then makes them ready again.</li>
          <li>
            In Automatic mode, lets the ready steps continue on their own; in Step-by-step mode,
            returns the run to <code>PAUSED</code> (or <code>WAITING_TRIGGER</code> if the epoch is
            done) for you to advance.
          </li>
        </ul>
        <h3>Re-running an older epoch</h3>
        <p>
          By default the most recent epoch is replayed. When an older epoch is on screen in the
          canvas, the re-run replays <strong>that</strong> epoch instead, and its new spawn starts
          above that epoch&apos;s own highest spawn. Replaying a chosen epoch is not available on a
          Step-by-step run, and it is refused while other epochs of the same trigger are still
          executing.
        </p>
        <h3>When a re-run is refused</h3>
        <ul>
          <li>The step is still executing on an Automatic run. Wait for it to settle, or stop the run.</li>
          <li>
            The run was <code>CANCELLED</code> or ended <code>TIMEOUT</code>. Fire the workflow again
            instead.
          </li>
          <li>
            The edit changes the graph&apos;s topology (adding or removing nodes, rewiring edges).
            Edits at re-run time are limited to parameters, prompts, and configuration.
          </li>
          <li>The step&apos;s branch was not taken in the chosen epoch, so there is nothing to redo.</li>
        </ul>
        <p>
          Every attempt at a step is kept, with its epoch, status, start and end time, and error,
          so you can compare re-runs.
        </p>

        <h2>The run panel</h2>
        <p>
          A workflow&apos;s runs are listed in a paged history, most recent first. The pinned
          production run is always pulled to the top. Each row shows:
        </p>
        <ul>
          <li>the status, as an icon and a label;</li>
          <li>the version it ran, with a pin marker on every run of the pinned version;</li>
          <li>the current epoch (calendar icon), once the run has fired at least once;</li>
          <li>a Step-by-step marker, and a flask marker for a <strong>Mock run</strong> (a dry run with mocked integrations);</li>
          <li>when it last fired, as a relative time;</li>
          <li>
            how long its <strong>last execution</strong> took, measured from the first node start
            to the last node end of the latest epoch, so the idle time between fires is not counted.
          </li>
        </ul>
        <p>Hover a row to see its run id, creation time, and last fire time.</p>
        <DocsTable
          caption="Status colors in the run history"
          rowHeaders
          head={['Status', 'Color']}
          rows={[
            ['Completed', 'Emerald'],
            ['Running', 'Blue, with a pulse'],
            ['Failed', 'Red'],
            ['Waiting for trigger, Paused, Pending, Partial Success', 'Amber'],
            ['Awaiting signal', 'Violet'],
            ['Timeout', 'Orange'],
            ['Cancelled, Stopped, Skipped', 'Gray'],
          ]}
        />
        <h3>Last-cycle result</h3>
        <p>
          Between fires, a reusable run sits at <code>WAITING_TRIGGER</code>, but the history shows
          something more useful: the outcome of its <em>last</em> cycle.
        </p>
        <DocsTable
          caption="How the last-cycle result is derived"
          rowHeaders
          head={['The last epoch had', 'The row shows']}
          rows={[
            ['A failed step (even with other steps completed)', 'failed'],
            ['Every step completed', 'completed'],
            ['No steps that cycle', 'the idle status'],
          ]}
        />
        <p>Cancelling a run clears this result, so a cancelled run always shows Cancelled.</p>

        <h3>Steps, timeline, and cost</h3>
        <p>
          Opening a run shows its graph in run mode, each node colored by its status, with an epoch
          selector: view one past epoch frozen in time, or switch back to all epochs. The steps
          panel lists every step and switches between <strong>List view</strong> and{' '}
          <strong>Timeline view</strong>, which draws one bar per step sized by how long it took. Its
          status filter keeps All, completed, failed, running, awaiting signal, or skipped steps.
        </p>
        <p>
          With no epoch selected, the counts for finished steps (completed, failed, skipped) are
          cumulative, so a re-run never makes them go backwards, while running and awaiting-signal
          counts stay live.
        </p>
        <p>
          The panel also shows the <strong>Cost of this run</strong>, for the selected epoch or
          across every epoch. When the workflow has a spending cap, a gauge shows the spend of the
          current period. Once the cap is reached, the run shows{' '}
          <strong>Budget reached, no new run will start</strong>: the run stays in{' '}
          <code>WAITING_TRIGGER</code> and opens no new epoch until the allowance starts again (or
          until you raise the cap, for a cap that never resets). Test fires from the builder are
          not counted. See <a href="/billing">Plans &amp; billing</a>.
        </p>
        <Callout variant="info" title="Out of credits">
          When the workspace runs out of credits, the next fire is still recorded: the trigger node
          fails with <code>CREDIT_EXHAUSTED</code> and the nodes after it are skipped. The run stays
          reusable, so fires work again once you top up.
        </Callout>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common run problems and what to check"
          rowHeaders
          head={['Symptom', 'What to check']}
          rows={[
            ['Stop is refused', 'The run is idle or waiting on a signal. Use Cancel to end a run parked on a signal.'],
            ['Schedules stopped after I cancelled a run', 'Cancel suspends schedules. Reactivate the run to re-enable them.'],
            ['Re-run is refused', 'The step is still executing, the run was cancelled or timed out, or the edit changes the topology.'],
            ['The duration looks short for a run that lives for days', 'It is the last execution’s working time, not the run’s lifetime.'],
            ['No new epoch opens', 'Look for a reached spending cap on the run, or CREDIT_EXHAUSTED on the trigger node.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            Every way a run starts, and which version it runs.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Nodes, ports, branching, and parallelism: the graph a run executes.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Models, tools, budgets, and agent stop reasons.
          </Card>
          <Card icon={LayoutPanelLeft} title="Interfaces & apps" href="/interfaces">
            How an interface page can hold a run on a user action.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
