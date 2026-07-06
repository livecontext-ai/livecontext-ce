import { Workflow, Bot, Boxes } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Chat',
  description:
    'Chat is the conversational builder in LiveContext: build, run, and edit workflows in one thread, with a model picker, per-conversation reasoning effort, attachments, skills, tool-authorization cards, and a live execution Activity view.',
  path: '/docs/chat',
});

export default function ChatPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Chat"
        lead="Chat is where you build and run automations by talking. You describe the outcome you want; the assistant assembles the workflow, wires it up, runs it, and edits it as you give feedback, without leaving the conversation."
      />

      <DocsProse>
        <h2>What you can ask</h2>
        <p>In a single thread, you can ask the assistant to:</p>
        <ul>
          <li><strong>Build</strong> a workflow from a description of the job.</li>
          <li><strong>Change</strong> a step: swap an integration, adjust a condition, shorten a prompt.</li>
          <li><strong>Run</strong> the workflow and show you the results.</li>
          <li><strong>Inspect</strong> a past run to explain what happened or why it failed.</li>
          <li><strong>Connect</strong> an integration, add a table, or wrap the workflow in an app.</li>
        </ul>

        <h2>How a build conversation flows</h2>
        <p>
          As it builds, the assistant always starts with an <strong>entry point</strong>, a trigger,
          then adds the steps that do the work and wires them together. It keeps you informed at each
          stage (adding the trigger, adding steps, connecting them, fixing anything invalid, ready to
          save) and tells you what it just did and what it suggests next. You stay in control: nothing
          runs or saves unless you ask.
        </p>
        <Callout variant="info">
          A workflow always begins with a trigger. If you describe steps before there&apos;s a way to
          start the workflow, the assistant adds the trigger first: every automation needs an entry
          point.
        </Callout>

        <h2>Running and debugging from chat</h2>
        <p>
          Ask the assistant to run the workflow and the results stream back into the thread, step by
          step, with each node&apos;s output. If something fails, ask{' '}
          <em>&ldquo;why did that fail?&rdquo;</em>, it reopens the run and reads it node by node, so
          it can tell you which step broke and why (a bad credential, a missing field, an empty
          result) and offer a fix.
        </p>
        <CodeBlock language="text">{`You:        Run it with a test contact.
Assistant:  ✓ Contact form received  {email: "test@acme.com"}
            ✓ Get customer            {name: "Dana", plan: "premium"}
            ✓ Decision: premium?      → yes
            ✗ Send welcome email      SMTP auth failed: invalid credentials
            The run stopped at "Send welcome email", the mail credential
            looks invalid. Want me to point it at a different one?`}</CodeBlock>

        <h2>Plain steps vs agents</h2>
        <p>
          Two kinds of steps do the work. <strong>Deterministic steps</strong>, an integration call,
          an HTTP request, a data transform, a bit of code, do exactly one predictable thing.{' '}
          <strong>Agents</strong> reason: they decide, summarize, classify, or handle open-ended
          input. Reach for an agent when the task genuinely needs judgement; use a plain step when the
          operation is fixed. It keeps your workflow faster, cheaper, and easier to audit.
        </p>
        <Callout variant="tip">
          Iterate out loud. &ldquo;Make the summary shorter,&rdquo; &ldquo;only do this for VIP
          customers,&rdquo; &ldquo;add a step that logs it&rdquo;, the assistant edits the existing
          workflow rather than starting over, and you can run again immediately to see the effect.
        </Callout>

        <h2>The composer: streaming, tool cards, previews</h2>
        <p>
          The message box auto-resizes as you type (about two lines up to a taller max). Enter sends
          your message; Shift+Enter inserts a newline. The single send control turns into a Stop
          button while the assistant is streaming or about to start, so you can interrupt a run in
          progress at any time.
        </p>
        <p>
          As the assistant works, its reasoning and tool use render live in the thread:
        </p>
        <ul>
          <li>A thinking indicator appears while the assistant is composing its next move.</li>
          <li>Each tool call renders as its own streaming <strong>tool card</strong>, appearing as soon as the call starts and updating as it completes.</li>
          <li>
            Results that are more than plain text render as rich inline previews: tables, interfaces,
            application carousels, generated images, workflow diagrams, and workflow run traces all
            have a dedicated preview block in the thread.
          </li>
        </ul>

        <h2>Model picker &amp; per-conversation reasoning effort</h2>
        <p>
          The model selector lives in the composer. Each entry shows the provider icon, capability
          flags, and an info popover with more detail, so you can compare models without leaving the
          thread. Switching models applies to the next message in that conversation.
        </p>
        <p>
          When the selected provider supports it, a <strong>reasoning effort</strong> control appears
          at the top of the model menu, scoped to this conversation. Effort only affects models that
          expose a real extended-thinking knob:
        </p>
        <DocsTable
          head={['Provider', 'Reasoning effort control']}
          rows={[
            ['Claude Code (bridge)', 'Shown'],
            ['Codex (bridge)', 'Shown'],
            ['Anthropic (direct API)', 'Shown'],
            ['Gemini CLI (bridge)', 'Hidden, no usable knob'],
            ['Mistral Vibe (bridge)', 'Hidden, no usable knob'],
            ['Other providers', 'Hidden'],
          ]}
        />
        <p>Effort levels, from lightest to deepest reasoning pass:</p>
        <DocsTable
          head={['Level', 'Notes']}
          rows={[
            ['Auto', 'Empty selection: the provider’s own default.'],
            [<code key="minimal">minimal</code>, 'Lightest reasoning pass.'],
            [<code key="low">low</code>, ''],
            [<code key="medium">medium</code>, ''],
            [<code key="high">high</code>, ''],
            [<code key="xhigh">xhigh</code>, ''],
            [<code key="max">max</code>, 'Deepest reasoning pass, highest latency and cost.'],
          ]}
        />

        <h2>Attachments</h2>
        <p>
          The paperclip attaches files to a message: images, PDFs, and text files are all supported,
          and you can attach several files to a single send along with your text.
        </p>
        <DocsTable
          head={['Kind', 'Accepted types']}
          rows={[
            ['Image', 'JPEG, PNG, GIF, WebP'],
            ['PDF', 'application/pdf'],
            [
              'Text',
              'Plain text, Markdown, CSV, HTML, JSON, XML, JavaScript, and CSS',
            ],
          ]}
        />
        <p>
          Each file can be up to <strong>50 MB</strong>. Images get an inline thumbnail preview that
          opens in a lightbox on click; PDFs have their text extracted server-side so the model can
          read their content, and text files are decoded and read as-is. Attachments also work in
          minimal, direct-message-style composer views (only the Tools &amp; Skills panel is hidden
          there).
        </p>
        <Callout variant="warn">
          The 50 MB figure is the validated limit end to end. In the current deployment the upload
          path is additionally constrained by the server&apos;s multipart request configuration,
          which can reject a very large file before validation runs, so the most reliable range for
          attachments today is well under the 50 MB ceiling. If a large upload is rejected, try a
          smaller file or split the content.
        </Callout>

        <h2>Skills in chat</h2>
        <p>
          The unified <strong>Tools &amp; Skills</strong> panel (the settings icon in the composer)
          lets you turn skills on or off for this conversation. With no changes, it mirrors the
          platform&apos;s default active-skill set; touching the list stores an explicit selection for
          that conversation. When the conversation is linked to an agent, the same panel edits the
          agent&apos;s own skill list instead, since the agent is the shared, reusable entity. The
          panel is hidden in minimal, direct-message-style composer views.
        </p>

        <h2>Voice dictation</h2>
        <p>
          A microphone button (shown when your browser supports speech recognition) dictates directly
          into the composer. It listens continuously, shows interim results and animated soundbars
          while you talk, and appends the transcript to whatever you&apos;ve already typed. Dictation
          works in minimal composer views too, since it is plain speech-to-text rather than an AI
          tool.
        </p>

        <h2>Chat configuration: conversation, agent, account</h2>
        <p>
          A configuration panel lets you tune behavior at three scopes: a single conversation, a
          linked agent, or your account-wide chat defaults (which seed every new conversation).
        </p>
        <DocsTable
          head={['Setting', 'Default', 'Notes']}
          rows={[
            ['System prompt', 'Empty', 'Free text.'],
            ['Temperature', '0.7', 'Range 0 to 2, in steps of 0.1.'],
            ['Max tokens', '16000', 'Minimum 1.'],
            ['Max iterations', '100', 'Range 1 to 1000, tool-call cycles before the agent must answer.'],
            ['Execution timeout', '3600 seconds', 'Range 10 to 7200 seconds.'],
            ['Tools mode', 'All tools', 'At conversation scope, the choice is "All tools" or "No tools"; a custom tool list is an agent-level feature.'],
            ['Web search', 'On', 'Toggle.'],
            ['Image generation', 'Off', 'Opt-in toggle; platform-key image calls are billed in credits, a cost that varies by model, while using your own key does not consume credits.'],
            ['Run sensitive actions without asking', 'Off', 'Skips the tool-authorization prompt for this conversation; hidden for agent-backed chats.'],
            ['Compaction', 'Inherited', 'Enable to override the default and set "compact after N turns" (default 5, range 1 to 100).'],
          ]}
        />
        <p>Advanced turn limits apply uniformly across tracked resource types (agent, skill, sub-agent, interface, workflow, table):</p>
        <DocsTable
          head={['Limit', 'Default', 'Range']}
          rows={[
            ['Max calls to the same resource per turn', '5', '1 to 100'],
            ['Stop after N identical repeated calls', '15', '2 to 100'],
            ['Stop after N consecutive calls to the same resource', '40', '4 to 200'],
          ]}
        />

        <h2>Tool authorization &amp; service approval</h2>
        <p>
          When the assistant is about to do something sensitive, a card appears asking you to approve
          or deny it before it proceeds: installing an application, executing one, continuing a paused
          workflow interface, or resolving a pending approval step. Installing an application shows the
          real marketplace listing inline so you can see exactly what you&apos;d be adding.
        </p>
        <p>
          A &ldquo;don&apos;t ask again in this conversation&rdquo; checkbox on the card flips the same
          setting as the configuration panel&apos;s auto-authorize toggle. Approving resumes the agent
          immediately; denying stops it without resuming.
        </p>
        <p>
          A separate card batches approvals for external services the workflow needs to reach. If every
          required credential already exists and nothing needs attention, it auto-approves and
          dismisses itself; if a credential exists but may be expired or invalid, it stays open and
          offers a manage or retry path instead of silently dismissing.
        </p>

        <h2>Credential prompts &amp; auto-resume</h2>
        <p>
          When a tool needs a credential you haven&apos;t connected yet, a connect card opens the
          credential wizard for that service. Returning from an OAuth authorization automatically
          reopens the wizard, shows the connected state, and resumes the agent, so completing the
          connection is enough to continue the run without retyping your original request. When
          several cards are pending in parallel, resolving one keeps the others open so you can work
          through them independently.
        </p>

        <h2>Inline visualizations</h2>
        <p>Results that aren&apos;t plain text render inline in the thread, not as attachments to download and open elsewhere:</p>
        <ul>
          <li><strong>Tables</strong> render as a live table view.</li>
          <li><strong>Interfaces</strong> render inline, and applications render as a carousel of pages.</li>
          <li><strong>Workflow structure</strong> renders as a diagram; a <strong>workflow run</strong> renders as its step-by-step trace, live or from history.</li>
          <li><strong>Generated media and files</strong> (images, generated files, arbitrary tool results) get a matching preview card.</li>
        </ul>

        <h2>Conversation Activity</h2>
        <p>
          An Activity view attached to the conversation surfaces its full execution tree: every agent
          call and tool call the conversation triggered, laid out so you can see what ran and in what
          order, with a row per tool call. In a self-hosted deployment, the Activity view also shows a
          running cost figure for the conversation. Whether the Activity view is shown or hidden is
          remembered per conversation, so your choice survives a reload.
        </p>

        <h2>Memory, sharing, search &amp; feedback</h2>
        <p>
          <strong>Memory</strong> comes from context compaction: as a conversation grows, older turns
          are summarized so the assistant keeps the gist without carrying the full transcript forever.
          You can tune when compaction kicks in from the configuration panel above.
        </p>
        <p>
          <strong>Sharing</strong> creates a read-only public link to the conversation. The number of
          links you can have open at once depends on your plan:
        </p>
        <DocsTable
          head={['Plan', 'Max shared links']}
          rows={[
            ['Free', '5'],
            ['Starter', '20'],
            ['Pro', '50'],
            ['Team / Pay-as-you-go', '100'],
            ['Enterprise', '200'],
            ['Self-hosted (CE)', 'Unlimited'],
          ]}
        />
        <p>
          <strong>Search</strong> finds past conversations by content or by title. <strong>Per-message
          actions</strong> let you copy a message, share it, retry it, mark it up or down with
          feedback, or download it as Markdown.
        </p>

        <h2>Send-while-running queue</h2>
        <p>
          You don&apos;t have to wait for the assistant to finish before sending your next message.
          While it is streaming, new sends are queued instead of interrupting the run; the queue holds
          up to 5 messages, and you can edit, remove, reorder, or send a queued message immediately
          from the queue bar. Text-only queued messages survive a page reload for up to 30 minutes;
          messages with attachments stay in memory only for that session. An unsent draft you were
          typing is also kept per conversation and restored if you navigate away and come back.
        </p>

        <h2>When something goes wrong: credits and storage</h2>
        <p>
          If a request would exceed your available credits, an insufficient-credits notice appears
          instead of a generic error, telling you what happened and pointing you to add credits. If an
          upload or generated file would exceed your storage quota, a matching insufficient-storage
          notice appears. Both are distinct from a normal failed run: they stop the request up front
          rather than letting it fail mid-way.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            What the assistant is building under the hood: structure, branching, parallelism.
          </Card>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            The catalog of building blocks the assistant can place.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Configure the AI steps: model, tools, budget, and guardrails.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
