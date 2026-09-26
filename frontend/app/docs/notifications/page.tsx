import { CalendarClock, Columns3, MessagesSquare } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Notifications',
  description:
    'The notification bell and its four tabs, what raises a notification, what you can do from it, and how alerts also reach you by email or in a chat app.',
  path: '/docs/notifications',
});

export default function NotificationsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Work & collaborate"
        title="Notifications"
        lead="The bell in the header is where LiveContext tells you what needs your attention: failed production runs, approvals, expired credentials, tasks, invitations and credits. Some of these alerts can also reach you by email or in a connected chat app."
      />

      <DocsProse>
        <h2>The notification bell</h2>
        <p>
          The bell sits at the top right of every page, on desktop and on mobile. It always gets every
          notification, whatever your delivery settings. Two markers on the icon tell you something
          without opening it:
        </p>
        <ul>
          <li>A <strong>red dot</strong>: you have unread items in the inbox.</li>
          <li>
            A <strong>pulsing blue dot</strong> (when nothing is unread): one of your automations is
            due to fire within the next 5 minutes.
          </li>
        </ul>
        <p>
          Click the bell to open it. It has four tabs: <strong>Inbox</strong>,{' '}
          <strong>Triggers</strong>, <strong>Activity</strong>, and <strong>Shared</strong>. When the
          inbox is empty, the bell opens on <strong>Triggers</strong> if you have armed automations,
          and on <strong>Activity</strong> otherwise.
        </p>

        <h2>Inbox</h2>
        <p>
          The inbox lists things that happened and may need you, newest first. Repeated events about
          the same item are grouped into one row with a count, for example &ldquo;3 failed runs&rdquo;
          on one workflow.
        </p>
        <DocsTable
          caption="What raises an inbox notification, and where clicking it takes you"
          rowHeaders
          head={['Row', 'Raised when', 'Clicking opens']}
          rows={[
            ['Failed run', 'A run of a production workflow fails, is cancelled, or times out. Test runs from the editor do not count.', 'That run'],
            ['Workflow stopped on its spending cap', 'A production workflow hits its spending cap.', 'The workflow, or its run'],
            ['Pending approval', 'A production run waits on a User Approval node. The row goes away once the approval is resolved.', 'The workflow run'],
            ['Expired credential', 'A connected account can no longer be refreshed.', 'That credential in Settings'],
            ['Disabled trigger', 'A trigger was switched off.', 'The matching tab of Public Access'],
            ['Task assigned', 'A task is assigned to one of your agents, or to you as assignee or reviewer.', 'The task board'],
            ['Task awaiting your review', 'A task you must decide on reaches In Review.', 'The task board'],
            ['Task mention', 'Someone mentions you in a task note.', 'The task board'],
            ['Agent with nobody to ask', 'An unattended agent run needed someone to authorize a tool and could reach nobody.', 'The agent'],
            ['Organization invitation', 'You are invited to join a workspace.', 'Your invitations'],
            ['Credits running low, Out of credits', 'Your credit balance is low or empty.', 'Billing'],
            ['New trophy', 'You unlock a trophy.', 'Your trophies'],
          ]}
        />
        <h3>Act from the inbox</h3>
        <ul>
          <li>Click a row to open what it is about.</li>
          <li>
            A row for a pending approval has two buttons: <strong>Open</strong> goes to the
            run, <strong>Review</strong> opens the pending approvals in a dialog so you can decide
            without leaving the page you are on.
          </li>
          <li>Hover a row and click <strong>Delete</strong> to remove it.</li>
          <li>
            <strong>Mark all read</strong> clears the unread count. Use <strong>Previous page</strong>{' '}
            and <strong>Next page</strong> to browse older items.
          </li>
        </ul>

        <h2>Triggers</h2>
        <p>
          <strong>Triggers</strong> looks ahead: it lists your armed automations (workflows,
          applications and agents with a live production trigger), with a countdown to the next run
          for schedules and when each one last ran. Chips filter by trigger kind; the tab opens on{' '}
          <strong>Schedule</strong> each time, and clicking the active chip shows every kind. A badge
          marks an automation that is <strong>disabled</strong> or <strong>capped</strong> by its
          spending cap.
        </p>
        <p>Hover a row and open its menu. The run actions appear on scheduled rows only:</p>
        <DocsTable
          caption="Actions on a scheduled automation row"
          head={['Action', 'Effect']}
          rows={[
            [<strong key="a">Run now</strong>, 'Runs it immediately. The scheduled run still happens.'],
            [<strong key="a">Run instead of the scheduled run</strong>, 'Runs it immediately and uses up the run that was coming.'],
            [<strong key="a">Disable workflow</strong>, 'Stops all of its production triggers until you choose Reactivate workflow. The label follows the resource (agent, interface).'],
            [<strong key="a">Open in the agenda</strong>, 'Opens the agenda on the day of the next run, with the schedule highlighted.'],
          ]}
        />
        <p>
          The two run actions are unavailable on an automation blocked by its spending cap. For moving
          an occurrence or seeing the week ahead, use the <a href="/agenda">Agenda</a>.
        </p>

        <h2>Activity</h2>
        <p>
          <strong>Activity</strong> shows the last 50 edits in the active workspace, with who made
          each one and when. Filter by kind: <strong>Workflow</strong>, <strong>Application</strong>,{' '}
          <strong>Interface</strong>, <strong>Agent</strong>, <strong>Skill</strong>, or{' '}
          <strong>Table</strong>. Click a row to open that resource.
        </p>

        <h2>Shared</h2>
        <p>
          <strong>Shared</strong> lists every public link in the workspace (a disabled one is marked <strong>Disabled</strong>):{' '}
          <strong>Conversations</strong>, <strong>Applications</strong>,{' '}
          <strong>Chat endpoints</strong>, and <strong>Form endpoints</strong>. Each row shows how
          often the link was opened, and has <strong>Copy public link</strong>,{' '}
          <strong>Open public link</strong>, and <strong>Revoke link</strong>. Revoking cuts access
          for anyone who has the URL. The same links are managed in{' '}
          <a href="/public-access">Public access</a>.
        </p>

        <h2>Alerts by email and in chat apps</h2>
        <p>
          Beyond the bell, four kinds of alert can be sent to you by email, to a connected chat
          channel, or both. Choose per kind in <strong>Settings</strong> &gt;{' '}
          <strong>Overview</strong> &gt; <strong>Notifications</strong>, with the options{' '}
          <strong>Email and channel</strong>, <strong>Email</strong>, <strong>Channel</strong>, or{' '}
          <strong>Bell only</strong>.
        </p>
        <DocsTable
          caption="Alert kinds that can leave the app, and their default delivery"
          rowHeaders
          head={['Kind', 'Covers', 'Sent', 'Default']}
          rows={[
            ['Workflow failures', 'A production workflow you own failed or stopped on its spending cap.', 'Right away', 'Email and channel'],
            ['Credits', 'Your balance fell to 20% of your monthly grant or less, or below 1 credit (out: workflow steps are refused from there). Checked every 15 minutes, sent once per level (low, then out) per cycle, and only to accounts that spent credits in the last 7 days. Never sent with unlimited credits (the self-hosted default).', 'Right away', 'Email and channel'],
            ['Account issues', 'A credential expired or a trigger was switched off.', 'In the daily summary', 'Email'],
            ['Tasks', 'A task was assigned to you, mentions you, or waits for your review.', 'In the daily summary', 'Email'],
          ]}
        />
        <p>
          Messages are written in your language and time zone. The rules keep them few: one message
          when a workflow breaks, at most one reminder a day while it stays broken, and one when it
          works again. You never get more than 10 alerts on one medium in any 24 hours; beyond that, further
          alerts wait for the next daily summary, and extra reminders and &ldquo;working again&rdquo;
          messages are dropped.
        </p>
        <p>
          Choices apply to the workspace you are in, except <strong>Credits</strong>, which applies
          in all your workspaces. Channel alerts go to the workspace&apos;s default chat channel, so
          everyone in that chat can read them. <strong>Credits</strong> alerts are the exception: they
          go to your personal workspace&apos;s channel. If no channel is connected, the channel options are
          unavailable; see <a href="/channels">Channels</a> to connect one.
        </p>
        <p>
          The same page shows <strong>Where channel alerts go</strong>: the default chat, the service,
          and the bot that sends the messages. When several chats are connected, a member who can
          edit the workspace can choose <strong>Send the alerts to another chat</strong>. The chosen
          chat becomes the workspace default, so approvals and agent questions that follow the default
          go there too. The rules that limit alerts are behind <strong>How alerts are limited</strong>,
          next to the page title.
        </p>
        <Callout title="Plan required for email">
          On the cloud, alert emails are included from the <strong>Starter</strong> plan. Credit
          alerts are the exception: they are emailed on every plan, Free included. The settings page
          shows the plan you need next to each kind.
        </Callout>
        <Callout variant="info" title="Not sent outside the app">
          Approvals and agent questions are not in this list because they already reach your chat
          channel through their own interactive messages. Organization invitations are emailed
          separately, and trophies stay in the bell.
        </Callout>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common notification problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'The channel options cannot be picked, with “No chat channel is connected to this workspace”',
              'The active workspace has no chat channel connected.',
              'Follow Connect a channel, then come back and choose Channel or Email and channel.',
            ],
            [
              'A kind shows “With this choice nothing leaves the app: the bell still shows it.”',
              'The saved choice can no longer be delivered: your plan no longer includes alert emails, the channel was disconnected, or it is the default on a Free plan with no channel.',
              'Pick an option that is available, connect a channel, or upgrade to a plan that includes alert emails.',
            ],
            [
              'Email options are unavailable, with “Email alerts are included from the Starter plan.”',
              'On the cloud Free plan, only credit alerts are emailed.',
              'Upgrade to Starter or above, or use Channel.',
            ],
            [
              'A failed run does not appear in the inbox',
              'Only runs of a production workflow (a pinned version) raise a notification. Test runs from the editor do not.',
              'Pin a version to production (see Triggers).',
            ],
            [
              'Run now and Run instead of the scheduled run are unavailable, with “Blocked by the spending cap”',
              'The automation has reached its spending cap.',
              'Raise the cap or change how it resets. A cap that never resets stays blocked until you do.',
            ],
            [
              'Channel alerts never arrive although chats are connected',
              'None of the connected chats is the workspace default, or the default chat is switched off. Both cases are stated under Where channel alerts go.',
              'Choose Send the alerts to another chat, or make a chat the default in Settings > Channels and switch it on.',
            ],
            [
              'Alerts stop arriving after a burst of failures',
              'No medium sends more than 10 alerts in any 24 hours.',
              'Check the bell, which always gets everything. The rest arrives in the next daily summary.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={MessagesSquare} title="Channels" href="/channels">
            Connect a chat app for approvals and alerts.
          </Card>
          <Card icon={CalendarClock} title="Agenda" href="/agenda">
            Every scheduled run, on a calendar.
          </Card>
          <Card icon={Columns3} title="Tasks & board" href="/board">
            Where task notifications take you.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
