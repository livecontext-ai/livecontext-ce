import { Workflow, LayoutPanelLeft, ShieldCheck, Table2, Plug } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Agents',
  description:
    'Agents in LiveContext: scoped tool access, model and reasoning-effort selection, limits and timeouts, cascading credit budgets, the ten stop reasons, task delegation, and built-in metrics.',
  path: '/docs/agents',
});

export default function AgentsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Agents"
        lead="An agent is an AI step with a role, a model, and a scoped set of tools and resources it's allowed to touch. On activation it runs a tool-calling loop: read the prompt, decide which tool to call, execute it, read the result, iterate, until it produces a final answer or hits one of its limits."
      />

      <DocsProse>
        <h2>What an agent is</h2>
        <p>
          An agent bundles a <code>system_prompt</code> (its role), a model (provider, name,
          temperature), a set of allowed MCP/catalog tools, allowed resources (tables, workflows,
          interfaces, sub-agents, applications), and optional activation mechanisms (webhook,
          schedule, skills).
        </p>
        <p>Four activation paths run an agent:</p>
        <DocsTable
          head={['Trigger', 'What fires it']}
          rows={[
            ['user_chat', 'A human sends a message in a conversation bound to the agent.'],
            ['webhook', 'An external HTTP call hits the agent’s webhook.'],
            ['schedule_cron', 'A cron schedule fires; the agent also surfaces its task inbox.'],
            ['parent_execute', 'Another agent calls it as a sub-agent; the task inbox is surfaced too.'],
          ]}
        />
        <p>
          Each human message or webhook call fires exactly one loop. The task inbox is
          auto-surfaced only on <code>schedule_cron</code> and <code>parent_execute</code>, so a
          worker agent that should process delegated tasks needs a schedule (see{' '}
          <a href="#task-delegation">task delegation</a> below).
        </p>

        <h2>Model &amp; reasoning effort</h2>
        <p>
          Each agent runs a model you choose (provider + model). Both are optional on create and
          update: omit them and the agent uses the platform default (the first model of the first
          configured provider). An unknown provider/model pair is silently substituted with the
          default and reported back as <code>model_substituted</code>, so a create/update never
          hard-fails on a stale model reference.
        </p>
        <ul>
          <li><strong>Temperature</strong>: 0.0-2.0, default 0.7. Lower is more deterministic, higher more creative.</li>
        </ul>
        <p>
          <strong>Reasoning effort</strong> is a separate, optional dial with <strong>six</strong>{' '}
          levels: <code>minimal</code>, <code>low</code>, <code>medium</code>, <code>high</code>,{' '}
          <code>xhigh</code>, <code>max</code>. It is <strong>not</strong> a token budget: it
          carries no per-level thinking-token numbers. It's a categorical intent, honored only by
          providers that expose a reasoning-effort knob of their own: <strong>Claude Code</strong>,{' '}
          <strong>Codex</strong>, and the <strong>direct Anthropic API</strong>. Every other
          provider ignores the setting entirely.
        </p>
        <DocsTable
          head={['Consumer', 'How it maps']}
          rows={[
            ['Claude Code', 'Sets CLAUDE_CODE_EFFORT_LEVEL. Accepts low/medium/high/xhigh/max - minimal clamps up to low.'],
            ['Codex', 'Sets -c model_reasoning_effort. Accepts minimal/low/medium/high/xhigh - there’s no max, so max maps to xhigh; xhigh/max also clamp down on models that aren’t a codex-max variant.'],
            ['Anthropic API', 'Sets output_config.effort directly, one of the six levels.'],
            ['Everything else', 'Ignored. Gemini CLI and Mistral Vibe have no headless effort knob and are deliberately excluded.'],
          ]}
        />
        <p>
          Precedence when several levels are set: a per-conversation override beats the per-agent
          value, which beats the model's own default.
        </p>

        <h2>Limits &amp; timeouts</h2>
        <p>
          Four independent limits can stop an agent. Note that the builder UI shows lower numbers
          than the platform actually defaults to: it displays 4096 max tokens and 10 max
          iterations, but the backend defaults are 16000 and 100 respectively.
        </p>
        <DocsTable
          head={['Limit', 'Default', 'Range', 'What happens']}
          rows={[
            ['Max tokens per turn', '16000', 'positive; clamped to the model’s real output ceiling (8192 fallback if unknown)', 'The model is cut off at this many output tokens for a single turn.'],
            ['Max iterations', '100', '1-1000', 'Stops after this many tool-call rounds with MAX_ITERATIONS.'],
            ['Execution timeout', '3600s', '10-7200s', 'Total wall-clock budget for the run; stops with TIMEOUT if exceeded while still active.'],
            ['Inactivity timeout', '5 min (NULL = default; 0 = disabled)', '10-7200s, or 0', 'Stops with INACTIVITY_TIMEOUT if the agent emits no activity at all for this long.'],
          ]}
        />
        <Callout variant="info">
          The inactivity timeout is a separate watchdog from the execution timeout, not a
          duplicate of it. It only fires on <strong>silence</strong>: no content token, no thinking
          token, no tool call, no tool result for the whole window. A streaming agent that keeps
          producing activity resets the idle clock on every event and is never stopped by it, no
          matter how long the run has been going overall. <code>TIMEOUT</code> means the agent was
          actively working but ran past its total wall-clock budget; <code>INACTIVITY_TIMEOUT</code>{' '}
          means it went quiet.
        </Callout>
        <p>Two more guards protect against runaway or misbehaving agents:</p>
        <ul>
          <li>
            <strong>Loop detector</strong>: stops with <code>LOOP_DETECTED</code> after 15
            identical tool calls or 40 consecutive tool calls (both overridable per agent).
          </li>
          <li>
            <strong>Max per resource per turn</strong>: a uniform cap of 5 calls per turn against
            each tracked resource type (agent, skill, sub_agent, interface, workflow, table),
            overridable per agent.
          </li>
        </ul>
        <p>
          A sub-agent invoked via <code>execute</code> has its own timeout parameter too, 10-300s,
          default 120s.
        </p>

        <h2>Tools &amp; scope</h2>
        <p>
          Tool access is a deny-by-default safety boundary, stored as <code>tools_mode</code>:
        </p>
        <DocsTable
          head={['tools_mode', 'Behavior']}
          rows={[
            ['all (default)', 'The agent may call every MCP/catalog tool.'],
            ['custom', 'Restricted to a specific list of catalog tool IDs (max 30).'],
            ['none', 'No MCP/catalog tools, but internal tools stay enabled.'],
            ['off', 'Every tool is disabled, MCP and internal alike, for a pure reasoner.'],
          ]}
        />
        <p>
          <strong>Resource access is a separate axis</strong> from <code>tools_mode</code>. On
          create, every resource list (workflows, tables, interfaces, agents, applications)
          defaults to <code>[]</code>, no access, and must be granted explicitly one family at a
          time; <code>web_search</code> is the one resource that defaults to enabled. The grant
          convention per family is: <code>all</code> (key omitted, unrestricted), <code>none</code>{' '}
          (<code>[]</code>, explicit no access), or <code>custom</code> (an explicit ID list).
          Unrestricted access is expressible only through the grant parameter, never as a literal
          wildcard in the ID list.
        </p>
        <p>
          Each resource family also has its own <strong>read/write access mode</strong>:{' '}
          <code>write</code> (default, full CRUD) or <code>read</code> (read actions always pass;
          any write action is denied). The read-action set is fixed per category, for example a
          table's read set is <code>get</code>/<code>list</code>/<code>query_rows</code>/
          <code>help</code>, and a workflow's read set includes <code>load</code>/<code>get</code>/
          <code>list</code>/<code>describe</code>/<code>validate</code>/<code>runs</code>/
          <code>get_run</code>/<code>read_rows</code>/<code>find_rows</code>.
        </p>
        <Callout variant="tip">
          When an agent creates a resource of its own and its allow-list for that category is
          restricted, the new resource's ID is appended automatically so the agent can keep using
          what it just created. If that family is unrestricted, this is a no-op.
        </Callout>
        <p>
          An optional <code>require_tool_authorization</code> flag exists as an extension seam for
          a synchronous tool-authorization gate. Today it applies only to the interactive shared
          chat with no bound agent; agent-backed chats, workflow/task/sub-agent runs, and headless
          contexts are exempt.
        </p>

        <h2>Context compaction</h2>
        <p>
          Compaction is the cold-summary pass that shrinks a long conversation to stay inside the
          model's context window. Two per-agent settings control it, both defaulting to inherit:
        </p>
        <DocsTable
          head={['Setting', 'Meaning']}
          rows={[
            ['compaction_enabled', 'true = always compact this agent’s conversations, false = never, NULL = inherit the per-conversation override, then the platform default.'],
            ['compaction_after_turns', 'Minimum new turns between summary regenerations (integer ≥ 1); NULL = inherit the per-conversation override, then the platform cadence.'],
          ]}
        />
        <p>
          The model that performs the summary can also be overridden per agent (
          <code>compaction_model_provider</code>/<code>compaction_model_name</code>); leaving
          either NULL falls back to the agent's own primary model, and the platform-wide fallback
          for the summarizer is Anthropic's <code>claude-haiku-4-5</code>.
        </p>

        <h2>Credit budgets</h2>
        <p>
          An agent's optional <code>credit_budget</code> is NULL by default (unlimited) or a
          number that caps its spend. One credit is roughly one iteration of the tool-calling loop.
        </p>
        <p>
          <strong>Budgets cascade.</strong> When agent A calls <code>execute</code> on sub-agent B,
          B's full budget is atomically reserved from A and from every ancestor above A, so a
          parent with a budget of 100 can never let its whole descendant tree spend more than 100
          combined. A child that needs 50 credits cannot start under a parent that only has 30
          free, and that produces <code>BUDGET_EXHAUSTED</code> with scope{' '}
          <code>parent_reservation</code>.
        </p>
        <p>
          When a child finishes, its actual consumption is settled up the chain and any unused
          reservation is refunded. You can see, for any agent, a full breakdown:
        </p>
        <DocsTable
          head={['Field', 'Meaning']}
          rows={[
            ['credits_consumed', 'Total spent by this agent, including its descendants.'],
            ['credits_consumed_from_subagents', 'The subset of the above spent by descendants.'],
            ['credits_reserved', 'Currently reserved for in-flight descendants.'],
            ['credits_free', 'budget − consumed − reserved (null when the budget is unlimited).'],
          ]}
        />
        <p>
          Subtract <code>credits_consumed_from_subagents</code> from <code>credits_consumed</code>{' '}
          to get what the agent spent on its own work. <code>budget_reset_mode</code> defaults to{' '}
          <code>cumulative</code>: the budget never resets on its own.
        </p>
        <Callout variant="tip">
          Set an explicit budget on every production agent, at least 5x its max iterations.
          Create/update returns a warning when <code>credit_budget</code> is at or below{' '}
          <code>max_iterations</code>, since that leaves too little room for a real run.
        </Callout>

        <h2>Why an agent stopped</h2>
        <p>Every execution ends with one of ten stop reasons, grouped into three outcomes:</p>
        <DocsTable
          head={['Stop reason', 'Outcome', 'Meaning']}
          rows={[
            [<code key="r1">COMPLETED</code>, 'Success', 'The model produced a final response with no further tool calls.'],
            [<code key="r2">MAX_ITERATIONS</code>, 'Partial (usable)', 'Reached the configured iteration limit.'],
            [<code key="r3">TIMEOUT</code>, 'Partial', 'Exceeded the configured wall-clock execution timeout while still active.'],
            [<code key="r4">BUDGET_EXHAUSTED</code>, 'Partial', 'Credit budget exhausted (scope: tenant, agent, parent_reservation, or browser).'],
            [<code key="r5">LOOP_DETECTED</code>, 'Partial', 'The loop detector found too many identical or consecutive tool calls.'],
            [<code key="r6">STOPPED_BY_USER</code>, 'Partial', 'A human explicitly cancelled the run.'],
            [<code key="r7">CANCELLED</code>, 'Failure', 'The system cancelled the run (deploy, scale-down, supervisor).'],
            [<code key="r8">NO_TOOLS</code>, 'Failure', 'Tool discovery returned an empty set.'],
            [<code key="r9">ERROR</code>, 'Failure', 'An unrecoverable execution error.'],
            [<code key="r10">INACTIVITY_TIMEOUT</code>, 'Failure', 'Force-stopped by the inactivity watchdog after silence for the configured window.'],
          ]}
        />
        <p>
          A <em>Success</em> or <em>Partial</em> stop leaves usable output; the agent did real work
          before stopping. A <em>Failure</em> does not. When you hit{' '}
          <code>BUDGET_EXHAUSTED</code>, the scope tells you which limit actually bit: the tenant
          pool, this agent's own budget, an ancestor refusing to reserve for a sub-agent, or the
          concurrent-browser quota. And remember the distinction between the two time-based
          failures: <code>TIMEOUT</code> is "still working, ran out of wall-clock budget";{' '}
          <code>INACTIVITY_TIMEOUT</code> is "went silent for too long".
        </p>

        <h2 id="task-delegation">Task delegation</h2>
        <p>
          Agents can hand off work to other agents (or to a shared backlog) as trackable tasks,
          synchronously or asynchronously.
        </p>
        <DocsTable
          head={['start_mode', 'Behavior']}
          rows={[
            ['execute (default)', 'Synchronous: dispatch the assignee and wait for a terminal state, returning the result or error inline.'],
            ['in_progress', 'Asynchronous fire-and-forget: returns immediately, the task runs in the background.'],
            ['pending', 'Create the task row only, no dispatch yet.'],
          ]}
        />
        <p>
          Assigning with no <code>agent_id</code> posts to the tenant <strong>backlog</strong>{' '}
          instead of a specific agent. Only agents that both have a schedule and opt in with{' '}
          <code>backlog_enabled=true</code> (default false) can browse and claim backlog tasks.
        </p>
        <Callout variant="warn">
          Assign does not push a notification. The task simply sits in the target's inbox until
          the target is next activated. An agent with no schedule, no chat, and no
          parent-executor will never process its inbox, so give a worker agent a{' '}
          <code>schedule_cron</code> if it's meant to pick up delegated work.
        </Callout>
        <p>Every task moves through a fixed lifecycle:</p>
        <CodeBlock language="text">{`pending -> in_progress -> in_review -> completed | failed | cancelled`}</CodeBlock>
        <p>
          Every task passes through <code>in_review</code> after <code>task_complete</code> or{' '}
          <code>task_reject</code>; that step is never skipped. If <code>reviewer_agent_id</code>{' '}
          is set, that agent reviews on its own activation trigger; if it's NULL, the human tenant
          owner is the implicit reviewer and the task simply rests in <code>in_review</code> until
          they act, which is expected, not stuck. The reviewer loop is capped by{' '}
          <code>max_review_attempts</code> (1-20, default 3); once hit, the task is automatically
          failed, not automatically approved.
        </p>
        <DocsTable
          head={['Role', 'Verbs']}
          rows={[
            ['Assignee', 'inbox, task_complete, task_reject'],
            ['Reviewer', 'review_inbox, task_approve, task_reject_review'],
            ['Creator', 'outbox, task_update, task_cancel, task_delete'],
            ['Anyone with access', 'task_get_context, task_get_execution'],
          ]}
        />
        <p>
          For recurring work, <code>recurrence_create</code> makes a cron-driven template that
          instantiates a fresh trackable task on each tick (fire-once-then-skip, no backfill
          flood), managed with <code>recurrence_list</code>/<code>update</code>/<code>delete</code>.
          A recurrence with no <code>target_agent_id</code> posts to the backlog.
        </p>
        <p>
          Rate limits keep delegation in check: at most 5 <code>assign</code> calls and 3{' '}
          <code>execute</code> calls per conversation turn, <code>update</code> soft-capped at 3
          per session, and a maximum delegation depth of 5. <code>execute</code> itself runs a
          sub-agent synchronously and inlines its response (with memory of the last 20
          user/assistant messages by default), auto-augmenting the sub-agent's prompt with a
          "Tasks in your inbox" section.
        </p>

        <h2>Skills</h2>
        <p>
          <code>skill_ids</code> attaches reusable instruction modules to an agent, up to 10.
          Passing this list on an update <strong>replaces</strong> the full set; use{' '}
          <code>skill(action=&apos;assign&apos;)</code> instead to add without replacing. Skill
          access itself follows the same read/write convention as other resources:{' '}
          <code>write</code> (default) or <code>read</code> (get/list only). See{' '}
          <a href="/skills">Skills</a>.
        </p>

        <h2>Built-in tool modules</h2>
        <p>
          The default system prompt is modular: a foundation intro, core rules, and a tool-routing
          table assembled from whichever resource modules are enabled. Each module below is a
          one-line routing entry; the agent always sees the underlying tool names regardless of
          which modules are described.
        </p>
        <DocsTable
          head={['Module', 'What it routes to']}
          rows={[
            ['catalog', 'Search and execute external APIs (Gmail, Slack, and the rest); start with search when there’s no known tool_id.'],
            ['table', 'Persistent DB tables: CRUD on rows and columns, filtering, 15 column types including vector for similarity search (RAG).'],
            ['interface', 'HTML pages (forms, dashboards, multi-page apps); a workflow node can emit a screenshot or a PDF of the rendered page.'],
            ['agent', 'Configure and execute sub-agents with an isolated context; also manages tasks (assign, inbox, outbox, task_complete).'],
            ['skill', 'Reusable instruction sets assigned to agents.'],
            ['workflow', 'The multi-step automation builder (stateful: init/load before add_node); also inspects and executes runs.'],
            ['application', 'The marketplace as a toolbox: a published app can add a missing capability; check here before reaching for web_search/catalog/workflow(init). Also publishes apps.'],
            ['web_search', 'Search the web, fetch a page as markdown, or drive an LLM browser.'],
            ['image_generation', 'Generate images from a text prompt; bills per image returned.'],
            ['files', 'Browse and reuse workspace files (docs, images, exports, uploads); get/view returns a ref usable by a workflow node.'],
            ['wait', 'Block server-side for a set number of seconds (1-240) between status checks or after a rate limit, instead of busy-polling.'],
          ]}
        />
        <p>
          Credential handling for a tool call that hits a 401/403 goes through a single unified{' '}
          <code>credential</code> tool, with actions <code>require</code>, <code>list</code>,{' '}
          <code>variables</code>, <code>set_variable</code>, and <code>help</code>. An agent asks
          for a credential with <code>credential(action=&apos;require&apos;)</code>; if the
          credential already exists and is recent it tries a different scope first, and only
          forces re-authorization if the existing token was actually revoked. The{' '}
          <code>set_variable</code> action is also how workflow-level <code>$vars</code> get
          written at runtime, so an agent can persist a value for later steps to read back with{' '}
          <code>{'{{$vars.name}}'}</code>.
        </p>
        <p>
          To wait for an entire workflow run to finish rather than a fixed number of seconds,{' '}
          <code>workflow(action=&apos;wait_run&apos;, run_id=...)</code> blocks for up to about
          300 seconds until the run reaches a terminal state, which is generally the better choice
          than a plain <code>wait</code> loop when you know the run ID.
        </p>

        <h2>Related AI nodes</h2>
        <p>
          <strong>Guardrail</strong> and <strong>Classify</strong> are specialized agent-category
          nodes that plug straight into a workflow graph.
        </p>
        <DocsTable
          head={['Node', 'Purpose', 'Key outputs']}
          rows={[
            ['Guardrail', 'Validates input or output against safety rules.', <><code key="g">passed</code> (boolean pass/fail), <code key="g2">violations</code>, <code key="g3">details</code>, <code key="g4">sanitized</code>, <code key="g5">tokens_used</code>, <code key="g6">duration_ms</code></>],
            ['Classify', 'Classifies input into one predefined category using AI.', <><code key="c">selected_category</code> (alias category), <code key="c2">selected_category_index</code> (drives port/branch routing), <code key="c3">confidence</code>, <code key="c4">reasoning</code>, <code key="c5">tokens_used</code></>],
          ]}
        />
        <p>See the <a href="/nodes">Node reference</a> for the full parameter and output list of both.</p>

        <h2>Metrics &amp; audit</h2>
        <p>
          Every run is recorded on the agent: total executions, total tokens used, total tool
          calls, success count, failure count, total duration, and the last execution timestamp
          (divide total duration by total executions for the average). That gives you a running
          audit trail to watch behavior over time and catch a regression early.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">How agent nodes sit in the graph.</Card>
          <Card icon={ShieldCheck} title="Node reference" href="/nodes">Guardrail, Classify, and the rest.</Card>
          <Card icon={LayoutPanelLeft} title="Interfaces" href="/interfaces">Let agents drive or hand off to a UI.</Card>
          <Card icon={Table2} title="Tables & data" href="/tables">Give an agent a table as scoped memory.</Card>
          <Card icon={Plug} title="Integrations" href="/integrations">The catalog tools an agent can call.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
