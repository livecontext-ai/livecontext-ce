import { MessageSquare, BookOpen, Workflow, Braces, Zap, MessagesSquare } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Steps, Step, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Getting started',
  description:
    'Sign up, finish onboarding, and build, run, and put into production your first LiveContext workflow by describing it in chat.',
  path: '/docs/getting-started',
});

export default function GettingStartedPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Getting started"
        lead="This tutorial takes you from a new account to a working workflow in production. You sign up, answer a few onboarding questions, then describe a small automation to Orbi, the assistant, and watch it build, run, and save it."
      />

      <DocsProse>
        <h2>Before you begin</h2>
        <p>
          You need a LiveContext account, on the cloud or on a self-hosted{' '}
          <a href="/self-host">Community Edition</a> (CE) instance. The tutorial takes about ten minutes
          and needs no connected app. It uses one AI step, which spends a few{' '}
          <a href="/glossary">credits</a>.
        </p>

        <h2>Sign up and onboarding</h2>
        <p>
          Visiting any app page while signed out sends you to log in, then back to the page you asked
          for. After you sign in, LiveContext routes you through onboarding if you have not finished or
          skipped it yet, or if your email is not verified. Whether you complete it or skip it, you end up
          in chat.
        </p>

        <h3>Cloud: verify your email, then three short steps</h3>
        <DocsTable
          head={['Step', 'What it asks', 'Required']}
          rowHeaders
          caption="Cloud onboarding steps"
          rows={[
            [
              '0. Verify Your Email',
              'Only if your address is not verified yet. A 6-digit code is sent automatically. It is submitted as soon as you type or paste the sixth digit.',
              'Yes, cannot be skipped',
            ],
            [
              "1. Let's get to know you",
              'Display Name, your role (choose Other to type your own), and company size. An unchecked box offers LiveContext news by email.',
              'Only the display name',
            ],
            [
              '2. What do you want to automate first?',
              'Your main goal, and which tools you already use.',
              'The main goal (tools are optional)',
            ],
            [
              '3. Almost there',
              'What you automate with today, and how you heard about LiveContext.',
              'Both questions',
            ],
          ]}
        />
        <p>
          The email code expires after 10 minutes and allows 3 attempts. <strong>Resend Code</strong>{' '}
          becomes available again after 60 seconds, up to 5 codes per hour. If the code went to the wrong
          address, use <strong>Not your address? Sign out</strong>.
        </p>
        <Callout variant="info" title="Skipping the questions">
          <strong>Skip for now</strong> ends onboarding from any of steps 1 to 3, but a display name is
          always required first. The button stays disabled until you enter one that is available (or
          until the availability check itself fails, in which case the server checks the name when you
          continue).
        </Callout>

        <h3>Display name rules</h3>
        <DocsTable
          caption="Display name rules"
          head={['Rule', 'Detail']}
          rowHeaders
          rows={[
            ['Length', '3 to 30 characters.'],
            ['Uniqueness', 'Case-insensitive across all users, checked about half a second after you stop typing.'],
            [
              'Characters',
              'Latin letters (including common accented letters such as é or ü), digits, spaces, hyphens, and underscores. Other characters are removed as you type. If you paste an email address, only the part before the @ is kept.',
            ],
            ['Visibility', 'Visible to other users.'],
          ]}
        />

        <h3>What you see when you arrive</h3>
        <p>On the cloud, two windows can open over the chat, one after the other:</p>
        <ul>
          <li>
            <strong>Welcome gift</strong> (Free plan): every account starts on the Free plan, which gives
            you 1,000 monthly credits. They pay for workflow runs, and for chat and agent
            turns on the models marked <strong>Free</strong>. They refill automatically at the start of
            every month, and no card is needed. Other models, web search, and generation need a credit
            top-up or a paid plan. See <a href="/billing">Plans &amp; billing</a>.
          </li>
          <li>
            <strong>Apps picked for you</strong>: up to four marketplace apps chosen from your onboarding
            answers (popular apps when nothing matches). It also appears when you skipped the questions.
            Choose <strong>Browse the marketplace</strong> or <strong>Maybe later</strong>.
          </li>
        </ul>
        <p>
          The chat itself opens empty, with Orbi&apos;s greeting and five suggestion chips under the
          composer (for example <strong>Summarize my inbox each morning</strong>). A chip fills the
          composer with a detailed request but does not send it, so you can edit it first.
        </p>

        <h3>The Get set up checklist</h3>
        <p>
          The sidebar shows a <strong>Get set up</strong> card with four tasks. Each one ticks itself as
          soon as it is done, wherever you did it, and the card goes away once all four are complete:
        </p>
        <DocsTable
          caption="Tasks in the Get set up checklist"
          head={['Task', 'Why it matters']}
          rowHeaders
          rows={[
            ['Connect an app', 'Your agents can then read and act in it (Gmail, Notion, Slack, and so on).'],
            ['Talk to Orbi', 'Ask for anything: Orbi builds agents, workflows, and apps for you.'],
            ['Create an agent or a workflow', 'Something that works for you, even when you are not there.'],
            ['Get reached outside the app', 'Approvals and questions arrive on Telegram, Slack, WhatsApp, Discord, or Teams.'],
          ]}
        />
        <p>
          Each open task links to the place where you do it. When the sidebar is collapsed, the card
          shrinks to a counter such as <strong>2/4</strong>.
        </p>

        <h3>Self-hosted (CE): the setup wizard</h3>
        <p>
          A Community Edition instance has no email code. The first time an administrator signs in to an
          instance that is not set up yet, a 5-step setup wizard opens. Users who are not
          administrators go straight to chat.
        </p>
        <DocsTable
          caption="Steps of the self-hosted setup wizard"
          head={['Step', 'What it configures']}
          rowHeaders
          rows={[
            ['1. Cloud connection', 'Link the instance to LiveContext Cloud (recommended). Next stays disabled until the link is made; use Skip to continue without it.'],
            ['2. AI providers', 'Your own API keys for model providers. Optional.'],
            ['3. CLI providers', 'Command-line coding agents: Claude Code, Codex, Gemini CLI, Mistral Vibe. Optional.'],
            ['4. Platform credentials', 'Credentials for third-party platforms your workflows will call. Optional.'],
            ['5. Done', 'A recap, then chat.'],
          ]}
        />
        <p>
          <strong>Skip for now</strong> at the top of the wizard ends it at once, and warns you if no AI
          source is configured yet. The welcome gift and suggested apps do not appear on a self-hosted
          instance. See <a href="/self-host">Self-hosting</a> for what each step unlocks.
        </p>

        <h2>Tutorial: your first workflow</h2>
        <p>
          You will build a three-step workflow: a manual trigger, an AI step that writes three focus tips,
          and a step that saves them to a table. You do not need to know any node types or syntax.
        </p>

        <Steps>
          <Step n={1} title="Open a new chat">
            In the sidebar, choose <strong>New chat</strong>. The composer at the bottom reads{' '}
            <strong>Message Orbi...</strong>.
            <br />
            <em>Result:</em> an empty conversation with Orbi&apos;s greeting.
          </Step>
          <Step n={2} title="Describe the job">
            Type the request below, then press Enter:
            <CodeBlock title="Your message">{`Build a workflow I can start manually. An AI step writes three short tips for staying focused at work. Then save the tips, with today's date, as a new row in a table called Focus tips.`}</CodeBlock>
            <em>Result:</em> Orbi starts working. Each action appears as a tool card in the thread, and
            the workflow appears as a diagram block with a trigger, an AI step, and a table step. If the{' '}
            <strong>Focus tips</strong> table does not exist yet, Orbi creates it as part of the build.
          </Step>
          <Step n={3} title="Open the workflow">
            Click the diagram block (or choose <strong>Open</strong> in its menu).
            <br />
            <em>Result:</em> the workflow opens in the side panel, next to the conversation, with its
            nodes connected left to right. Click a node to see its settings.
          </Step>
          <Step n={4} title="Refine it in plain language">
            Ask for a change the way you would ask a teammate, for example{' '}
            <em>&ldquo;Make it five tips instead of three.&rdquo;</em>
            <br />
            <em>Result:</em> Orbi edits the existing workflow instead of starting over, and tells you what
            it changed. The diagram updates.
          </Step>
          <Step n={5} title="Run it">
            Ask <em>&ldquo;Run it.&rdquo;</em> You can also use the <strong>Run</strong> button in the
            workflow toolbar.
            <br />
            <em>Result:</em> a run trace appears, step by step, with each node&apos;s output. If a step
            fails, the trace shows which one and why. Ask <em>&ldquo;Why did that fail?&rdquo;</em> and
            Orbi reads the run and proposes a fix.
          </Step>
          <Step n={6} title="Check the result">
            Open <strong>Tables</strong> in the sidebar, then the <strong>Focus tips</strong> table.
            <br />
            <em>Result:</em> one new row holds the tips and the date. Every run is also recorded, so you
            can reopen it later and inspect each node&apos;s inputs and outputs.
          </Step>
          <Step n={7} title="Save a version and set it as production">
            In the workflow panel, click <strong>Save</strong>, open <strong>Version History</strong>,
            and choose <strong>Set as production</strong> on the version you just saved.
            <br />
            <em>Result:</em> the version is tagged <strong>production</strong> and the workflow is live.
            Later edits do not change what runs until you set a newer version as production, so when you
            add a schedule or a webhook, save again and move production to that version.
          </Step>
        </Steps>

        <Callout variant="info" title="Credits">
          The AI step spends credits on every run. On the Free plan your monthly credits cover the models
          marked <strong>Free</strong>. If a run stops with an insufficient-credits notice, pick a Free
          model for the step or add credits.
        </Callout>

        <Callout variant="tip">
          Give each step a clear name (&ldquo;Write tips&rdquo;, &ldquo;Save tips&rdquo;). The name
          becomes the handle other steps use to read its output, so descriptive labels make the whole
          workflow easier to read.
        </Callout>

        <h2>How data moves between steps</h2>
        <p>
          Connecting two steps sets their <em>order</em>. It does not pass data. To use an earlier
          step&apos;s result, a step references it with a template expression of the form{' '}
          <code>{'{{type:label.output.field}}'}</code>: <code>type</code> is the kind of node (such as{' '}
          <code>trigger</code>, <code>agent</code>, <code>mcp</code> for an integration, or{' '}
          <code>core</code>), <code>label</code> is the step&apos;s name, and <code>field</code> is the
          piece of output you want.
        </p>
        <CodeBlock title="Example expressions">{`{{agent:write_tips.output.response}}
{{trigger:contact_form.output.email}}`}</CodeBlock>
        <p>
          The first reads the text an AI step named &ldquo;Write tips&rdquo; generated. The second reads
          the email field a visitor submitted to a form trigger named &ldquo;Contact form&rdquo;.
          Expressions are resolved when the step runs, and a step can only read steps that ran before it
          on its path. A reference to a step that has not run resolves to an empty value. See{' '}
          <a href="/expressions">Expressions &amp; variables</a> for the full syntax.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common problems when getting started"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'A verification code is refused',
              'The code expires after 10 minutes and allows 3 attempts.',
              'Select Resend Code (available again after 60 seconds, up to 5 codes per hour) and type the new code.',
            ],
            [
              'The code went to the wrong address',
              'You signed up with a different email.',
              'Use Not your address? Sign out and sign up again with the right address.',
            ],
            [
              'Skip for now stays disabled',
              'A display name is required first, and it must be available.',
              'Enter a display name of 3 to 30 characters that nobody else uses.',
            ],
            [
              'A run stops with an insufficient-credits notice',
              'The AI step uses a model your credits do not cover.',
              'On the Free plan, pick a model marked Free for the step, or add credits.',
            ],
            [
              'A step receives an empty value',
              'Its expression names a step that has not run on its path, or uses the wrong label.',
              'Check the label in the expression against the step name, and connect the step after the one it reads.',
            ],
            [
              'The workflow never runs on its own',
              'A workflow only starts when you run it, unless it has a trigger such as a schedule or a webhook.',
              'Ask Orbi to add a trigger, then save and set the new version as production.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Zap} title="Triggers" href="/triggers">
            Start runs from a schedule, a webhook, a form, a table change, or another workflow.
          </Card>
          <Card icon={BookOpen} title="Core concepts" href="/concepts">
            Runs, nodes, versions, and credits: the terms used everywhere.
          </Card>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            Everything the composer, the model picker, and the chat settings can do.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            The builder in depth: branching, loops, parallel steps, and versions.
          </Card>
          <Card icon={Braces} title="Expressions & variables" href="/expressions">
            Read and transform data between steps.
          </Card>
          <Card icon={MessagesSquare} title="Chat channels" href="/channels">
            Get approvals and questions on Telegram, Slack, and more.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
