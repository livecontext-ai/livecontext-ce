import { Globe, Bot, MessagesSquare } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Browser Agent',
  description:
    'The Browser Agent drives a real Chromium browser step by step with an LLM: workflow node and chat tool, live view with takeover, domain allow/deny lists, structured extraction, nine stop reasons, and per-user budgets.',
  path: '/docs/browser-agent',
});

export default function BrowserAgentPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Browser Agent"
        lead="Give an LLM a task and a browser. The Browser Agent navigates real pages step by step, clicking, typing, and reading as it goes, then hands back a summary and (optionally) structured data. It's available both as a workflow node and as a chat tool, with a live view you can watch and, when needed, take over."
      />

      <DocsProse>
        <h2>Two ways to run it</h2>
        <p>
          The Browser Agent is the same engine reached from two surfaces:
        </p>
        <ul>
          <li>
            <strong>Workflow node</strong> (<code>Browser Agent</code>, category Agent) - runs exactly
            one browsing session per execution, as a step in a graph. See{' '}
            <a href="/workflows">Workflows</a> for how nodes connect.
          </li>
          <li>
            <strong>Chat tool</strong> - a chat agent can call <code>agent_browse</code> directly, and
            then check on, steer, or stop that session with <code>browse_status</code>,{' '}
            <code>browse_intervene</code>, <code>browse_abort</code>, and <code>browse_screenshot</code>.
          </li>
        </ul>
        <p>
          Both paths open a real headless Chromium browser and stream a live view of what it's doing.
          Sessions are capped: at most one active session per host at a time, plus per-user budgets
          covering concurrency and daily steps (see <a href="#budgets">Budgets &amp; concurrency</a>{' '}
          below).
        </p>

        <h2>Node configuration</h2>
        <p>
          On the node, only <strong>task</strong> is required. Everything else lives under an optional
          drawer:
        </p>
        <DocsTable
          head={['Field', 'Default', 'What it does']}
          rows={[
            [<code key="f">task</code>, 'required', "The agent's goal. Supports {{templates}}, so you can feed in data from earlier in the workflow."],
            [<code key="f">start_url</code>, 'none', "Where to open first. Optional: if you leave it out, the agent infers and opens a URL from the task itself."],
            [<code key="f">interaction_mode</code>, 'autonomous', 'How much control you keep during the run (see below).'],
            [<code key="f">max_steps</code>, '25', 'Upper bound on the number of browser actions.'],
            [<code key="f">timeout_seconds</code>, '300', 'Wall-clock limit for the whole session.'],
            [<code key="f">screenshot_policy</code>, 'on_change', 'When to capture a screenshot (see below).'],
            [<span key="f"><code>domain_allowlist</code> / <code>domain_denylist</code></span>, 'none', 'Restrict which domains the session may navigate to.'],
          ]}
        />
        <p><code>interaction_mode</code> has three settings:</p>
        <DocsTable
          head={['Mode', 'Behavior']}
          rows={[
            ['autonomous', 'Runs start to finish on its own (the default).'],
            ['supervised', "Runs on its own, but you can inject a hint via browse_intervene between steps."],
            ['manual', 'Starts paused and waits for your first browse_intervene before taking any action.'],
          ]}
        />
        <p><code>screenshot_policy</code> controls what lands in the <code>screenshots</code> output:</p>
        <DocsTable
          head={['Policy', 'Captures']}
          rows={[
            ['every_step', 'A screenshot after every single action.'],
            ['on_change', 'A screenshot whenever the page visibly changes (the default).'],
            ['final_only', 'Just the last screenshot, at the end.'],
            ['off', 'No screenshots at all.'],
          ]}
        />
        <Callout variant="warn">
          <code>max_steps</code> and <code>timeout_seconds</code> set on the <strong>workflow node</strong>{' '}
          don&apos;t reach the browser session, so a node run applies the platform&apos;s own defaults
          (50 steps, 600 seconds) regardless of what the form shows. When calling the browser agent from
          a <strong>chat</strong> agent, <code>max_steps</code> is honored. Either way, the browser
          session hard-caps at 600 seconds and 50 steps no matter what you set, so a session can never
          run away.
        </Callout>

        <h2 id="outputs">Outputs &amp; stop reasons</h2>
        <p>Every session returns:</p>
        <DocsTable
          head={['Field', 'What it holds']}
          rows={[
            [<code key="o">final_result</code>, 'A natural-language summary of what happened, always present.'],
            [<code key="o">extracted_data</code>, 'Structured data if you asked for it (see below), otherwise null.'],
            [<code key="o">stop_reason</code>, 'Why the session ended (see the table below).'],
            [<code key="o">final_url</code>, 'The last page the session was on.'],
            [<code key="o">pages_visited</code>, 'The list of URLs visited, in order.'],
            [<code key="o">steps</code>, 'A per-step trace: action, target, page URL, reasoning, screenshot key, tokens, and duration for each step.'],
            [<code key="o">screenshots</code>, 'Keys for the captured screenshots (per the screenshot policy above).'],
            [<code key="o">cost</code>, 'Tokens in/out, number of LLM calls, browser seconds, and the USD cost.'],
            [<code key="o">session_id</code>, 'The session identifier, used to target browse_status / browse_intervene / browse_abort / browse_screenshot at a still-running session.'],
          ]}
        />
        <p>Reference any of these downstream the usual way, by the node's label:</p>
        <CodeBlock language="text">{`{{agent:price_check.output.final_result}}    → the summary text
{{agent:price_check.output.extracted_data}}  → the structured object you asked for
{{agent:price_check.output.final_url}}       → where the session ended up`}</CodeBlock>

        <p>A session ends with one of nine stop reasons:</p>
        <DocsTable
          head={['Stop reason', 'Meaning']}
          rows={[
            [<code key="s">COMPLETED</code>, 'The agent decided the task was done.'],
            [<code key="s">MAX_STEPS</code>, 'Hit the step cap before finishing.'],
            [<code key="s">TIMEOUT</code>, 'Exceeded the wall-clock limit.'],
            [<code key="s">CANCELLED</code>, 'Stopped via browse_abort at a step boundary.'],
            [<code key="s">BUDGET_EXHAUSTED</code>, "Hit this user's concurrent-session or daily-step limit."],
            [<code key="s">DOMAIN_BLOCKED</code>, 'Tried to navigate somewhere outside the allowed domains, or to an unsafe internal address.'],
            [<code key="s">LLM_FAILED</code>, 'The model configuration failed or the session crashed early.'],
            [<code key="s">USER_TAKEOVER</code>, 'A takeover is in progress: the session is paused and the workflow is waiting for you to resolve it (see below).'],
            [<code key="s">SCHEMA_MISMATCH</code>, 'Reserved for a future check against your requested output schema.'],
          ]}
        />
        <p>
          Any stop reason other than <code>COMPLETED</code> (except <code>USER_TAKEOVER</code>, which
          pauses the run for you to resolve) surfaces as a failed result, using{' '}
          <code>final_result</code> as the error message, so a workflow branch on error catches every
          non-success case in one place.
        </p>
        <Callout variant="info">
          <code>extracted_data</code> is returned as-is from the browsing session; it is not currently
          validated against your <code>expected_output_schema</code>, so treat schema mismatches as
          something to check for in your workflow logic rather than something the agent itself
          enforces yet.
        </Callout>

        <h2>Structured extraction</h2>
        <p>
          Pass an <code>expected_output_schema</code> (a JSON Schema) alongside the task to tell the
          agent the shape of data you want back, for example a list of products with{' '}
          <code>name</code>/<code>price</code>/<code>url</code> fields. The agent returns that data in{' '}
          <code>extracted_data</code>, ready to feed into the next node without any text parsing.
        </p>

        <h2>Live view &amp; takeover</h2>
        <p>
          While a session runs, LiveContext streams a live view of the actual browser: the same
          Chromium window the agent is driving, updated in real time as it navigates. Sensitive fields
          (passwords, card numbers, CVV/CVC) are masked in every screenshot and live frame before you
          ever see them.
        </p>
        <p>
          If the live connection ever drops mid-session, the panel falls back to the last screenshot
          taken before teardown, so you're never left looking at nothing.
        </p>
        <p>
          <strong>Taking over</strong> a session (for example, to solve a CAPTCHA or log in yourself)
          pauses the browser and blocks the workflow until you resolve it:
        </p>
        <Steps>
          <Step n={1} title="Session reports USER_TAKEOVER">
            The browser pauses at the next step boundary and the workflow node waits (up to 30 minutes
            by default) for you to act.
          </Step>
          <Step n={2} title="You resolve it">
            From the live view, resume the session, optionally leaving a hint for the agent about what
            you just did (for example, "logged in, continue from the dashboard").
          </Step>
          <Step n={3} title="Both halves unfreeze together">
            Resuming releases the workflow's wait <em>and</em> unpauses the browser itself, so the
            agent picks back up in sync with what actually happened on screen.
          </Step>
        </Steps>
        <p>
          From chat, the same control surface is available without a full takeover:{' '}
          <code>browse_status</code> to check in, <code>browse_intervene</code> to inject a hint,{' '}
          <code>browse_abort</code> to cancel, and <code>browse_screenshot</code> to force a fresh shot.
          None of these consume a concurrency slot.
        </p>

        <h2>Domain allow/deny lists</h2>
        <p>
          Set <code>domain_allowlist</code> and/or <code>domain_denylist</code> to keep a session inside
          (or out of) specific domains. A navigation outside the allowed set ends the session with{' '}
          <code>DOMAIN_BLOCKED</code>. This is on top of a built-in safety check that blocks navigation
          to unsafe internal addresses regardless of your lists, including redirects that try to sneak
          through after the page has already loaded.
        </p>

        <h2 id="budgets">Budgets &amp; concurrency</h2>
        <p>
          A browser session is expensive: it holds a real Chromium process and an LLM context for its
          whole lifetime, so it's capped from two directions:
        </p>
        <DocsTable
          head={['Limit', 'Default', 'What happens when hit']}
          rows={[
            ['Concurrent sessions, per host', '1', 'A second overlapping session anywhere on the host is rejected immediately.'],
            ['Concurrent sessions, per user', '1', 'Your next session is rejected until your current one finishes.'],
            ['Steps per user, per day', '200', 'New sessions are rejected once you cross the daily step count; the quota resets at 00:00 UTC.'],
            ['Wall-clock, per session', '600 seconds', 'The session stops with TIMEOUT.'],
            ['Steps, per session', '50', 'The session stops with MAX_STEPS.'],
          ]}
        />
        <p>
          The per-user limits apply the same way no matter which model drives the session, there's no
          exemption for any particular provider.
        </p>

        <h2>Model selection</h2>
        <p>
          Pick a model the usual way on the node. From chat, you can leave the model unset and the
          platform substitutes its best available model for browsing, telling you exactly what it
          swapped in.
        </p>
        <p>
          The Browser Agent needs a model that can answer one step at a time as a plain chat
          completion, so bridge-style CLI models (for example a Claude Code or Codex CLI session) can't
          drive it directly, since those run a whole agent session per call rather than a single
          per-step completion. If one is requested, the platform substitutes a direct-API model
          instead and reports the swap back to you.
        </p>

        <h2>Chat browsing</h2>
        <p>
          In chat, <code>agent_browse</code> is the heaviest of the web tools, roughly a hundred times
          the cost of a page fetch, since it pins a full browser for the whole task. Chat agents are
          guided to reach for it only when a plain fetch can't do the job: pages behind a login, or
          that need real clicking and typing to reveal their content.
        </p>
        <p>
          A successful <code>agent_browse</code> call in chat shows up as a live panel right in the
          conversation, the same live view described above, so you can watch (or take over) without
          leaving the chat.
        </p>

        <h2>Cost &amp; observability</h2>
        <p>
          Every session's token usage is priced against the model actually used and rolled into your
          usual usage history, the same as any other model call. Each session is recorded individually,
          so you can see exactly which task ran, which model drove it, and what it cost.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents">Model selection, budgets, and the general agent stop reasons.</Card>
          <Card icon={Globe} title="Workflows" href="/workflows">How the Browser Agent node sits in a graph.</Card>
          <Card icon={MessagesSquare} title="Chat" href="/chat">Calling tools like agent_browse from a conversation.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
