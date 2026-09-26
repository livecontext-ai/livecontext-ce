import { Workflow, Bot, Boxes, Sparkles, MessagesSquare, Bell } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Chat',
  description:
    'Build, run, and fix workflows by talking to Orbi: the composer, the model picker and reasoning effort, attachments, chat settings, approvals, questions, sharing, and the activity view.',
  path: '/docs/chat',
});

export default function ChatPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Chat"
        lead="Chat is where you build and run automations by talking. You describe the outcome you want, and Orbi, the assistant, assembles the workflow, runs it, and edits it as you give feedback, without leaving the conversation."
      />

      <DocsProse>
        <h2>Orbi and agent chats</h2>
        <p>
          <strong>Orbi</strong> is the general assistant: a conversation with no agent attached. Its
          composer reads <strong>Message Orbi...</strong>, and its mascot pulses while it is thinking.
          You can also chat with one of your own <a href="/agents">agents</a>. In an agent chat, the
          agent&apos;s avatar replaces the model picker, because the agent brings its own model, tools,
          and skills.
        </p>
        <p>
          On the home screen, a <strong>Chat</strong> / <strong>Studio</strong> switch in the composer
          moves between chatting and generating media. See <a href="/studio">Studio</a>. Under an empty
          composer, suggestion chips (such as <strong>Summarize my inbox each morning</strong>) fill in a
          detailed request without sending it.
        </p>

        <h2>What you can ask</h2>
        <p>In a single thread, you can ask Orbi to:</p>
        <ul>
          <li><strong>Build</strong> a workflow, an agent, a table, or an app from a description of the job.</li>
          <li><strong>Change</strong> a step: swap an integration, adjust a condition, shorten a prompt.</li>
          <li><strong>Run</strong> the workflow and show you the results.</li>
          <li><strong>Inspect</strong> a past run to explain what happened or why it failed.</li>
          <li><strong>Connect</strong> an integration or wrap the workflow in an app.</li>
        </ul>
        <p>
          A workflow always starts with a trigger. If you describe the steps first, Orbi adds a trigger
          before them. It tells you what it just did and what it suggests next.
        </p>
        <Callout variant="tip">
          Iterate out loud: &ldquo;Make the summary shorter&rdquo;, &ldquo;only do this for VIP
          customers&rdquo;, &ldquo;add a step that logs it&rdquo;. Orbi edits the existing workflow
          instead of starting over, and you can run it again right away to see the effect.
        </Callout>

        <h2>Running and debugging from chat</h2>
        <p>
          Ask Orbi to run the workflow and the run trace streams into the thread, step by step, with each
          node&apos;s output. If something fails, ask <em>&ldquo;why did that fail?&rdquo;</em>. Orbi
          reopens the run and reads it node by node, so it can tell you which step broke and why (a bad
          credential, a missing field, an empty result) and offer a fix.
        </p>
        <CodeBlock title="Example exchange">{`You:   Run it with a test contact.
Orbi:  The run stopped at "Send welcome email". The first three steps
       succeeded, but the mail server rejected the credential.
       Want me to point the step at a different one?`}</CodeBlock>
        <p>
          Two kinds of steps do the work. <strong>Plain steps</strong> (an integration call, an HTTP
          request, a data transform, a bit of code) do exactly one predictable thing.{' '}
          <strong>Agents</strong> reason: they decide, summarize, classify, or handle open-ended input. Use
          an agent when the task needs judgement, and a plain step when the operation is fixed. Plain
          steps are faster, cheaper, and easier to audit.
        </p>

        <h2>The composer</h2>
        <p>
          The message box grows as you type. <strong>Enter</strong> sends, and{' '}
          <strong>Shift+Enter</strong> adds a new line. While Orbi is answering, the send button turns
          into <strong>Stop</strong>, and pressing <strong>Escape</strong> also stops the answer (unless
          Escape is closing a menu or dialog first).
        </p>
        <p>As Orbi works, its progress renders live in the thread:</p>
        <ul>
          <li>A thinking indicator shows while it prepares its next move.</li>
          <li>
            Each tool call renders as a <strong>tool card</strong> that updates as the call completes.
            Back-to-back calls to the same tool share one card. Only the latest steps stay expanded;
            older ones fold behind a toggle.
          </li>
          <li>
            Results that are more than text render as previews: tables, interfaces, applications,
            generated images and files, workflow diagrams, and run traces. Several previews in a row are
            grouped into a carousel. Click a workflow diagram to open it in the side panel.
          </li>
        </ul>
        <p>
          On a narrow screen or a narrow side panel, the attach and tools buttons merge into a single{' '}
          <strong>More actions</strong> (+) menu.
        </p>

        <h2>Model picker and reasoning effort</h2>
        <p>
          The model picker sits in the composer. Each model shows its provider icon, what it can do, and
          an info popover with more detail. Models covered by the Free plan&apos;s monthly credits carry
          a <strong>Free</strong> mark. The footer of the menu has <strong>Tier</strong> and{' '}
          <strong>Provider</strong> filters, remembered in your browser, and a <strong>Reset</strong>{' '}
          button.
        </p>
        <p>
          Your model choice applies across the whole app, not only to the current conversation: it is
          used for your next message in any chat, and your browser remembers it.
        </p>
        <p>
          When the selected provider supports it, a <strong>Reasoning effort</strong> control appears in
          the menu footer, next to the filters. Like the model, it applies app-wide, but it resets to{' '}
          <strong>Auto</strong> when you reload the page. The chat in the side panel has no effort
          control.
        </p>
        <DocsTable
          caption="Reasoning effort control by provider"
          head={['Provider', 'Reasoning effort control']}
          rowHeaders
          rows={[
            ['Claude Code (bridge)', 'Shown'],
            ['Codex (bridge)', 'Shown'],
            ['Anthropic (direct API)', 'Shown'],
            ['Gemini CLI (bridge)', 'Hidden, no usable setting'],
            ['Mistral Vibe (bridge)', 'Hidden, no usable setting'],
            ['Other providers', 'Hidden'],
          ]}
        />
        <p>
          The levels are <strong>Auto</strong> (the model&apos;s own default), then <code>minimal</code>,{' '}
          <code>low</code>, <code>medium</code>, <code>high</code>, <code>xhigh</code>, and{' '}
          <code>max</code>, from the lightest to the deepest reasoning. Deeper levels are slower and cost
          more. When a model does not support a level, the nearest level it supports is used instead.
        </p>

        <h3>Using your own API key</h3>
        <p>
          On the cloud, you can run chats and agents on your own model provider key from{' '}
          <strong>Settings</strong> &gt; <strong>AI Providers</strong> &gt; <strong>Your keys</strong>. It
          requires the Pro plan or higher. Your provider bills the tokens, and LiveContext charges a
          small fee per turn. See <a href="/models">Models &amp; providers</a>.
        </p>

        <h2>Attachments</h2>
        <p>
          <strong>Attach files</strong> (the paperclip) adds files to a message. You can attach several
          files to one message, along with your text.
        </p>
        <DocsTable
          caption="Accepted attachment types"
          head={['Kind', 'Accepted types']}
          rowHeaders
          rows={[
            ['Image', 'JPEG, PNG, GIF, WebP'],
            ['PDF', 'PDF documents'],
            ['Text', 'Plain text, Markdown, CSV, HTML, JSON, XML, JavaScript, and CSS'],
          ]}
        />
        <p>
          On LiveContext Cloud, each file can be up to <strong>10 MB</strong>. Images get a thumbnail that
          opens full size on click. PDFs have their text extracted so the model can read them, and text
          files are read as they are. If an upload fails, the error shows on the file&apos;s chip in the
          composer.
        </p>

        <h2>Tools, skills, and chat settings</h2>
        <p>
          The <strong>Tools</strong> button in the composer opens a panel with three tabs:
        </p>
        <ul>
          <li><strong>Tools</strong>: the integration tools available to the conversation.</li>
          <li>
            <strong>Skills</strong>: turn skills on or off for this conversation. Untouched, the list
            follows the platform&apos;s default skills. In an agent chat, this tab edits the agent&apos;s
            own skill list instead. See <a href="/skills">Skills</a>.
          </li>
          <li><strong>Options</strong>: the chat settings described below.</li>
        </ul>
        <p>
          The settings apply at one of three scopes: <strong>Conversation settings</strong>,{' '}
          <strong>Agent settings</strong> (in an agent chat), or <strong>Defaults for next
          conversation</strong>. Workspace defaults for new Orbi and agent conversations live on the{' '}
          <strong>Agents</strong> page, in its <strong>Settings</strong> tab.
        </p>
        <DocsTable
          head={['Setting', 'Default', 'Notes']}
          rowHeaders
          caption="Chat settings in the Options tab"
          rows={[
            ['System prompt', 'Empty', 'Instructions that guide the model in this conversation.'],
            ['Temperature', '0.7', 'From 0 to 2, in steps of 0.1. Higher means more varied answers.'],
            ['Max tokens', '16000', 'The longest single response the model may write.'],
            ['Max iterations', '100', 'From 1 to 1000 tool-calling cycles within one turn.'],
            ['Timeout (s)', '3600', 'Total time a turn may run, from 10 to 7200 seconds.'],
            ['Inactivity timeout (s)', '300', 'Stops the turn if nothing happens for this long. 0 turns it off, otherwise 10 to 7200 seconds.'],
            ['Tools mode', 'All tools', 'All tools or No tools. A custom tool list is set on an agent.'],
            ['Web search', 'On', 'Lets the assistant search the web for up-to-date information.'],
            [
              'Generation',
              'Off',
              'Lets the assistant create images, videos, audio, voices, and music. Each generation is charged in credits, and longer outputs cost more.',
            ],
            [
              'Mailbox',
              'Off',
              'Lets the assistant read and send email with your connected mailbox. Mailbox permissions start at Read-only; Full access also allows sending, moving, and deleting.',
            ],
            [
              'Run sensitive actions without asking',
              'Off',
              'Skips the authorization cards in this conversation. Not shown in agent chats.',
            ],
            [
              'Context compaction',
              'On (platform setting)',
              'Summarizes older turns so long conversations fit the model. Compact after N turns defaults to 5 (1 to 100). Summariser model picks the model that writes the summaries.',
            ],
          ]}
        />
        <Callout variant="info" title="Compaction switch">
          Compaction is on by default for every conversation. The <strong>Context compaction</strong>{' '}
          switch shows only an override set on this conversation, so it can read off while the platform
          default still applies.
        </Callout>
        <p>
          <strong>Advanced limits</strong> are per-turn safety stops that prevent runaway loops:
        </p>
        <DocsTable
          caption="Advanced per-turn limits in chat settings"
          head={['Limit', 'Default', 'Range', 'What it counts']}
          rowHeaders
          rows={[
            ['Max per-resource / turn', '5', '1 to 100', 'Creation calls per resource type (agent, skill, sub-agent, interface, workflow, table) within one turn, capped for each type separately.'],
            ['Identical-loop stop', '15', '2 to 100', 'Identical repeated steps in a row.'],
            ['Consecutive-loop stop', '40', '4 to 200', 'Consecutive steps in one turn, whatever tool they call.'],
          ]}
        />
        <p>
          A <strong>microphone</strong> button (shown when your browser supports speech recognition)
          dictates into the composer and adds the transcript to what you already typed.
        </p>

        <h2>Approvals: authorization cards</h2>
        <p>
          Before Orbi does something sensitive, a card asks you to approve or deny it. That includes:
          installing or running an application, continuing a paused interface, resolving a pending
          approval step, running or restarting part of a workflow, setting or removing a production
          version, running an agent, calling an integration directly, sending or deleting email, and
          creating or updating an agent with a schedule. Installing an application shows its marketplace
          listing in the card.
        </p>
        <ul>
          <li>
            <strong>Approve</strong> lets the action go ahead. If the assistant had already paused, it
            resumes as soon as it is free.
          </li>
          <li>
            <strong>Deny</strong> blocks that one action. The assistant is told you refused and continues
            with the rest of its work.
          </li>
          <li>
            The &ldquo;don&apos;t ask again in this conversation&rdquo; checkbox turns on the same setting
            as <strong>Run sensitive actions without asking</strong>.
          </li>
        </ul>
        <p>
          A separate card groups the external services a workflow needs. If every credential is already
          connected and healthy, it approves and closes itself. If one may be expired or invalid, it stays
          open with a way to manage or retry it.
        </p>

        <h3>Connecting an app from a card</h3>
        <p>
          When a tool needs a credential you have not connected, a connect card opens the credential
          wizard for that service. After an OAuth sign-in, you come back to the conversation with the
          wizard showing the connected state. The services card resumes the assistant automatically. After
          a plain connect card, tell the assistant to continue if it does not pick up on its own. When
          several cards are open, each one resolves on its own.
        </p>

        <h2>When the assistant asks you a question</h2>
        <p>
          When it needs a decision, the assistant can put a question card in the thread, titled{' '}
          <strong>The assistant has a question</strong>. A card holds 1 to 4 questions, each with 2 to 4
          options.
        </p>
        <ul>
          <li>
            Each question says <strong>Choose one</strong> or <strong>Choose one or more</strong>.{' '}
            <strong>Other</strong> lets you type your own answer.
          </li>
          <li>
            Move with <strong>Back</strong> and <strong>Next</strong>, then <strong>Send answer</strong>.{' '}
            <strong>Skip</strong> tells the assistant to continue without your answer.
          </li>
          <li>Number keys pick an option and arrow keys move between options.</li>
          <li>
            If the assistant&apos;s turn already ended, your answer starts it again. Your answers stay
            visible in the thread afterwards.
          </li>
        </ul>
        <p>
          When nobody is in the chat (for example an agent running on a schedule), questions and approvals
          go to your linked chat channel instead. See <a href="/channels">Chat channels</a>.
        </p>

        <h2>Conversation activity</h2>
        <p>
          The <strong>Conversation activity</strong> button in the chat header opens a view of everything
          the conversation ran: one group per message you sent, with the agent and tool calls it
          triggered. Each group shows its tokens, iterations, and cost (credits on the cloud, a dollar
          amount on a self-hosted instance), and <strong>Go to message</strong> jumps back to it in the
          thread. Whether the view is open is remembered in your browser for all conversations.
        </p>

        <h2>Sharing, search, and message actions</h2>
        <p>
          <strong>Sharing</strong> a conversation from the chat header creates a read-only public link.
          Your workspace has one quota for all shared links: conversations, applications, and chat and
          form endpoints. Disabled links still count. Only deleting a link frees its slot.
        </p>
        <DocsTable
          caption="Shared links allowed per workspace, by plan"
          head={['Plan', 'Shared links per workspace']}
          rowHeaders
          rows={[
            ['No subscription', '10'],
            ['Free', '5'],
            ['Starter', '20'],
            ['Pro', '50'],
            ['Team', '100'],
            ['Enterprise', '200'],
            ['Self-hosted (CE)', 'Unlimited'],
          ]}
        />
        <p>
          The <strong>Shared</strong> tab of the notification bell lists every active link, with copy,
          open, and revoke actions.
        </p>
        <p>
          <strong>Search</strong> finds past conversations by content or title, and the conversation list
          can be filtered to all conversations, agent chats, workflow chats, or Studio threads.
        </p>
        <p>Each assistant message has these actions:</p>
        <DocsTable
          caption="Actions on an assistant message"
          head={['Action', 'What it does']}
          rowHeaders
          rows={[
            ['Copy', 'Copies the message text.'],
            ['Share', "Opens your device's share sheet, or copies the text when there is none."],
            ['Helpful / Not helpful', 'Rates the answer. Not shown in direct-message threads.'],
            ['Download', 'Saves the message as a Markdown file.'],
          ]}
        />
        <p>Your own messages have <strong>Copy</strong> only.</p>

        <h2>Memory</h2>
        <p>
          Within a conversation, <strong>context compaction</strong> summarizes older turns so the
          assistant keeps the gist without carrying the whole transcript. That is separate from{' '}
          <strong>long-term memory</strong>: durable facts agents save while they work, which you can view,
          edit, pin, or delete from the <strong>Memory</strong> tab of the Agents page. See{' '}
          <a href="/agents">Agents</a>.
        </p>

        <h2>Sending while the assistant works</h2>
        <p>
          You do not have to wait for an answer before sending the next message. While the assistant is
          working, new messages are queued instead of interrupting it. The queue holds up to 5 messages.
          You can <strong>Edit</strong>, <strong>Remove</strong>, or reorder a queued message, or use{' '}
          <strong>Send now</strong>, which stops the current answer first and then sends it.
        </p>
        <p>
          The queue and your unsent draft are kept per browser tab. Text-only queued messages and drafts
          survive a page reload for up to 30 minutes. Queued messages with attachments are kept only
          until you reload.
        </p>

        <h2>Notifications and generation</h2>
        <p>
          The <strong>notification bell</strong> in the header has four tabs: <strong>Inbox</strong>{' '}
          (pending approvals, failed runs, expired credentials, invitations, and more),{' '}
          <strong>Triggers</strong> (your live automations and when they run next),{' '}
          <strong>Activity</strong> (recently edited items), and <strong>Shared</strong> (your shared
          links). See <a href="/notifications">Notifications</a>.
        </p>
        <p>
          To generate media from a conversation, turn on <strong>Generation</strong> in the Options tab.
          For a dedicated space, switch to <strong>Studio</strong>, or use the{' '}
          <strong>Generate an image, video or sound</strong> button in the side-panel chat. On a
          self-hosted instance, generation stays unavailable until an administrator enables it.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common chat problems and what they mean"
          head={['What you see', 'What it means']}
          rowHeaders
          rows={[
            [
              'An insufficient-credits notice when you send',
              'Your balance cannot cover the request. Add credits, or on the Free plan pick a model marked Free.',
            ],
            [
              'The assistant stops partway through a long task',
              'Credits are also checked before each step, so a turn can end when the balance or a budget runs out. It can also hit Timeout, Inactivity timeout, Max iterations, or an advanced limit.',
            ],
            [
              'An insufficient-storage notice',
              'The upload or generated file is larger than the server accepts, or your storage quota is full.',
            ],
            ['A file will not attach', 'Check its type against the list above. On LiveContext Cloud, keep it under 10 MB.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            What Orbi builds under the hood: structure, branching, versions.
          </Card>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            The building blocks Orbi can place.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Configure AI workers: model, tools, budget, and memory.
          </Card>
          <Card icon={Sparkles} title="Studio" href="/studio">
            Generate images, video, voice, audio, and music.
          </Card>
          <Card icon={MessagesSquare} title="Chat channels" href="/channels">
            Answer approvals and questions from Telegram, Slack, and more.
          </Card>
          <Card icon={Bell} title="Notifications" href="/notifications">
            The bell, its tabs, and your notification preferences.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
