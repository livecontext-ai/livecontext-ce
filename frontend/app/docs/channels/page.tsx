import { Bot, Bell, Workflow, Server } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Chat channels',
  description:
    'Connect Telegram, Slack, Discord, WhatsApp, or Microsoft Teams so approvals, agent questions, and alerts reach you when you are not in the app.',
  path: '/docs/channels',
});

export default function ChannelsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Work & collaborate"
        title="Chat channels"
        lead="A chat channel is a chat you already read, on Telegram, Slack, Discord, WhatsApp, or Microsoft Teams, that your workspace can reach when you are not in the app. This page shows how to connect one, what arrives there, how answers are checked, and the limits of each service."
      />

      <DocsProse>
        <h2>What a channel is for</h2>
        <p>
          Agents and workflows that run on their own (on a schedule, from a webhook, or on a task)
          sometimes need a person. A connected channel is where they find you:
        </p>
        <DocsTable
          caption="What a channel delivers"
          head={['What arrives', 'What you can do from the chat']}
          rowHeaders
          rows={[
            ['Permission requests', 'An agent with Ask my permission before a sensitive action asks before it acts in a run nobody is watching. Press the approve or reject button.'],
            ['Agent questions', 'An agent running unattended asks you a multiple-choice question. Pick an option (or type your own answer on Telegram and WhatsApp).'],
            ['Workflow approvals', 'A User Approval node set to Delegate via external channel sends its decision to the chat. Press the approve or reject button.'],
            ['Alerts', 'Workflow failures, credit alerts, account issues, and task updates, if you choose Channel in your notification settings.'],
          ]}
        />
        <p>
          A channel is not a chat with your agent. A message you write that does not answer
          something you were asked is ignored by LiveContext. Email is not a channel either: alerts
          can also go by email, but decisions and questions travel only through a chat channel.
        </p>
        <p>
          Channels belong to the <strong>workspace</strong>. Every member sees the same connected
          destinations, and a workspace can have several, one of them the default.
        </p>

        <h2>Before you begin</h2>
        <ul>
          <li>
            Open <strong>Settings</strong> &gt; <strong>Channels</strong>.
          </li>
          <li>
            You need a role that can edit the workspace. A <strong>Viewer</strong> sees the list of
            destinations but cannot connect, change the default, or disconnect.
          </li>
          <li>You need an account on the service, and for some services an app or bot of your own (see the table below).</li>
        </ul>
        <Callout title="Editions and plans">
          Chat channels are available on the cloud and on self-hosted installations, on every
          plan. Receiving alerts on a channel is never plan-gated; only email alerts depend on your
          plan on the cloud.
        </Callout>

        <h2>Supported services</h2>
        <DocsTable
          caption="Supported channel services"
          head={['Service', 'What you need', 'Setup time']}
          rowHeaders
          rows={[
            ['Telegram', 'Your own bot, created in two minutes with BotFather.', 'About 3 minutes'],
            ['Slack', 'Nothing to create: approve the app in your workspace and pick a channel.', 'About 1 minute'],
            ['Discord', 'Your own Discord application and bot, plus one URL to paste in its portal.', 'About 5 minutes'],
            ['WhatsApp', 'A Meta business app with WhatsApp, its phone number ID, and an access token.', 'About 10 minutes'],
            ['Microsoft Teams', 'Nothing to create: sign in with Microsoft and pick a chat. Messages go out as you.', 'About 1 minute'],
          ]}
        />

        <h2>Connect a channel with the assistant</h2>
        <p>
          The assistant walks you through each step in the service’s own console, one at a time,
          and you can ask it questions along the way. The assistant’s turns use credits.
        </p>
        <Steps>
          <Step n={1} title="Pick a service">
            <p>
              In <strong>Settings</strong> &gt; <strong>Channels</strong>, under{' '}
              <strong>Add a channel</strong>, press <strong>Set up with the assistant</strong> on
              the service you want. The chat opens with the request already written.
            </p>
          </Step>
          <Step n={2} title="Follow the steps">
            <p>
              Do what the assistant asks in the service (create a bot, copy a token, add the bot to
              a chat), and confirm each step. You can also ask the assistant directly from any
              chat, for example &quot;I want my approvals to reach me on Slack&quot;.
            </p>
          </Step>
          <Step n={3} title="Check the test message">
            <p>
              The assistant connects the chat and sends a test message. The destination then
              appears under <strong>Connected destinations</strong>.
            </p>
          </Step>
        </Steps>

        <h2>Connect a channel manually</h2>
        <p>
          <strong>Set up manually</strong> opens a form with the same steps. It uses no credits.
        </p>
        <Steps>
          <Step n={1} title="Your account">
            <p>
              Connect the service account (a bot token, or a sign-in for Slack and Teams). Discord
              also asks for the <strong>Application public key</strong>, and WhatsApp for the{' '}
              <strong>Phone number ID</strong>.
            </p>
          </Step>
          <Step n={2} title="Say hello to your bot">
            <p>
              A bot cannot write to someone who never wrote to it. Send it a message, or add it to
              the group or channel that should receive the messages.
            </p>
          </Step>
          <Step n={3} title="Where messages go">
            <p>
              Press <strong>Find my chats</strong> and pick the chat, or type its id in{' '}
              <strong>Or type the chat id</strong>. For WhatsApp, type the phone number in
              international format.
            </p>
          </Step>
          <Step n={4} title="Connect and send a test message">
            <p>
              <strong>Connected: the test message arrived.</strong> means the destination works.
              For Discord and WhatsApp, the form then shows <strong>One more step in the
              service</strong>: a URL (and, for WhatsApp, a verify token) to paste in the
              service’s developer console so button presses come back.
            </p>
          </Step>
        </Steps>

        <h3>Per-service notes</h3>
        <DocsTable
          caption="Per-service setup notes"
          head={['Service', 'Setup notes']}
          rowHeaders
          rows={[
            ['Telegram', 'Create the bot with /newbot in @BotFather and copy its token. Press Start in your bot (or add it to a group and post there). To send to yourself, use your numeric id, which @userinfobot sends you. A group id starts with -100. Find my chats only works before the bot is connected; after that, type the id.'],
            ['Slack', 'Connect Slack and approve the app. For a channel, type /invite followed by the app name in it; a direct message needs nothing. Then pick it with Find my chats.'],
            ['Discord', 'In the Discord Developer Portal, create an application, open Bot, and copy its token. In OAuth2 > URL Generator, tick bot and Send Messages, and add the bot to your server. Paste the token and the Public Key, pick the channel, then paste the Interactions Endpoint URL shown after connecting on the General Information page.'],
            ['WhatsApp', 'In your Meta app, API Setup: note the Phone number ID and create a permanent System User token. From the phone that will answer, send any message to your business number first. After connecting, set the callback URL and verify token shown on screen.'],
            ['Microsoft Teams', 'Sign in with Microsoft and pick the chat. Messages are posted as you, and Teams does not notify you about your own messages: pick a chat other people read, or use another service to be notified yourself.'],
          ]}
        />

        <h2>Manage your destinations</h2>
        <p>
          Each destination under <strong>Connected destinations</strong> shows its status:{' '}
          <strong>Working: a message was delivered here.</strong> or{' '}
          <strong>Not working yet: nothing has been delivered here.</strong>, and the last error,
          if any.
        </p>
        <DocsTable
          caption="Destination actions"
          head={['Action', 'What it does']}
          rowHeaders
          rows={[
            ['Make default', 'Makes this destination the workspace default. Only a destination that has received a message can be the default. The first working destination becomes the default on its own.'],
            ['Fix with the assistant', 'Shown on a destination that does not work yet. Opens the chat with the error, so the assistant can find the cause.'],
            ['Disconnect', 'Approvals and questions stop arriving there. If it was the default, the default moves to another working destination.'],
          ]}
        />
        <p>
          A destination can be limited to certain people (shown as{' '}
          <strong>N people may decide</strong>): presses from anyone else are refused. Ask the
          assistant to set or clear that list. Microsoft Teams cannot tell who pressed, so a Teams
          destination cannot be limited.
        </p>

        <h2>Choose where an agent reaches you</h2>
        <p>
          In the agent editor, step <strong>Integration</strong>, the{' '}
          <strong>Reach me outside the app</strong> card controls whether the agent may reach you
          at all, and where:
        </p>
        <ul>
          <li>
            <strong>Destination</strong> picks one of the workspace’s destinations. Leave it empty
            to use the workspace default. If the chosen destination is later disconnected, the
            agent’s requests are reported as not delivered rather than sent to another chat.
          </li>
          <li>
            <strong>Ask my permission before a sensitive action</strong> makes the agent ask before
            installs, runs, sub-agents, and catalog calls in runs nobody is watching. Without an
            answer, the action is not done.
          </li>
          <li>
            With the card off, nothing leaves the app: permission requests are not sent (the action
            is not done), and questions are answered by assumption.
          </li>
        </ul>
        <p>
          The card is available once the workspace has a connected channel; otherwise it offers{' '}
          <strong>Connect a channel</strong>. See <a href="/agents">Agents</a> for how permissions
          and questions work.
        </p>

        <h2>Send workflow approvals to a channel</h2>
        <p>
          On a User Approval node, turn on <strong>Delegate via external channel</strong> and
          pick the <strong>Channel</strong>. The account and the chat id are optional: leave them
          empty to use the destination connected on that service in Settings &gt; Channels (its
          default when the default is on that service). You can also list the user ids allowed to
          decide; empty lets anyone in the chat decide. The run waits for the decision, which you
          can still make in the app. On Telegram the approval message can include an image.
        </p>

        <h2>Get alerts on a channel</h2>
        <p>
          In <strong>Settings</strong> &gt; <strong>Overview</strong> &gt;{' '}
          <strong>Notifications</strong>, choose for each topic (<strong>Workflow failures</strong>,{' '}
          <strong>Credits</strong>, <strong>Account issues</strong>, <strong>Tasks</strong>)
          where alerts go: <strong>Email and channel</strong>, <strong>Email</strong>,{' '}
          <strong>Channel</strong>, or <strong>Bell only</strong>. Channel alerts go to the
          workspace’s default chat, so everyone in that chat can read them. Credit alerts go to
          the channel of your personal workspace. See <a href="/notifications">Notifications</a>.
        </p>

        <h2>Security &amp; verification</h2>
        <ul>
          <li>
            <strong>A destination is proven by delivery.</strong> Connecting always sends a test
            message, and only a destination that received one can be the default.
          </li>
          <li>
            <strong>A button only works where it was sent.</strong> Each button carries a
            single-use token tied to the service, the bot, and the chat it went to. A press from
            anywhere else is refused.
          </li>
          <li>
            <strong>Requests from the services are checked.</strong> Slack requests must carry
            Slack’s signature; Discord presses are verified with your application’s public key,
            which is checked against your bot at connect; old signed requests are refused.
          </li>
          <li>
            <strong>On Teams, opening a link decides nothing.</strong> The buttons are links to a
            confirmation page, and only its <strong>Confirm</strong> button decides, so link
            previews and security scanners cannot approve by accident.
          </li>
          <li>
            <strong>The people list is a guard, not a login.</strong> It prevents a mistaken tap by
            someone else in the chat. The real protection is who is in the chat: anyone there can
            read the messages, so pick a private chat for sensitive requests.
          </li>
          <li>
            <strong>A late approval covers exactly what you saw.</strong> An Approve that arrives
            after the run ended authorizes that same action on the agent’s next run, not any other
            action of the same kind.
          </li>
          <li>
            <strong>Nothing on this path can make an action run on its own.</strong> Any failure
            leaves the request unanswered, and the action is not done.
          </li>
        </ul>

        <h2>Limits</h2>
        <DocsTable
          caption="Channel limits"
          head={['Limit', 'Value']}
          rowHeaders
          rows={[
            ['Permission request lifetime', '24 hours, then it expires and its buttons stop working. The agent may ask again.'],
            ['Question lifetime', '6 hours.'],
            ['Duplicate requests', 'One waiting permission request per agent conversation and action: the agent does not ask twice for the same action.'],
            ['WhatsApp reply window', 'WhatsApp only lets a business write to someone who messaged it in the last 24 hours. Outside that window, delivery fails with a re-engagement error.'],
            ['Typed answers ("Other...")', 'Telegram and WhatsApp only.'],
            ['Closing a settled message', 'Telegram, Slack, and Discord remove the buttons. WhatsApp and Teams cannot edit a message, so a short follow-up is sent instead, and a late press is answered "already decided".'],
            ['Images in approvals', 'Telegram only.'],
            ['Limiting who may decide', 'Not available on Microsoft Teams.'],
          ]}
        />

        <h2>Self-hosted installations</h2>
        <ul>
          <li>
            Button presses come back to your installation from the service, so your installation
            must be reachable from the internet at its public address.
          </li>
          <li>
            Slack uses the platform Slack app your administrator registered. Presses are refused
            until the administrator sets that app’s signing secret (<code>SLACK_SIGNING_SECRET</code>)
            and points its Interactivity Request URL at your installation.
          </li>
        </ul>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Channel troubleshooting"
          head={['Symptom', 'What to check']}
          rows={[
            ['The test message did not arrive (Telegram)', 'You never pressed Start in your bot, or you gave the bot’s own @name. A bot cannot write to itself: use your numeric id from @userinfobot.'],
            ['Find my chats finds nothing', 'Telegram: send a message to the bot first. Slack: /invite the app to the channel. Discord: add the bot to your server. Teams: start a chat first.'],
            ['Telegram cannot list chats', 'The bot is already connected, and Telegram does not replay messages then. Type the chat id instead.'],
            ['Buttons do nothing (Discord or WhatsApp)', 'The Interactions Endpoint URL (Discord) or the callback URL and verify token (WhatsApp) were not set in the service’s console.'],
            ['WhatsApp messages fail', 'The 24-hour window is closed. Send any message to your business number from that phone, then retry.'],
            ['Nobody is notified on Teams', 'Teams does not notify you about your own messages. Pick a chat that other people read.'],
            ['An agent says its request was not delivered', 'Its chosen Destination was disconnected, or the workspace has no working destination. Check Settings > Channels and the agent’s Destination.'],
            ['Make default is missing', 'The destination has not received a message yet. Fix it first.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Bot} title="Agents" href="/agents">Permissions, questions, and unattended runs.</Card>
          <Card icon={Bell} title="Notifications" href="/notifications">The bell and where alerts go.</Card>
          <Card icon={Workflow} title="Node reference" href="/nodes">The User Approval node.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Run LiveContext on your own server.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
