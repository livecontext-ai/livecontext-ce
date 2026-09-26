import { Bot, Bell, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Tasks & board',
  description:
    'Track work on the Board: create tasks, assign them to agents or teammates, review results, manage columns, and see your workflows and applications as kanban boards.',
  path: '/docs/board',
});

export default function BoardPage() {
  return (
    <>
      <DocsHero
        eyebrow="Work & collaborate"
        title="Tasks & board"
        lead="The Board is where tracked work lives: tasks you or your agents create, who they are assigned to, and where each one stands. It also shows your workflows and applications as kanban boards."
      />

      <DocsProse>
        <h2>Open the board</h2>
        <p>
          <strong>Board</strong> starts hidden in the sidebar: open it from the sidebar&apos;s{' '}
          <strong>More</strong> menu, or tick it in <strong>Customize</strong> to keep it in the
          sidebar (see <a href="/workspace">Tour of the workspace</a>). A task notification in the
          bell also opens it.
        </p>
        <p>A toggle at the top switches between three boards:</p>
        <DocsTable
          caption="The three boards and what each one shows"
          head={['Board', 'Shows']}
          rows={[
            [<strong key="b">Tasks</strong>, 'Every task in the active workspace, one column per status.'],
            [<strong key="b">Applications</strong>, 'Your published and installed applications, by production state.'],
            [<strong key="b">Workflows</strong>, 'Your workflows, by production state.'],
          ]}
        />
        <p>The board remembers the last one you used.</p>
        <Callout title="Role required">
          A workspace <strong>Viewer</strong> sees the boards read-only: no new tasks, no dragging
          cards, no bulk actions, and no column management.
        </Callout>

        <h2>What a task is</h2>
        <p>
          A task is a unit of work with a title, <strong>Instructions</strong>, a{' '}
          <strong>Priority</strong> (<strong>Low</strong>, <strong>Normal</strong>,{' '}
          <strong>High</strong>, <strong>Urgent</strong>), an optional assignee, and an optional
          reviewer. When the work is done it carries a <strong>Result</strong>. Tasks can have
          sub-tasks. You create tasks here; agents create them too, when they delegate work (see{' '}
          <a href="/agents#task-delegation">Task delegation</a>).
        </p>

        <h2>Create a task</h2>
        <Steps>
          <Step n={1} title="Start a new task">
            Click <strong>New Task</strong> in the toolbar, or the add button at the bottom of the{' '}
            <strong>Pending</strong> column.
          </Step>
          <Step n={2} title="Fill in the Task tab">
            Give it a title, <strong>Instructions</strong> and a <strong>Priority</strong>, then pick
            an assignee under <strong>Assign</strong>. The list has two groups,{' '}
            <strong>Agents</strong> and <strong>People</strong>. Leave it on{' '}
            <strong>Unassigned</strong> if nobody owns it yet.
          </Step>
          <Step n={3} title="Optionally set a reviewer">
            On the <strong>Review</strong> tab, choose a <strong>Reviewer</strong>: an agent or a
            teammate who checks the result. With a reviewer agent you can also set{' '}
            <strong>Max review attempts</strong> (1 to 20, default 3).
          </Step>
          <Step n={4} title="Create it">
            The task appears in <strong>Pending</strong>. The assignee and reviewer are notified in
            their bell (see <a href="/notifications">Notifications</a>).
          </Step>
        </Steps>

        <h2>Agents or people</h2>
        <p>The assignee decides what happens when the task starts:</p>
        <ul>
          <li>
            <strong>An agent</strong> does the work. Moving the task to <strong>In Progress</strong>{' '}
            shows <strong>Execute Agent</strong>; while it runs you can <strong>Stop Agent</strong>.
            When it finishes, the task moves to <strong>In Review</strong>, never straight to{' '}
            <strong>Completed</strong>.
          </li>
          <li>
            <strong>A teammate</strong> is not run automatically: the task is theirs to move, like a
            card on any board. Moving it to <strong>In Progress</strong> shows{' '}
            <strong>Start Task</strong>.
          </li>
        </ul>

        <h3>Reviewing a result</h3>
        <p>
          A task <strong>In Review</strong> waits for a decision. A reviewer agent reviews it on its
          own. Otherwise you decide from the task: <strong>Approve</strong> completes it,{' '}
          <strong>Request Changes</strong> sends it back to <strong>In Progress</strong>. You can decide even when a reviewer agent is set. After{' '}
          <strong>Max review attempts</strong> rejections by a reviewer agent, the task is failed, not
          approved.
        </p>
        <Callout variant="tip">
          A task with no reviewer that rests in <strong>In Review</strong> is not stuck: it is
          waiting for you. The bell tells the person who has to decide.
        </Callout>

        <h2>Statuses and columns</h2>
        <p>The task board has seven built-in columns:</p>
        <DocsTable
          caption="Built-in task board columns"
          head={['Column', 'Meaning']}
          rows={[
            [<strong key="c">Pending</strong>, 'Created, not started yet.'],
            [<strong key="c">In Progress</strong>, 'Being worked on by its agent or teammate.'],
            [<strong key="c">In Review</strong>, 'Work submitted, waiting for approval.'],
            [<strong key="c">Completed</strong>, 'Approved and done.'],
            [<strong key="c">Failed</strong>, 'Could not be done, or rejected too many times. Hidden by default.'],
            [<strong key="c">Cancelled</strong>, 'Stopped. Cancelling also cancels its sub-tasks. Hidden by default.'],
            [<strong key="c">Deleted</strong>, 'In the trash, removed for good 30 days after deletion. Hidden by default.'],
          ]}
        />
        <p>
          Show or hide columns from the <strong>Columns</strong> menu. Drag a card to another column
          to change its status. A move that needs confirmation opens it first: dropping on{' '}
          <strong>In Progress</strong> opens the task so you can start it, a task needs an assignee
          before it can go to <strong>In Review</strong>, and dropping on <strong>Cancelled</strong> or{' '}
          <strong>Deleted</strong> asks you to confirm. Dragging a card out of{' '}
          <strong>Deleted</strong> restores it.
        </p>

        <h3>Custom columns and WIP limits</h3>
        <p>
          <strong>Manage columns</strong> (at the bottom of the <strong>Columns</strong> menu) lets
          you add your own columns. Each one belongs to a <strong>Category</strong> (
          <strong>Pending</strong>, <strong>In progress</strong>, <strong>In review</strong>,{' '}
          <strong>Done</strong>, <strong>Failed</strong>, <strong>Cancelled</strong>, <strong>Deleted</strong>) that decides how
          its tasks behave, and can have a <strong>WIP</strong> limit. You can also reorder, hide, and
          delete columns there. Deleting a column moves its tasks to the default column of the same
          category.
        </p>

        <h2>Task details</h2>
        <p>Click a card to open it. The detail view has these tabs:</p>
        <DocsTable
          caption="Tabs of the task detail view"
          head={['Tab', 'Contents']}
          rows={[
            [<strong key="t">Instructions</strong>, 'What to do, and the result once there is one.'],
            [<strong key="t">Notes</strong>, 'Comments from people and agents. Add one with Add Note.'],
            [<strong key="t">Executions</strong>, 'Each agent run on the task, with its conversation, tool calls, tokens, and model.'],
            [<strong key="t">Activity</strong>, 'The history of status changes and assignments.'],
          ]}
        />
        <p>
          Under <strong>Advanced</strong> you can add <strong>Labels</strong>, an{' '}
          <strong>Estimate (min)</strong>, <strong>Time spent (min)</strong>, a{' '}
          <strong>Checklist</strong>, and <strong>Blocked by</strong> links to other tasks. Cards show
          these at a glance: due date, estimate, a blocked marker, checklist progress, and the number
          of attachments. Changes are staged: nothing is saved until you click the action button (
          <strong>Save Changes</strong>, <strong>Mark Complete</strong>, and so on), and{' '}
          <strong>Discard</strong> drops them.
        </p>

        <h2>Find and sort tasks</h2>
        <ul>
          <li>Sort by <strong>Priority</strong>, <strong>Last updated</strong>, <strong>Created</strong>, or <strong>Due date</strong>.</li>
          <li>Filter by agent (<strong>All agents</strong>) and by label (<strong>All labels</strong>).</li>
          <li><strong>Filters</strong> adds <strong>My tasks</strong> and <strong>Blocked</strong>.</li>
          <li><strong>Search tasks...</strong> matches task text.</li>
        </ul>
        <p>The board updates live as agents and teammates move tasks.</p>

        <h2>Bulk actions</h2>
        <p>
          Select several cards in the same column to act on them together: <strong>Cancel</strong>,{' '}
          <strong>Delete</strong> (moves them to <strong>Deleted</strong>), and, in the Deleted column,{' '}
          <strong>Restore</strong> or <strong>Delete permanently</strong>. Permanent deletion cannot
          be undone.
        </p>

        <h2>Workflow and application boards</h2>
        <p>
          The <strong>Workflows</strong> and <strong>Applications</strong> boards sort each item into
          four columns:
        </p>
        <DocsTable
          caption="Workflow and application board columns, and what dragging a card does"
          head={['Column', 'Meaning', 'Drag to change']}
          rows={[
            [<strong key="c">Draft</strong>, 'No version is pinned to production.', 'Drag to Production to pick a version to pin. Only versions that have run at least once can be pinned.'],
            [<strong key="c">Production</strong>, 'A version is pinned and its production run is live.', 'Drag to Draft to unpin, or to Paused to stop the production run.'],
            [<strong key="c">Needs Review</strong>, 'The production run waits on a user approval.', 'You cannot drop cards here; resolve the approval with Review approvals on the card.'],
            [<strong key="c">Paused</strong>, 'The production run was stopped.', 'Drag back to Production to reactivate it.'],
          ]}
        />
        <p>
          You can filter these boards by trigger type and by last modification, search them, and sort
          by <strong>Last executed</strong>, <strong>Name</strong>, <strong>Run count</strong>, or{' '}
          <strong>Last modified</strong>. Pinning is explained in{' '}
          <a href="/triggers">Triggers</a>.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common task board problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'There is no New Task button and cards cannot be dragged',
              'Your role in this workspace is Viewer, which is read-only on the boards.',
              'Ask an owner or admin of the workspace for the Member role or higher.',
            ],
            [
              'Dropping a card on In Review opens the task instead of moving it',
              'The task has no assignee, and a task needs one before it can go to In Review.',
              'Pick an agent or a person under Assign, then move it again.',
            ],
            [
              'Dropping a card on In Progress opens the task and the card stays where it was',
              'The move waits for you to confirm it from the task.',
              'Start it from the task: Execute Agent for an agent, Start Task for a teammate.',
            ],
            [
              'A cancelled, failed, or deleted task has disappeared',
              'The Failed, Cancelled, and Deleted columns are hidden by default.',
              'Show them from the Columns menu.',
            ],
            [
              'A column count turns red, but tasks still move into it',
              'A WIP limit is a warning, not a block: the count turns red once the column holds more tasks than its limit.',
              'Move tasks out of the column, or raise its WIP in Manage columns.',
            ],
            [
              'Dragging a workflow to Production shows “No version available”',
              'The board lists only versions that have already run at least once. No version of this workflow has run yet.',
              'Run the workflow once, then drag it to Production again. Or open the workflow and use Set as production in its version history, which does not need a prior run.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents#task-delegation">
            How agents create, claim, and review tasks.
          </Card>
          <Card icon={Bell} title="Notifications" href="/notifications">
            Task assignments and reviews in the bell, by email, or in a chat app.
          </Card>
          <Card icon={Workflow} title="Runs & execution" href="/runs">
            What happens when a production workflow runs.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
