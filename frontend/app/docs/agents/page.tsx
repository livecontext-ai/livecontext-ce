import { Workflow, ShieldCheck, MessagesSquare, Cpu, BookOpen, KanbanSquare } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Agents',
  description:
    'Create and run AI agents: models and CLI agents, tools and permissions, long-term memory, chat channels, budgets, task delegation, and why an agent stopped.',
  path: '/docs/agents',
});

export default function AgentsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Agents"
        lead="An agent is an AI worker with a role, a model, and a scoped set of tools and resources it may touch. This page shows how to create one, start it, keep it safe when nobody is watching, give it memory and a budget, delegate work to it, and find out why it stopped."
      />

      <DocsProse>
        <h2>What an agent is</h2>
        <p>
          An agent bundles a <strong>System Prompt</strong> (its role), a model, the tools and
          resources it is allowed to use (tables, workflows, interfaces, sub-agents, applications,
          files, skills), its limits, and the ways it can be started (chat, webhook, schedule,
          chat widget). Each time it is started it runs a tool-calling loop: read the request,
          pick a tool, run it, read the result, and repeat until it gives a final answer or hits
          one of its limits.
        </p>
        <p>
          The model can be a regular API model or a <strong>CLI agent</strong> (Claude Code,
          Codex, Gemini CLI, or Mistral Vibe) that runs through a bridge. Which ones you can pick
          depends on what your administrator has set up; see{' '}
          <a href="/models">Models &amp; providers</a>.
        </p>

        <h2>The Agents page</h2>
        <p>
          Open <strong>Agents</strong> in the sidebar. The page has six tabs:
        </p>
        <DocsTable
          caption="Tabs of the Agents page"
          head={['Tab', 'What you do there']}
          rowHeaders
          rows={[
            ['Agents', 'List, search, and delete your agents. Create one with Create Agent, or start from Use a template. Click an agent to edit it.'],
            ['Skills', 'Manage reusable instruction sets and assign them to agents. See Skills.'],
            ['Memory', 'Read, add, correct, pin, deactivate, or delete the long-term facts agents keep for this workspace. See Long-term memory below.'],
            ['Fleet', 'A full-screen canvas of your agents and their sub-agents, tools, and resources. Select an agent to inspect its configuration, recent executions, and each execution’s tool calls. Edit mode lets you disconnect a tool or resource from an agent.'],
            ['Metrics', 'Totals (executions, tokens, average duration, success rate) for all agents and for Orbi, per-agent performance, tool statistics, and daily activity over the last 7, 30, or 90 days.'],
            ['Settings', 'Agent & Orbi defaults: the settings every new conversation starts with. This is the only place to edit them: the Chat defaults link in Settings > Overview opens this tab.'],
          ]}
        />
        <p>
          Delegated tasks are not on this page: they live on the task board, under{' '}
          <strong>Board</strong> &gt; <strong>Tasks</strong>. See <a href="/board">Tasks &amp; board</a>.
        </p>

        <h2>Create an agent</h2>
        <p>
          You can ask the assistant in the chat to create an agent for you, or use the editor.
          The editor has three steps:
        </p>
        <Steps>
          <Step n={1} title="Basic Info">
            <p>Give the agent a name, a description, and a <strong>System Prompt</strong> that states its role.</p>
          </Step>
          <Step n={2} title="Configuration">
            <p>
              Pick the <strong>Model Provider</strong> and <strong>Model Name</strong>, the
              tools and resources it may use, its skills, its <strong>Credit Budget</strong>, and
              its limits. Open <strong>Advanced mode</strong> for temperature, the per-turn loop
              guards, <strong>Generation</strong>, <strong>Mailbox</strong>, and context
              compaction.
            </p>
          </Step>
          <Step n={3} title="Integration">
            <p>
              Optional ways to start and reach the agent: <strong>Reach me outside the app</strong>{' '}
              (a chat channel), <strong>Webhook</strong>, <strong>Schedule</strong>, and{' '}
              <strong>Chat Widget</strong> (embed a chat on your website).
            </p>
          </Step>
        </Steps>
        <Callout variant="info" title="Schedules ask first">
          When the assistant creates an agent with a schedule, or adds a schedule to an existing
          one, it asks for your permission in the chat first (<strong>Let this agent run on a
          schedule?</strong>), because a scheduled agent starts itself and spends credits with
          nobody watching. Removing a schedule does not ask.
        </Callout>

        <h2>How an agent is started</h2>
        <DocsTable
          caption="Ways an agent run can start"
          head={['Started by', 'What happens']}
          rowHeaders
          rows={[
            ['Chat', 'You send a message in a conversation with the agent. One message starts one run.'],
            ['Webhook', 'An external HTTP call hits the agent’s webhook URL. Optionally it sees its previous webhook conversations.'],
            ['Schedule', 'A cron schedule fires with the Scheduled Task message. If tasks are waiting in its inbox, it gets its task list instead.'],
            ['Chat Widget', 'A visitor chats with the agent on your website.'],
            ['Workflow', 'An AI Agent node in a workflow runs the agent as a step.'],
            ['Another agent', 'A parent agent calls it as a sub-agent with execute. Its task inbox is added to the prompt.'],
            ['A task', 'A task is assigned to it with the default start mode, or a task waits for its review.'],
          ]}
        />
        <Callout variant="warn" title="Worker agents need a way to wake up">
          A task assigned with <code>start_mode=&apos;pending&apos;</code>, and a task posted to the
          backlog, simply wait. An
          agent only reads its inbox when it is started, so an agent meant to pick up delegated
          work on its own needs a schedule.
        </Callout>

        <h2>Model &amp; reasoning effort</h2>
        <p>
          Model provider and model name are both optional. Leave them empty and the agent uses the
          platform default model. An unknown provider and model pair is replaced by the default and
          reported back as <code>model_substituted</code>, so saving does not fail on a model that
          was removed. Saving an agent on a CLI agent you are not allowed to use is refused.
        </p>
        <p>
          <strong>Temperature</strong> goes from 0 to 2 (default 0.7): lower is more predictable,
          higher more creative.
        </p>
        <p>
          <strong>Reasoning effort</strong> is an optional setting with six levels:{' '}
          <code>minimal</code>, <code>low</code>, <code>medium</code>, <code>high</code>,{' '}
          <code>xhigh</code>, <code>max</code>. It is a level, not a token budget. Only three
          consumers honor it: <strong>Claude Code</strong>, <strong>Codex</strong>, and the
          direct <strong>Anthropic</strong> API. Every other provider ignores it, and the editor
          hides it for them. <strong>Inherit (model default)</strong> leaves the choice to the
          model’s own default.
        </p>
        <DocsTable
          caption="How each consumer applies the reasoning effort"
          head={['Consumer', 'How the level is applied']}
          rowHeaders
          rows={[
            ['Claude Code', 'Accepts low, medium, high, xhigh, and max; minimal is raised to low.'],
            ['Codex', 'Accepts minimal, low, medium, high, and xhigh; max becomes xhigh. On a model that is not a codex-max variant, xhigh and max are lowered to high.'],
            ['Anthropic API', 'Uses the level as is.'],
            ['Everything else', 'Ignored. Gemini CLI and Mistral Vibe have no effort setting.'],
          ]}
        />
        <p>
          When several levels are set, a per-conversation choice wins over the agent’s value,
          which wins over the default an administrator set for the model.
        </p>

        <h2>Limits &amp; timeouts</h2>
        <p>
          Four independent limits can stop a run. The editor shows the same defaults the platform
          applies.
        </p>
        <DocsTable
          caption="Run limits and their defaults"
          head={['Limit', 'Default', 'Range', 'What happens']}
          rowHeaders
          rows={[
            ['Max Tokens', '16000', 'Lowered automatically to the model’s own output ceiling', 'The model’s answer for one turn is cut at this many output tokens.'],
            ['Max Iterations', '100', '1 to 1000', 'The run stops with MAX_ITERATIONS after this many tool-call rounds.'],
            ['Execution Timeout (s)', '3600', '10 to 7200', 'The run stops with TIMEOUT when its total time runs out.'],
            ['Inactivity Timeout (s)', '300 (5 minutes)', '10 to 7200, or 0 to turn it off', 'The run stops with INACTIVITY_TIMEOUT when the agent produces nothing at all for this long.'],
          ]}
        />
        <Callout variant="info">
          The inactivity timeout only fires on silence: no text, no thinking, no tool call, no
          tool result for the whole window. An agent that keeps producing activity is never
          stopped by it, however long it runs. <code>TIMEOUT</code> means the agent was working
          and ran out of total time; <code>INACTIVITY_TIMEOUT</code> means it went quiet.
        </Callout>
        <p>Three more guards, under <strong>Advanced mode</strong>, stop a runaway agent:</p>
        <DocsTable
          caption="Runaway guards under Advanced mode"
          head={['Guard', 'Default', 'Range']}
          rowHeaders
          rows={[
            ['Identical-loop stop', '15 identical tool calls, then LOOP_DETECTED', '2 to 100'],
            ['Consecutive-loop stop', '40 tool calls in a row, then LOOP_DETECTED', '4 to 200'],
            ['Max per-resource / turn', '5 calls per turn on each kind of resource (agent, skill, sub-agent, interface, workflow, table)', '1 to 100'],
          ]}
        />
        <p>
          A sub-agent started with <code>execute</code> gets a timeout: the value the calling agent
          passes, otherwise the sub-agent&apos;s own execution timeout, otherwise 600 seconds. Whatever
          the source, it is kept between 10 and 7,200 seconds.
        </p>

        <h2>Tools &amp; scope</h2>
        <p>
          Integration tools (the catalog of external APIs) are chosen with{' '}
          <strong>Available integration tools</strong>:
        </p>
        <DocsTable
          caption="Integration tool choices"
          head={['Choice', 'tools_mode', 'Behavior']}
          rows={[
            ['All integration tools (default)', 'all', 'The agent may call every catalog tool.'],
            ['Custom selection', 'custom', 'Only the tools you pick, up to 30.'],
            ['No integration tools', 'none', 'No catalog tools; the built-in tools (tables, web search, and so on) stay available.'],
            ['No tools at all', 'off', 'Every tool is off, built-in ones included: a pure reasoner. Resource grants are ignored.'],
          ]}
        />
        <p>
          <strong>Resource Access</strong> is a separate choice. A new agent has no access to your
          workflows, tables, interfaces, sub-agents, or applications until you grant it, one
          family at a time: all of them, none, or a custom list. <strong>Web Search</strong> is
          the one capability that starts on.
        </p>
        <p>
          Each family also has a read or write mode. <code>write</code> (the default) allows
          everything; <code>read</code> allows only reading actions, for example{' '}
          <code>get</code>, <code>list</code>, and <code>query_rows</code> on a table, or{' '}
          <code>get</code>, <code>runs</code>, <code>get_run</code>, and <code>wait_run</code> on a
          workflow. <strong>File Access</strong> and <strong>Long-term memory</strong> have
          their own read or write mode too.
        </p>
        <Callout variant="tip">
          When an agent with a custom list creates a new resource of that family, the new
          resource is added to its list automatically, so it can keep using what it just made.
        </Callout>
        <p>Two capabilities are off until you turn them on, because they act on the outside world:</p>
        <DocsTable
          caption="Capabilities that are off by default"
          head={['Setting', 'Default', 'What it allows']}
          rowHeaders
          rows={[
            ['Generation', 'Off', 'Create images, videos, audio, voices, and music. Each asset is charged in credits at the model’s rate. See Studio.'],
            ['Mailbox', 'Off', 'Read and send email on the account’s connected mailbox (IMAP to read, SMTP to send). Mailbox permissions: Full access or Read-only.'],
          ]}
        />
        <p>
          An agent that creates or edits another agent can only switch these on for it when it
          has them itself; otherwise it leaves them off and asks you to enable them.
        </p>

        <h2 id="permissions">Permissions: when an agent asks before acting</h2>
        <p>
          Some actions are sensitive: running a workflow or a catalog tool, calling a sub-agent,
          installing an application, sending or deleting an email, resolving a paused approval,
          putting a workflow live or taking it off, and giving an agent a schedule.
        </p>
        <h3>In the chat</h3>
        <p>
          In a chat with Orbi (the general assistant, no agent selected), a sensitive action shows
          a permission card, for example <strong>Run this action?</strong>,{' '}
          <strong>Send this email?</strong>, or <strong>Put this workflow live?</strong>. The
          action waits on the card: <strong>Authorize</strong> lets it run and the agent
          continues in place, <strong>Decline</strong> stops it. Tick{' '}
          <strong>Don&apos;t ask again in this conversation</strong> to stop being asked for that
          kind of action in this conversation. If you answer after the agent has stopped waiting,
          your answer starts its next turn.
        </p>
        <p>
          A chat with a specific agent does not ask, unless that agent has{' '}
          <strong>Ask my permission before a sensitive action</strong> turned on (see below).
        </p>
        <h3>In runs nobody is watching</h3>
        <p>
          By default, an agent started by a schedule, a webhook, or a task runs its actions without
          asking. To make it ask, open the agent, go to <strong>Integration</strong>, turn on{' '}
          <strong>Reach me outside the app</strong>, and turn on{' '}
          <strong>Ask my permission before a sensitive action</strong>. From then on:
        </p>
        <ul>
          <li>
            The request is sent with approve and reject buttons (links to a
            confirmation page on Microsoft Teams) to the chat channel chosen as the
            agent’s <strong>Destination</strong> (the workspace default if you leave it empty).
            See <a href="/channels">Chat channels</a>.
          </li>
          <li>
            If nobody answers in time, the run ends without doing the action. A later approval
            authorizes that exact action (same tool, same arguments) for the agent’s next run.
            It does not approve other actions of the same kind.
          </li>
          <li>
            The agent does not send the same request twice while one is waiting. Unanswered
            requests expire after 24 hours, and the agent may then ask again.
          </li>
          <li>
            With no working channel, nothing is sent, and the action is not done. The editor warns
            you: <strong>This agent asks permission before sensitive actions, but has no channel
            to ask on</strong>.
          </li>
        </ul>
        <Callout variant="warn" title="Two cases never ask">
          An agent running inside a workflow’s AI Agent node, and an agent called as a sub-agent
          by another agent, never ask, even with the setting on. Put a User Approval node in the
          workflow when a step needs a human decision.
        </Callout>
        <p>
          Turning <strong>Reach me outside the app</strong> off also turns off the permission
          setting: the agent’s questions are then answered by assumption, and sensitive actions
          in unattended runs are not done.
        </p>

        <h2 id="questions">Questions the agent asks you</h2>
        <p>
          With the <code>ask_user</code> tool, an agent can put one to four multiple-choice
          questions to you (two to four options each). You can always type your own answer
          instead of picking one.
        </p>
        <DocsTable
          caption="What happens to a question depending on where the agent runs"
          head={['Where the agent runs', 'What happens']}
          rowHeaders
          rows={[
            ['A chat you are in', 'A question card appears, one question at a time. The agent waits up to about 4 minutes (about 2.5 minutes on Claude Code, Codex, and Gemini CLI). If you answer later, your answer starts its next turn. Skip lets it continue without an answer.'],
            ['Schedule, webhook, or task', 'The question is sent to the agent’s chat channel, and the agent is told where it went. Your answer reaches its next run. Unanswered questions expire after 6 hours.'],
            ['No channel connected', 'The agent is told nobody can answer, decides with what it has, and states its assumption.'],
            ['Workflow node', 'The agent is told nobody can answer, at once.'],
            ['Sub-agent', 'The question goes back to the agent that called it.'],
          ]}
        />

        <h2 id="memory">Long-term memory</h2>
        <p>
          Long-term memory holds durable facts that agents keep between conversations and runs:
          your preferences, corrections, project decisions, useful references. It is different
          from conversation history and from skills: a skill says how to do something, a memory
          says what is true here.
        </p>
        <ul>
          <li>
            Each entry has a <strong>Title</strong>, a one-line <strong>Summary</strong>, optional{' '}
            <strong>Details</strong>, and a <strong>Type</strong> (About the user, Feedback,
            Project, Reference).
          </li>
          <li>
            The summaries of active entries are added to every agent’s context in the workspace,
            on every run. The details are read only when an agent needs them. An entry marked{' '}
            <strong>Always in context</strong> (pinned) sends its full details every time, so
            keep that for rules that must never be missed.
          </li>
          <li>
            Memory belongs to the workspace: switch workspace and you see that workspace’s memory.
            An entry can also be private to one agent (<strong>One agent</strong>); other agents do
            not see it, but workspace members still do on the <strong>Memory</strong> tab.
          </li>
          <li>
            Agents save entries as they work. You can add, edit, or delete one on the{' '}
            <strong>Memory</strong> tab. <strong>Deactivate</strong> makes agents stop using an
            entry while keeping it; an agent saving the same fact again does not reactivate it.
          </li>
          <li>
            Set an agent’s <strong>Long-term memory</strong> to <strong>Recall only</strong> for
            an agent that should use the workspace facts without changing them.
          </li>
        </ul>
        <DocsTable
          caption="Long-term memory limits"
          head={['Memory limit', 'Default']}
          rowHeaders
          rows={[
            ['Summary length', '240 characters'],
            ['Details length', '8000 characters'],
            ['Entries per workspace', '200 (deactivated entries count)'],
            ['Entries private to one agent', '50'],
            ['Summaries added to context', 'Up to 40'],
            ['Pinned entries added in full', 'Up to 3'],
          ]}
        />
        <Callout variant="warn">
          Do not store secrets in memory. Every agent in the workspace reads the summaries, and
          viewers of the workspace can read the entries.
        </Callout>

        <h2>Context compaction</h2>
        <p>
          Compaction replaces the oldest part of a long conversation with a summary so it keeps
          fitting in the model’s context window. Three settings under{' '}
          <strong>Advanced mode</strong> control it, and each can inherit the default:
        </p>
        <DocsTable
          caption="Context compaction settings"
          head={['Setting', 'Meaning']}
          rowHeaders
          rows={[
            ['Context compaction', 'On, off, or inherit (the conversation’s setting, then the platform default).'],
            ['Compact after N turns', 'The minimum number of new turns between two summaries. It applies when the platform decides by turn count, which is the default.'],
            ['Summariser model', 'The model that writes the summary. Empty means the agent’s own model.'],
          ]}
        />

        <h2 id="budgets">Credit budgets</h2>
        <p>
          <strong>Credit Budget</strong> caps what an agent may spend. Empty means unlimited.
          Credits are the real metered cost of each model call (priced per model and per token), not a
          fixed amount per iteration.
        </p>
        <p>
          An agent that is already over its budget is refused before it starts, on every way of
          starting it (schedule, webhook, widget, workflow node), and a refused scheduled run does
          not count toward the schedule’s maximum number of runs.
        </p>
        <p>
          <strong>Budgets cascade.</strong> When agent A calls sub-agent B, B’s whole budget is
          reserved from A and from every agent above A. A parent with a budget of 100 can never
          let its whole tree spend more than 100. A child that needs 50 credits cannot start under
          a parent with only 30 free: it stops with <code>BUDGET_EXHAUSTED</code>, scope{' '}
          <code>parent_reservation</code>. When the child finishes, what it really spent is
          charged up the chain and the rest of the reservation is released.
        </p>
        <DocsTable
          caption="Credit budget fields"
          head={['Field', 'Meaning']}
          rowHeaders
          rows={[
            [<code key="f1">credits_consumed</code>, 'Total spent by the agent, its sub-agents included.'],
            [<code key="f2">credits_consumed_from_subagents</code>, 'The part of the total spent by its sub-agents.'],
            [<code key="f3">credits_reserved</code>, 'Currently reserved for sub-agents still running.'],
            [<code key="f4">credits_free</code>, 'Budget minus consumed minus reserved; empty when the budget is unlimited.'],
          ]}
        />
        <p>
          <strong>Reset Mode</strong> decides when the count starts again. With{' '}
          <strong>Cumulative</strong> (the default) it never resets on its own; with{' '}
          <strong>Weekly</strong> it resets 7 days after the last reset; with <strong>Monthly</strong>{' '}
          it resets when the calendar month changes (in UTC). <strong>Reset Credits</strong> in the editor
          restarts it by hand at any time.
        </p>
        <Callout variant="tip">
          Set a budget on every agent that runs unattended, with room for a full run: a budget at or
          below the agent’s max iterations will likely stop it early, since one model call usually
          costs more than one credit. When the assistant creates or updates an
          agent for you, it warns you about such a budget.
        </Callout>

        <h2 id="task-delegation">Task delegation</h2>
        <p>
          Agents can hand work to other agents, or to a shared backlog, as trackable tasks. You see
          and manage the same tasks on the task board (<a href="/board">Tasks &amp; board</a>).
        </p>
        <DocsTable
          caption="Task start modes"
          head={['start_mode', 'Behavior']}
          rows={[
            ['execute (default)', 'Starts the assignee now and waits for the outcome. With a reviewer agent, it also waits for the review.'],
            ['in_progress', 'Starts the assignee now and returns at once; the task runs in the background.'],
            ['pending', 'Only creates the task. It waits until the assignee is started and picks it up.'],
          ]}
        />
        <p>
          A task with no assignee goes to the <strong>backlog</strong> and is always pending.
          Only agents with <strong>Shared backlog participation</strong> on (off by default) and a
          way to wake up are offered backlog tasks and may claim them.
        </p>
        <p>Every task follows the same lifecycle:</p>
        <CodeBlock language="text">{`pending -> in_progress -> in_review -> completed | failed | cancelled`}</CodeBlock>
        <p>
          A finished or rejected task always goes through <code>in_review</code>. With a{' '}
          <code>reviewer_agent_id</code>, that agent reviews it. Without one, you are the reviewer,
          and the task waits in review until you act (that is expected, not stuck). After{' '}
          <code>max_review_attempts</code> rejections (1 to 20, default 3), the task fails; it is
          never approved automatically.
        </p>
        <DocsTable
          caption="Task roles and their actions"
          head={['Role', 'Actions']}
          rowHeaders
          rows={[
            ['Assignee', 'inbox, task_complete, task_reject'],
            ['Reviewer', 'review_inbox, task_approve, task_reject_review'],
            ['Creator', 'outbox, task_update, task_cancel, task_delete'],
            ['Anyone with access', 'task_get_context, task_get_execution'],
          ]}
        />
        <p>
          For recurring work, <code>recurrence_create</code> makes a cron template that creates a
          fresh task on each tick, without flooding missed ticks, managed with{' '}
          <code>recurrence_list</code>, <code>recurrence_update</code>, and{' '}
          <code>recurrence_delete</code>. A recurrence without a target agent posts to the backlog.
        </p>
        <p>
          Guards: at most 5 <code>assign</code> calls per turn and a delegation depth of 5. Calls to sub-agents with <code>execute</code> are
          capped by <strong>Max per-resource / turn</strong> (5 by default). A sub-agent sees its
          last 20 messages by default, plus a list of the tasks in its inbox.
        </p>

        <h2>Skills</h2>
        <p>
          Skills are reusable instruction sets. An agent can have up to 10. Setting an agent’s
          skills from the chat replaces its whole list; use{' '}
          <code>skill(action=&apos;assign&apos;)</code> to add one without replacing the others.
          Skill access is <code>write</code> (default) or <code>read</code> (<code>get</code>,{' '}
          <code>list</code>, <code>list_folders</code>, <code>help</code>). See{' '}
          <a href="/skills">Skills</a>.
        </p>

        <h2>Built-in tool modules</h2>
        <p>
          Besides catalog tools, an agent can use built-in tools, each described to it in one line
          of its system prompt. A module is present only when the agent has access to it.
        </p>
        <DocsTable
          caption="Built-in tool modules"
          head={['Module', 'What it does']}
          rowHeaders
          rows={[
            ['catalog', 'Search and call external APIs (Gmail, Slack, and the rest).'],
            ['table', 'Tables: rows, columns, filters, and vector columns for similarity search.'],
            ['interface', 'HTML pages: forms, dashboards, multi-page apps.'],
            ['agent', 'Configure and run sub-agents; manage tasks.'],
            ['skill', 'Reusable instruction sets assigned to agents.'],
            ['memory', 'Long-term facts: save, get, list, search, delete.'],
            ['workflow', 'Build, run, inspect, and stop workflows.'],
            ['application', 'Find, install, run, and publish marketplace applications.'],
            ['web_search', 'Search the web, read a page, or drive a browser (see Browser Agent).'],
            ['generation', 'Create an image, video, audio, voice, or music asset. Off unless Generation is on.'],
            ['files', 'Browse and reuse workspace files.'],
            ['mailbox', 'Read and send email on the connected mailbox. Off unless Mailbox is on.'],
            ['wait', 'Pause 1 to 240 seconds between status checks instead of polling.'],
            ['ask_user', 'Ask you a multiple-choice question (see Questions the agent asks you).'],
            ['channel', 'Connect and manage the workspace’s chat channels (see Chat channels).'],
          ]}
        />
        <p>
          When a tool call fails with a 401 or 403, the agent uses the <code>credential</code>{' '}
          tool (actions <code>require</code>, <code>list</code>, <code>variables</code>,{' '}
          <code>set_variable</code>). <code>require</code> shows you a card to connect or
          reconnect the service, with the missing scopes when the provider named them.{' '}
          <code>set_variable</code> writes a workflow variable that later steps read with{' '}
          <code>{'{{$vars.name}}'}</code>.
        </p>
        <p>
          To wait for a workflow run, <code>workflow(action=&apos;wait_run&apos;)</code> blocks
          until the run finishes, for up to 120 seconds by default and 240 at most. On timeout it
          returns <code>timed_out=true</code> and the run keeps going; the agent calls it again.
        </p>

        <h2>Related AI nodes</h2>
        <p>
          <strong>Guardrail</strong> and <strong>Classify</strong> are AI nodes you place directly
          in a workflow.
        </p>
        <DocsTable
          caption="AI nodes you place in a workflow"
          head={['Node', 'Purpose', 'Key outputs']}
          rows={[
            ['Guardrail', 'Checks content against safety rules.', <><code key="g1">passed</code>, <code key="g2">violations</code>, <code key="g3">details</code>, <code key="g4">sanitized</code>, <code key="g5">tokens_used</code>, <code key="g6">model</code>, <code key="g7">provider</code></>],
            ['Classify', 'Puts the input in one of your categories and routes to its branch.', <><code key="c1">selected_category</code>, <code key="c2">selected_category_index</code>, <code key="c3">confidence</code>, <code key="c4">reasoning</code>, <code key="c5">probabilities</code> (decision models only), <code key="c6">tokens_used</code></>],
          ]}
        />
        <p>See the <a href="/nodes">Node reference</a> for every parameter and output.</p>

        <h2>Metrics &amp; inspection</h2>
        <p>
          Each agent keeps running totals: executions, tokens, tool calls, successes, failures,
          total duration, and the time of its last run. The <strong>Metrics</strong> tab charts
          them. In the <strong>Fleet</strong> tab, select an agent to open its recent executions,
          and select an execution to see its conversation and every tool call with its arguments
          and result. From the chat, <code>task_get_execution</code> with{' '}
          <code>include_tool_calls=true</code> returns the same tool calls, with secrets redacted.
        </p>

        <h2 id="troubleshooting">Troubleshooting: why did my agent stop?</h2>
        <p>Every run ends with one of ten stop reasons, in three outcomes:</p>
        <DocsTable
          caption="Agent stop reasons"
          head={['Stop reason', 'Shown as', 'Outcome', 'What to do']}
          rows={[
            [<code key="r1">COMPLETED</code>, 'Completed', 'Success', 'Nothing: the agent gave its final answer.'],
            [<code key="r2">MAX_ITERATIONS</code>, 'Iteration limit reached', 'Partial', 'Raise Max Iterations, or narrow the task.'],
            [<code key="r3">TIMEOUT</code>, 'Timed out', 'Partial', 'Raise Execution Timeout (s), or split the work into tasks.'],
            [<code key="r4">BUDGET_EXHAUSTED</code>, 'Credit budget exhausted', 'Partial', 'Read the scope: tenant (your account’s credits), agent (its own Credit Budget), parent_reservation (a parent agent could not reserve the budget), or browser (the browser-agent quota).'],
            [<code key="r5">LOOP_DETECTED</code>, 'Tool loop detected', 'Partial', 'Look at its last tool calls in Fleet; fix the prompt or the failing tool. Adjust the loop guards only if the repetition is intended.'],
            [<code key="r6">STOPPED_BY_USER</code>, 'Stopped by user', 'Partial', 'Someone pressed stop.'],
            [<code key="r7">CANCELLED</code>, 'Cancelled by system', 'Failure', 'The platform cancelled the run (for example during a restart). Run it again.'],
            [<code key="r8">NO_TOOLS</code>, 'No tools available', 'Failure', 'Check Available integration tools and Resource Access.'],
            [<code key="r9">ERROR</code>, 'Execution error', 'Failure', 'Usually the model provider failed. Check the execution in Fleet, then retry or change model.'],
            [<code key="r10">INACTIVITY_TIMEOUT</code>, 'Stopped (inactivity)', 'Failure', 'The agent went silent, often a slow tool. Raise Inactivity Timeout (s) or set it to 0.'],
          ]}
        />
        <p>
          A <em>Success</em> or <em>Partial</em> stop leaves usable output; a <em>Failure</em>{' '}
          does not.
        </p>
        <h3>Other common problems</h3>
        <DocsTable
          caption="Other common agent problems"
          head={['Symptom', 'Likely cause']}
          rows={[
            ['A scheduled agent never runs', 'It is over its Credit Budget (refused before starting), or the schedule reached Max executions.'],
            ['An unattended agent never did the sensitive action', 'It asked for permission and nobody approved, or it has no working chat channel. Check Settings > Channels and the agent’s Destination.'],
            ['The agent says nobody can answer its question', 'It ran unattended with no chat channel, or it ran inside a workflow node.'],
            ['Delegated tasks sit in pending', 'The assignee is never started. Give it a schedule, or assign with the default execute mode.'],
            ['A task stays in review', 'No reviewer agent was set, so you are the reviewer. Approve or reject it on the task board.'],
            ['The agent cannot create images or send email', 'Generation or Mailbox is off for that agent.'],
            ['Your saved model changed', 'The model was no longer available and was replaced by the default (model_substituted).'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={MessagesSquare} title="Chat channels" href="/channels">Reach agents’ questions and approvals on Telegram, Slack, and more.</Card>
          <Card icon={Cpu} title="Models & providers" href="/models">API models, CLI agents, and reasoning effort.</Card>
          <Card icon={BookOpen} title="Skills" href="/skills">Reusable instructions for your agents.</Card>
          <Card icon={KanbanSquare} title="Tasks & board" href="/board">Follow and review delegated work.</Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">Run an agent as a workflow step.</Card>
          <Card icon={ShieldCheck} title="Node reference" href="/nodes">AI Agent, Guardrail, Classify, and User Approval.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
