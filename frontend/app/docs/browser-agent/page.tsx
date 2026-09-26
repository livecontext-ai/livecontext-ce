import { Globe, Bot, CreditCard, Server } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Browser Agent',
  description:
    'The Browser Agent drives a real Chromium browser with an LLM, as a workflow node or from chat: settings, stop reasons, limits, live view, plans, and pricing.',
  path: '/docs/browser-agent',
});

export default function BrowserAgentPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Browser Agent"
        lead="Give an LLM a task and a real browser. The Browser Agent opens pages, clicks, types, and reads step by step, then returns a summary of what it found. This page covers when to use it, the settings that take effect, its limits, and how it is billed."
      />

      <DocsProse>
        <h2>What it is</h2>
        <p>
          The Browser Agent runs a headless Chromium browser driven by a language model. At each step
          the model looks at the page, picks an action (open a URL, click, type, scroll, read), and the
          browser performs it. The session ends when the model decides the task is done or when a limit
          is reached.
        </p>
        <p>You can run it from two places, and both use the same engine:</p>
        <ul>
          <li>
            <strong>Workflow node</strong>: the <strong>Browser Agent</strong> node runs one browsing
            session each time it executes. See <a href="/workflows">Workflows</a> for how nodes connect.
          </li>
          <li>
            <strong>Chat</strong>: ask a chat agent to browse a site. The agent starts a session through
            the <code>agent_browse</code> action of its <code>web_search</code> tool, can check on it
            with <code>browse_status</code>, and can stop it with <code>browse_abort</code>.
          </li>
        </ul>

        <h2>When to use it</h2>
        <p>
          A browser session is the most expensive web tool: it holds a real browser and a model
          context for the whole task, roughly a hundred times the cost of fetching a page. Chat agents
          are told to use it only when a plain search or page fetch cannot do the job, for example
          when content only appears after clicking through a site or filling in a form. Prefer a
          catalog integration or an HTTP request when the site has an API.
        </p>

        <h2>Before you begin</h2>
        <Callout title="Cloud: Pro plan and above">
          On the cloud, the Browser Agent node requires the <strong>Pro</strong> plan or higher. The
          node palette marks it with the plan it needs, and on a lower plan the node fails at run time
          with <code>PLAN_UPGRADE_REQUIRED</code>. Self-hosted installs are never plan-gated.
        </Callout>
        <Callout title="Self-hosted: optional component">
          On the Community Edition the browser agent is an optional component and is off by default,
          because it needs a heavy Chromium container (about 1 GB of image plus 2 GB of shared
          memory). Check its status in <strong>Settings &gt; Information</strong>, under{' '}
          <strong>Optional components</strong>, and see <a href="/self-host">Self-hosting</a> for the
          command that enables it.
        </Callout>
        <p>On a self-hosted install without the component:</p>
        <ul>
          <li>
            If the install is linked to the cloud with the <strong>Cloud</strong> model source, a
            Browser Agent node relays its session to the cloud, which runs it and bills your cloud
            account.
          </li>
          <li>
            Otherwise the node shows a notice in the builder and fails at run time with a message
            explaining how to enable it, and chat agents do not offer browsing.
          </li>
        </ul>
        <CodeBlock language="bash" title="Enable the browser agent (self-hosted)">{`docker compose --env-file .env --env-file docker/.env.ce.browser-agent up -d`}</CodeBlock>

        <h2>Configure the node</h2>
        <Steps>
          <Step n={1} title="Add the node">
            Add a <strong>Browser Agent</strong> node to your workflow.
          </Step>
          <Step n={2} title="Write the task">
            Fill in <strong>Task</strong>, the only required field. Be specific: vague tasks waste
            steps. The task supports <code>{'{{...}}'}</code> templates, so you can pass in data from
            earlier nodes.
          </Step>
          <Step n={3} title="Optionally set a start URL and a model">
            Set <strong>Start URL</strong> if you know where the agent should begin. If you leave it
            empty, the agent picks a URL from the task. Pick a model, or leave it unset to use the
            platform default.
          </Step>
        </Steps>
        <DocsTable
          caption="Browser Agent settings that affect a run"
          rowHeaders
          head={['Setting', 'Default', 'Effect']}
          rows={[
            ['Task', 'Required', 'The goal of the session, in plain language.'],
            ['Start URL', 'None', 'The first page to open. Checked for safety before the browser starts.'],
            ['Model', 'Platform default', 'The model that decides each step. See Model selection below.'],
          ]}
        />
        <Callout variant="warn">
          The other fields under <strong>Advanced</strong> on the node are not applied to the session
          today. A node run always uses up to 50 steps and 600 seconds, whatever those fields show.
          From chat, the agent can lower the step limit for a session; 50 steps and 600 seconds remain
          the hard maximum on every path.
        </Callout>

        <h2>Model selection</h2>
        <p>
          The model must answer one step at a time as a plain chat completion. When no model is set,
          or when the requested model is not in the catalog, the platform uses the highest-ranked
          model of the browser agent category (set by an administrator on the Models page) and reports
          the substitution. CLI bridge models such as Claude Code or Codex are not used for automatic
          selection, because they run a whole agent session per call. See{' '}
          <a href="/models">Models</a>.
        </p>

        <h2 id="outputs">Outputs</h2>
        <p>Every session returns these fields:</p>
        <DocsTable
          caption="Browser Agent output fields"
          rowHeaders
          head={['Field', 'What it holds']}
          rows={[
            [<code key="o">final_result</code>, 'A plain-language summary of what happened. On failure, the error message.'],
            [<code key="o">extracted_data</code>, 'Content the agent extracted while browsing, as returned by the session. May be empty.'],
            [<code key="o">stop_reason</code>, 'Why the session ended (see the next section).'],
            [<code key="o">final_url</code>, 'The last page the session was on.'],
            [<code key="o">pages_visited</code>, 'The URLs visited, in order.'],
            [<code key="o">steps</code>, 'A per-step trace of the actions taken.'],
            [<code key="o">cost</code>, 'Token usage (input, output, cache, image input, per model), number of model calls, and browser seconds.'],
            [<code key="o">session_id</code>, 'The session identifier.'],
          ]}
        />
        <p>Reference them downstream by the node label:</p>
        <CodeBlock language="text">{`{{agent:price_check.output.final_result}}
{{agent:price_check.output.final_url}}
{{agent:price_check.output.pages_visited}}`}</CodeBlock>

        <h2 id="stop-reasons">Stop reasons</h2>
        <DocsTable
          caption="Browser Agent stop reasons"
          rowHeaders
          head={['Stop reason', 'Meaning']}
          rows={[
            [<code key="s">COMPLETED</code>, 'The agent decided the task was done.'],
            [<code key="s">MAX_STEPS</code>, 'The session used all its steps before finishing.'],
            [<code key="s">TIMEOUT</code>, 'The session ran out of time.'],
            [<code key="s">CANCELLED</code>, 'The session was stopped by a user or by the chat agent, at the next step boundary.'],
            [<code key="s">BUDGET_EXHAUSTED</code>, 'No browser capacity became free in time, or you reached your daily step quota (see Limits).'],
            [<code key="s">DOMAIN_BLOCKED</code>, 'The start URL or a later navigation pointed at an unsafe address, such as an internal network host.'],
            [<code key="s">LLM_FAILED</code>, 'The model could not be used (for example a missing key), or the agent stopped early after repeated errors.'],
          ]}
        />
        <p>
          Any stop reason other than <code>COMPLETED</code> makes the node fail, with{' '}
          <code>final_result</code> as the error message, so one error branch catches every
          unsuccessful session.
        </p>

        <h2 id="budgets">Limits and concurrency</h2>
        <p>
          Each session holds a real Chromium process, so the platform bounds how many run at once and
          how many steps you use per day.
        </p>
        <DocsTable
          caption="Browser Agent limits"
          rowHeaders
          head={['Limit', 'Default', 'What happens when you reach it']}
          rows={[
            ['Running sessions per host', '3', 'Extra sessions wait in a first-in, first-out queue.'],
            ['Running sessions per user', '1', 'Your extra sessions wait in the same queue.'],
            ['Queue wait', 'Up to 240 seconds', 'A session that cannot start in time ends with BUDGET_EXHAUSTED.'],
            ['Steps per user per day', '200', 'New sessions are refused, and a running session stops with BUDGET_EXHAUSTED. The quota resets at 00:00 UTC.'],
            ['Steps per session', '50', 'The session stops with MAX_STEPS.'],
            ['Time per session', '600 seconds', 'The session stops with TIMEOUT.'],
          ]}
        />
        <p>
          Time spent in the queue counts against the 600 seconds, with at least 30 seconds left to run.
          So a workflow that starts several browser sessions in parallel (for example in a split) runs
          them one after another for the same user instead of failing all but one. The limits apply
          the same way whatever model drives the session.
        </p>

        <h2>Live view and taking control</h2>
        <p>
          While a session runs, you can open a live view of the browser: in the workflow, with{' '}
          <strong>View live browser</strong> on the node; in chat, in the panel that appears in the
          conversation. Password, card number, and security code fields are masked in screenshots and
          in the live view.
        </p>
        <p>
          Click the live view to take control. After you confirm with{' '}
          <strong>Yes, take control</strong>, the agent pauses at the next step boundary and you can
          use the page yourself. Select <strong>Resume agent</strong> to hand control back.
        </p>
        <ul>
          <li>The session clock keeps running while the agent is paused.</li>
          <li>If you stop interacting for 5 minutes, the agent resumes on its own.</li>
          <li>
            Typing into password, card number, and security code fields is blocked for the agent and
            for you, so you cannot sign in with a password during a session.
          </li>
        </ul>

        <h3>After the task finishes</h3>
        <p>
          If someone is watching the live view (or has taken control) when the agent finishes, the
          result is returned right away but the page stays open so you can keep using it. The browser
          closes 20 seconds after the last viewer leaves, after 5 minutes without input, or after 30
          minutes in any case. You keep at most one open page: your next session closes the previous
          one. Held pages do not block new sessions.
        </p>

        <h2>Pricing</h2>
        <p>
          There is no per-step or per-session fee. Every model call of the session is priced at the
          rate of the model actually used, and the session appears in your credit history under{' '}
          <strong>Browser Agent</strong>, one entry per session. See <a href="/billing">Billing</a>.
          On a self-hosted install that relays through the cloud, the usage is billed on the linked
          cloud account.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common Browser Agent problems"
          rowHeaders
          head={['Symptom', 'Cause and fix']}
          rows={[
            ['PLAN_UPGRADE_REQUIRED', 'Your cloud plan is below Pro. Upgrade, or use a fetch or an integration instead.'],
            ['The node says the browser agent is not enabled', 'Self-hosted install without the optional component. Enable it, or link the install to the cloud with the Cloud model source.'],
            ['BUDGET_EXHAUSTED right away', 'The host or your own sessions were busy for the whole queue wait, or you used your 200 daily steps. Retry later, or run fewer sessions in parallel.'],
            ['DOMAIN_BLOCKED', 'The URL points at a private or internal address. Use a public URL.'],
            ['LLM_FAILED', 'Read final_result: it names the model error (for example a missing API key) or the last error before the agent gave up.'],
            ['MAX_STEPS or TIMEOUT on a long task', 'The task is too large for 50 steps or 600 seconds. Split it into smaller tasks, or give a Start URL closer to the target.'],
            ['Cannot sign in to a site', 'Password fields are blocked by design. Browse pages that do not need a password login.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Bot} title="Agents" href="/agents">Chat agents that can start browser sessions.</Card>
          <Card icon={Globe} title="Models" href="/models">The browser agent model ranking and providers.</Card>
          <Card icon={CreditCard} title="Billing" href="/billing">Credits, plans, and credit history.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Enable optional components on the Community Edition.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
