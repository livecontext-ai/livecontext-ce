import { Webhook, Globe, PlayCircle, Bot } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Agenda',
  description:
    'A calendar of every scheduled workflow, application, and agent in your workspace: see what will run and what ran, run a schedule early, move a run, or suspend it.',
  path: '/docs/agenda',
});

export default function AgendaPage() {
  return (
    <>
      <DocsHero
        eyebrow="Work & collaborate"
        title="Agenda"
        lead="The Agenda is a calendar of everything that runs on its own in your workspace: scheduled workflows, applications, and agents, beside what already ran and how it went. This page explains how to read it and what you can do from it."
      />

      <DocsProse>
        <h2>Overview</h2>
        <p>
          Open <strong>Agenda</strong> from the sidebar. It shows the <strong>production</strong>{' '}
          triggers of the workflows, applications, and agents in your current workspace:
        </p>
        <ul>
          <li>
            <strong>Scheduled</strong> runs are drawn on the calendar at the time they will fire,
            in the time zone you choose.
          </li>
          <li>
            <strong>Past runs</strong> are drawn where they happened, with their outcome: workflow
            and application fires, and agent runs (with how each was launched, for example by a
            schedule, a webhook, a chat, a task, or another agent).
          </li>
          <li>
            Triggers <strong>without a date</strong> (webhook, chat, form, table, workflow chain,
            manual, error) are not on the grid, but you can find them with the trigger search.
          </li>
        </ul>
        <Callout variant="info" title="Production only">
          A workflow&apos;s schedule is armed, and appears here, only while the workflow has a
          pinned production version. A workflow you are still editing does not show up. See{' '}
          <a href="/triggers">Triggers</a> for pinning.
        </Callout>

        <h2>Read the calendar</h2>
        <p>
          Switch between <strong>Month</strong>, <strong>Week</strong>, <strong>Day</strong>, and{' '}
          <strong>List</strong>. Use the arrows to move one period at a time, <strong>Today</strong>{' '}
          to come back, or click the period title to jump to any date. In the Week and Day views, a
          line marks the current time.
        </p>
        <DocsTable
          caption="Statuses shown on calendar entries"
          rowHeaders
          head={['Label', 'Meaning']}
          rows={[
            ['Scheduled', 'A future run.'],
            ['Running', 'A fire in progress.'],
            ['Completed', 'A past run that finished well.'],
            ['Failed', 'A past run with a failure.'],
            ['Stopped', 'A run somebody stopped.'],
            ['Triggered', 'A past fire whose final outcome is not known.'],
            ['Will not run: spending cap reached', 'A future run that the workflow’s spending cap will hold back.'],
          ]}
        />
        <p>
          An entry marked <strong>Moved off its schedule</strong> is a run you moved to another
          time. When a day holds more entries than fit, use <strong>+N more</strong> to expand it.
        </p>
        <Callout variant="info">
          An agent run appears once it has <strong>finished</strong>. While a long agent run is in
          progress, its slot looks empty.
        </Callout>
        <h3>Filter and display settings</h3>
        <p>
          <strong>Resource types</strong> (Workflows, Applications, Agents) and{' '}
          <strong>Launch kinds</strong> (Schedule, Webhook, Chat, Table, and so on) narrow what the
          calendar shows. <strong>Display settings</strong> let you choose the{' '}
          <strong>Time zone</strong>, which day the <strong>Week starts on</strong>, the{' '}
          <strong>Hours shown</strong>, and whether to <strong>Show weekends</strong>,{' '}
          <strong>Show past runs</strong>, <strong>Show paused schedules</strong>, and use{' '}
          <strong>Compact rows</strong>. <strong>Reset display settings</strong> restores the
          defaults.
        </p>
        <p>
          Nothing is hidden silently: runs outside the hours shown are gathered above the grid, runs
          on a hidden weekend are counted, and a schedule that fires too often to draw in full asks
          you to switch to the Day view.
        </p>
        <h3>Find a trigger</h3>
        <p>
          Use <strong>Search production triggers</strong> to find any production trigger, including
          one without a date. Selecting it highlights that trigger&apos;s runs on the calendar and
          shows how many uses it had. From the result you can open the resource, or disable and
          reactivate it.
        </p>

        <h2>Act on a scheduled run</h2>
        <p>Click an entry to open its menu. A future run offers:</p>
        <DocsTable
          caption="Actions on a scheduled run"
          rowHeaders
          head={['Action', 'What it does']}
          rows={[
            ['Run now', 'Starts the run immediately. The scheduled run still happens as planned. Blocked when the spending cap is reached.'],
            ['Move...', 'Opens the move dialog (see below).'],
            ['Suspend this schedule', 'This schedule triggers no runs until you enable it again.'],
            ['Disable workflow, Disable agent, Disable interface', 'None of the resource’s production triggers run until you reactivate it (Reactivate workflow, Reactivate agent...).'],
            ['Open resource', 'Opens the workflow, application, or agent.'],
          ]}
        />
        <p>
          A past entry opens its menu too: <strong>Open this run</strong> opens the workflow run on
          that exact epoch, and <strong>Open this conversation</strong> opens an agent run&apos;s
          conversation, where its prompt, answer, and tool calls are.
        </p>
        <p>
          To enable a suspended schedule again, turn on <strong>Show paused schedules</strong> to find
          it in the trigger search, then choose <strong>Enable</strong> on its card in the Schedules tab of{' '}
          <a href="/public-access">Public access</a>.
        </p>

        <h2>Move a run</h2>
        <Steps>
          <Step n={1} title="Open the move dialog">
            Drag the entry to another day (or day and hour, in the Week and Day views), or choose{' '}
            <strong>Move...</strong> in its menu. The dialog opens with the new date filled in.
          </Step>
          <Step n={2} title="Pick the time and the scope">
            Adjust the <strong>Time</strong>, then choose <strong>This occurrence only</strong> or{' '}
            <strong>Every occurrence</strong>.
          </Step>
          <Step n={3} title="Confirm">
            Choose <strong>Move</strong>. If the time has already passed, the run starts at the next
            check.
          </Step>
        </Steps>
        <DocsTable
          caption="Move scopes"
          rowHeaders
          head={['Scope', 'Effect', 'Available when']}
          rows={[
            [
              'This occurrence only',
              'Only this run moves. Later runs return to their usual slot, and the move survives saves and re-pins of the workflow.',
              'On the next run only. Moving a later one would cancel every run before it.',
            ],
            [
              'Every occurrence',
              'The schedule itself moves to the new time.',
              'Daily, weekly, and monthly schedules with a single time of day.',
            ],
          ]}
        />
        <p>&ldquo;Every occurrence&rdquo; is refused, with the reason, when:</p>
        <ul>
          <li>the schedule has no single time of day (for example every 15 minutes, or 9:00 and 17:00);</li>
          <li>the schedule runs on fixed weekdays and the new date is not one of them;</li>
          <li>a monthly schedule would land after the 28th, a day some months do not have.</li>
        </ul>
        <p>In each case you can still move this occurrence only.</p>

        <h2>Schedule something new</h2>
        <p>
          Click an empty slot (an empty hour in the Week or Day view, an empty day in the Month
          view) and choose what to create:
        </p>
        <ul>
          <li>
            <strong>Workflow</strong>: name it and choose <strong>Create and open</strong>. The
            workflow is created with a <strong>Scheduler</strong> trigger proposed from the slot (the same weekday and
            hour every week, or the same day every month), and opens in the builder. The schedule
            starts firing once you have built it and set it as production.
          </li>
          <li>
            <strong>Agent</strong>: name it and choose <strong>Continue</strong>. The schedule is
            carried into the agent form, and the agent exists only once you save that form. Its
            schedule runs as soon as it is saved. Give it an instruction: without one, it runs only
            when it has tasks assigned to it.
          </li>
        </ul>

        <h2>Permissions</h2>
        <Callout variant="warn" title="Viewer role">
          In an organization, members with the <strong>Viewer</strong> role can read the Agenda but
          cannot run, move, suspend, or create anything from it (&ldquo;Your role in this workspace
          does not allow changing schedules.&rdquo;). See{' '}
          <a href="/organizations">Organizations &amp; roles</a>.
        </Callout>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common Agenda problems"
          rowHeaders
          head={['Symptom', 'What to check']}
          rows={[
            ['A new scheduled workflow is not on the calendar', 'It has no pinned production version yet. Set a version as production.'],
            ['A schedule disappeared', 'It was suspended, or its resource was disabled. Turn on Show paused schedules.'],
            ['Every occurrence is unavailable', 'The schedule has no single time of day, runs on fixed weekdays and the new date is not one of them, or is monthly after the 28th. Move this occurrence only.'],
            ['Only the next run can be moved', 'A schedule holds one pending run. Use Every occurrence to move later ones.'],
            ['A run shows Will not run: spending cap reached', 'The workflow’s cost budget is used up for the period. Raise it or wait for the reset.'],
            ['Some older runs are not shown', 'The period holds more past runs than the calendar loads at once. Switch to the Week or Day view.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            Schedule syntax, pinning, and every other trigger type.
          </Card>
          <Card icon={Globe} title="Public access & sharing" href="/public-access">
            Enable, disable, or delete schedules and other endpoints.
          </Card>
          <Card icon={PlayCircle} title="Runs & execution" href="/runs">
            What a run and an epoch are, and how to control a run.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Scheduled agents, instructions, and budgets.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
