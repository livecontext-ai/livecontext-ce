import { Workflow, Bot, LayoutPanelLeft, Webhook } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Runs & execution',
  description:
    'How a workflow run behaves once it starts: the status lifecycle, epochs and spawns, Automatic vs Step-by-step execution, pause/resume/stop/cancel/reactivate, signals and approvals, re-running a step, and the run panel views.',
  path: '/docs/runs',
});

export default function RunsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Operate"
        title="Runs & execution"
        lead="A run is one execution instance of a workflow. This page covers what happens after you start it: how progress is tracked, the statuses a run can be in, how to pause, stop, cancel or reactivate it, how signals and approvals pause a run, and how to re-run a single step."
      />

      <DocsProse>
        <h2>What a run is</h2>
        <p>
          Every time a workflow executes, it creates a <strong>run</strong>, identified by a
          <code> runId</code> and listed in the workflow&apos;s run history. A run with a reusable
          trigger (schedule, webhook, chat, table) can live for a long time: each time the trigger
          fires, it starts a new <strong>epoch</strong> within the same run and re-executes the
          graph, so one run can accumulate many epochs over its lifetime. The run&apos;s start time
          reflects when it was first created; each epoch also has its own start time, the most
          recent of which is the run&apos;s last-fired time.
        </p>
        <p>
          Internally, a run tracks its progress per trigger: which epoch it&apos;s on, which nodes
          in the current epoch are completed, failed, skipped, running, ready, or waiting on a
          signal. That state is what every view described below is reading from.
        </p>

        <h2>Run statuses (11)</h2>
        <p>
          A run is always in exactly one of 11 statuses: 5 non-terminal (still active or idle) and
          6 terminal (finished).
        </p>
        <DocsTable
          head={['Status', 'Terminal?', 'Meaning']}
          rows={[
            ['PENDING', 'No', 'Created, not started yet.'],
            ['RUNNING', 'No', 'Actively executing.'],
            ['PAUSED', 'No', 'Paused by a user. The only status that can be resumed.'],
            ['AWAITING_SIGNAL', 'No', 'A node is blocked, waiting on a signal (see below).'],
            ['WAITING_TRIGGER', 'No', 'Idle between trigger fires, for a reusable trigger.'],
            ['COMPLETED', 'Yes', 'Finished successfully. The only status counted as a success.'],
            ['PARTIAL_SUCCESS', 'Yes', 'Finished with a mix of completed and failed steps.'],
            ['SKIPPED', 'Yes', 'Finished with the relevant path skipped.'],
            ['FAILED', 'Yes', 'Finished with a failure.'],
            ['CANCELLED', 'Yes', 'Stopped permanently by a user.'],
            ['TIMEOUT', 'Yes', 'Finished because a run-level time limit was hit.'],
          ]}
        />
        <p>
          Two finer points worth knowing: only <code>COMPLETED</code> counts as a success, and only{' '}
          <code>FAILED</code>, <code>CANCELLED</code>, and <code>TIMEOUT</code> count as a failure.{' '}
          <code>PARTIAL_SUCCESS</code> and <code>SKIPPED</code> are terminal but count as neither.
        </p>
        <Callout variant="warn">
          Don&apos;t confuse the run-level <code>TIMEOUT</code> status with an agent&apos;s{' '}
          <em>inactivity</em> stop reason. The run status <code>TIMEOUT</code> means the run was
          actively working but ran over its overall time budget; an agent that goes silent past its
          watchdog window instead stops with a distinct, agent-level reason shown as
          &ldquo;Stopped (inactivity)&rdquo;. See <a href="/agents">Agents</a> for stop reasons.
        </Callout>

        <h2>Epochs vs spawns</h2>
        <p>
          Two different things can make part of a run happen again, and they&apos;re tracked
          separately:
        </p>
        <ul>
          <li>
            <strong>Epoch:</strong> a new trigger fire. Epochs start at 0 (before the trigger has
            fired) and increment by one each time the trigger fires again. Each epoch&apos;s results
            accumulate and stay browsable on their own.
          </li>
          <li>
            <strong>Spawn:</strong> a re-execution <em>within the same epoch</em>, produced by
            re-running a single step (see below). Re-running increments the spawn, never the epoch.
          </li>
        </ul>
        <p>
          Node counts shown on a run (its badges, its whole-run status counts) are{' '}
          <strong>cumulative</strong> across every epoch and every spawn: re-running a step never
          decrements what already happened, it only adds to the total.
        </p>

        <h2>Execution modes</h2>
        <DocsTable
          head={['Mode', 'How it runs']}
          rows={[
            [
              'Automatic (default)',
              'Every ready node executes as soon as its predecessors are resolved, following the graph on its own.',
            ],
            [
              'Step-by-step',
              'The run pauses after each node. You manually advance every node one at a time, including control nodes like Decision.',
            ],
          ]}
        />
        <p>
          In Step-by-step mode, trigger nodes are never auto-executed either way: they still have to
          fire on their own. After a re-run in Step-by-step, the run goes back to{' '}
          <code>PAUSED</code> if non-trigger nodes are still ready to run, or to{' '}
          <code>WAITING_TRIGGER</code> if only triggers remain (the epoch is effectively done). The
          run history list marks a Step-by-step run with its own badge so you can tell the two modes
          apart at a glance. For how nodes, ports, and branching actually behave, see{' '}
          <a href="/workflows">Workflows</a>.
        </p>

        <h2>Run controls</h2>
        <p>Five actions control a run&apos;s lifecycle. Each is scoped to your workspace: you can only act on runs you own.</p>
        <DocsTable
          head={['Action', 'What it does', 'Works from', 'Resulting status']}
          rows={[
            [
              'Pause',
              'Stops execution where it stands and suspends the schedule so it won’t tick further.',
              'An active run (not rejected from other states)',
              'PAUSED',
            ],
            [
              'Resume',
              'Picks back up from where it paused; completes the run if every step is already terminal.',
              'PAUSED only',
              'RUNNING (then a terminal status once finished)',
            ],
            [
              'Stop',
              'A graceful, non-terminal stop: closes the active epoch, cancels any pending signals, and returns the run to idle so future trigger fires still work.',
              'RUNNING, PAUSED',
              'WAITING_TRIGGER',
            ],
            [
              'Cancel',
              'A hard, terminal stop: cancels active signals and closes epochs for good.',
              'RUNNING, PAUSED, WAITING_TRIGGER, AWAITING_SIGNAL',
              'CANCELLED',
            ],
            [
              'Reactivate',
              'Revives a finished run so its triggers can fire again.',
              'Any terminal status',
              'WAITING_TRIGGER',
            ],
          ]}
        />
        <p>
          Calling Stop on a run that isn&apos;t RUNNING or PAUSED (for example one that&apos;s idle
          or already finished) is refused; calling it on a run that already finished is a harmless
          no-op. Cancel is the one action that can reach a run parked on a signal (
          <code>AWAITING_SIGNAL</code>), and Reactivate is the one action that works from every
          terminal status, including a crashed or timed-out run.
        </p>

        <h2>The run panel</h2>
        <p>
          A workflow&apos;s runs are listed in a paged history (most recent first), each row showing
          its status badge, a Step-by-step indicator when applicable, the workflow version it ran
          with (with a pin marker on the version currently pinned as &ldquo;production&rdquo;), its
          current epoch, how many nodes it touched, when it ran, its <code>runId</code>, and its
          duration. The pinned production run is always pulled to the top of the list.
        </p>
        <p>
          Opening a run shows its graph in run mode, with each node colored by its live status, plus
          Stop / Cancel / Reactivate actions and an epoch selector.
        </p>
        <h3>List vs gauge view</h3>
        <p>
          The run-info panel lists every step and lets you switch between a plain <strong>list</strong>{' '}
          and a <strong>gauge</strong> view: the gauge draws one horizontal bar per step, its width
          proportional to how long that step actually took, so at a glance you can see which steps
          dominated the run&apos;s total time.
        </p>
        <h3>Step outputs table</h3>
        <p>
          The step outputs table has a client-side search box plus two server-side filters that
          narrow the whole result set: a status filter and an epoch selector.
        </p>
        <DocsTable
          head={['Filter', 'Options']}
          rows={[
            [
              'Status',
              'All statuses, completed, failed, running, pending, skipped, cancelled, timeout, partial_success.',
            ],
            [
              'Epoch',
              'All epochs, or a specific epoch, listed most-recent-first. Disabled when the run has no epochs yet.',
            ],
          ]}
        />
        <p>
          The whole-run (no-epoch-selected) view shows <strong>cumulative</strong> counts for
          finished buckets (completed / failed / skipped) so a re-run never makes the totals go
          backwards, while still showing the current transient buckets (running / awaiting signal)
          live. A run&apos;s total duration combines the time of every closed epoch plus the elapsed
          time of the epoch still in progress, if any.
        </p>
        <p>
          The canvas has its own epoch selector: view a specific past epoch frozen in time, or switch
          back to &ldquo;live&rdquo; / &ldquo;all epochs&rdquo; to see the graph as it looks right
          now.
        </p>

        <h2>Signals & pauses</h2>
        <p>
          A node can pause a run by yielding a <strong>signal</strong>. There are six signal types;
          five of them hold the run in <code>AWAITING_SIGNAL</code> until they&apos;re resolved, and
          one only holds it conditionally:
        </p>
        <DocsTable
          head={['Signal', 'Pauses the run?', 'Resolved by']}
          rows={[
            [
              'WAIT_TIMER',
              'Yes',
              'Time passing (a Wait node’s duration expiring). See the inline threshold below.',
            ],
            ['USER_APPROVAL', 'Yes', 'A person approving, rejecting, or the approval timing out.'],
            ['WEBHOOK_WAIT', 'Yes', 'An external HTTP callback arriving.'],
            [
              'INTERFACE_SIGNAL',
              'Only when its action is set to advance the run',
              'A user action on an interface page. Otherwise the interface simply displays without pausing anything.',
            ],
            [
              'AGENT_EXECUTION',
              'Yes (internal)',
              'An agent step offloaded to an internal queue completing on its own. Not a user-facing pause.',
            ],
            ['BROWSER_USER_TAKEOVER', 'Yes', 'A person taking over a live browser session.'],
          ]}
        />
        <Callout variant="info">
          A Wait node with a very short duration (3 seconds or less) simply runs inline and never
          creates a signal at all; only durations longer than that park the run on a{' '}
          <code>WAIT_TIMER</code> signal.
        </Callout>

        <h2>Resolving approvals</h2>
        <p>
          A <code>USER_APPROVAL</code> signal is resolved one at a time or in bulk:
        </p>
        <DocsTable
          head={['Action', 'What it does']}
          rows={[
            [
              'Resolve one',
              'Sets a single pending approval to approved, rejected, timeout, or cancelled, with an optional comment and extra data. If the same node has more than one pending signal (for example one per item inside a Split), you can target an exact item; otherwise the latest epoch’s signal is the one resolved, so you never accidentally resolve a stale one.',
            ],
            [
              'Resolve all',
              'Approves every pending approval for a node at once, either scoped to one epoch (useful when a Split created one approval per item) or across every epoch.',
            ],
            [
              'Cancel',
              'Cancels every pending signal on a node across the whole run.',
            ],
          ]}
        />

        <h2>Re-running a step</h2>
        <p>
          Any completed step can be re-run on its own, optionally applying edits to its
          configuration first. Re-running:
        </p>
        <ul>
          <li>Increments the current epoch&apos;s <strong>spawn</strong>, not the epoch itself.</li>
          <li>Resets that step and everything downstream of it, then makes them ready again.</li>
          <li>
            In Automatic mode, lets ready non-trigger steps continue on their own (up to a safety
            cap); in Step-by-step mode, returns the run to <code>PAUSED</code> (or{' '}
            <code>WAITING_TRIGGER</code> if the epoch is now done) for manual advancement.
          </li>
        </ul>
        <p>
          Re-running a step that&apos;s still in progress is refused. Edits applied at re-run time
          are limited to parameters, prompts, and configuration: changing the graph&apos;s topology
          (adding or removing nodes, rewiring edges) is rejected, and edits are refused entirely on a
          pinned workflow for a run you don&apos;t have editor access to.
        </p>
        <h3>Step attempt history</h3>
        <p>Every attempt at a step is kept, so you can compare what changed between re-runs:</p>
        <DocsTable
          head={['Field', 'What it shows']}
          rows={[
            ['epoch', 'Which epoch the attempt belongs to.'],
            ['status', 'How that attempt ended.'],
            ['startTime / endTime', 'When the attempt ran.'],
            ['errorMessage', 'The error, if the attempt failed.'],
            ['outputStorageId', 'A reference to that attempt’s stored output.'],
          ]}
        />

        <h2>Reusable-trigger badge</h2>
        <p>
          Between fires, a reusable-trigger run technically sits at <code>WAITING_TRIGGER</code>, but
          the run history shows something more useful: the outcome of its <em>last</em> cycle,
          derived from that epoch&apos;s steps.
        </p>
        <DocsTable
          head={['Last epoch had...', 'Badge shows']}
          rows={[
            ['A failed step and a real completed step', 'partial_success'],
            ['Every step failed', 'failed'],
            ['Every step completed', 'completed'],
            ['No steps ran that cycle', 'the raw idle status'],
          ]}
        />
        <p>Badge colors follow the same idea across the run panel:</p>
        <DocsTable
          head={['Status shown', 'Color']}
          rows={[
            ['COMPLETED', 'Emerald'],
            ['RUNNING', 'Blue'],
            ['FAILED', 'Red'],
            ['PARTIAL_SUCCESS', 'Amber'],
            ['CANCELLED', 'Gray'],
            ['Waiting / idle (default)', 'Yellow'],
          ]}
        />
        <p>
          Cancelling a run explicitly clears this last-cycle badge, so a run you cancelled always
          shows CANCELLED rather than whatever its last cycle happened to be.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Nodes, ports, branching, and parallelism, the graph a run executes.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Models, tools, budgets, and the full set of agent stop reasons.
          </Card>
          <Card icon={LayoutPanelLeft} title="Interfaces" href="/interfaces">
            How an interface page can pause a run on a user action.
          </Card>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            Every way a run can start, and what makes a trigger reusable.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
