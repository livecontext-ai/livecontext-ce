import { MessageSquare, BookOpen, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Steps, Step, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Getting started',
  description:
    'Sign up, get through onboarding, and build your first LiveContext automation by describing it in chat. The assistant assembles the workflow in front of you - you review it, run it, and ship it.',
  path: '/docs/getting-started',
});

export default function GettingStartedPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Getting started"
        lead="The fastest way to build something in LiveContext is to describe it in chat. You say what you want done, the assistant builds the workflow step by step in front of you, and you run it - all in the same conversation. First, a short setup gets you there."
      />

      <DocsProse>
        <h2>Sign up and land in chat</h2>
        <p>
          Visiting a protected page while signed out sends you to log in. Once you&apos;re
          authenticated, LiveContext checks whether you still need onboarding, meaning a new
          account, an incomplete profile, or an unverified email, and routes you through it before
          you ever see chat. Whether you complete the flow or skip it, it always ends in the same
          place: your chat.
        </p>

        <h3>Cloud: email code, profile, then a welcome gift</h3>
        <p>
          On LiveContext Cloud, onboarding is a short sequence. A step 0 only appears if your
          email isn&apos;t verified yet:
        </p>
        <DocsTable
          head={['Step', 'What it asks for']}
          rows={[
            [
              '0. Verify email (cloud only, if needed)',
              'A 6-digit one-time code is sent to your address automatically. You can request a new one every 60 seconds. Verifying advances you to the profile.',
            ],
            [
              '1. Profile',
              'Display name, role, and company/team size.',
            ],
            ['2. Interests', 'What you’re interested in using LiveContext for.'],
            [
              '3. Goals',
              'Your intended use cases and experience level.',
            ],
          ]}
        />
        <Callout variant="info">
          You can skip the profile questionnaire (steps 1-3), but not the email step. A{' '}
          <strong>display name is always required</strong>, even when you skip the rest: the Skip
          button stays disabled until you&apos;ve entered one that&apos;s available.
        </Callout>

        <h4>Display name rules</h4>
        <DocsTable
          head={['Rule', 'Detail']}
          rows={[
            ['Length', '3 to 30 characters.'],
            ['Uniqueness', 'Case-insensitive across all users, checked live as you type (roughly half a second after you stop typing).'],
            [
              'Allowed characters',
              'Letters (including accented characters), digits, spaces, hyphens, and underscores. If you paste an email address, only the part before the @ is kept.',
            ],
            ['Where it shows', 'It becomes your name in the app sidebar.'],
          ]}
        />

        <h4>Welcome gift and suggested apps (cloud only)</h4>
        <p>
          Finishing or skipping onboarding on Cloud triggers two modals in sequence. First, a{' '}
          <strong>welcome gift</strong>: the free plan starts every account with an initial credit
          balance, and the modal shows your current, live balance (not a fixed number), with the
          note that it renews every month for free. Then, once the gift modal is dismissed, a{' '}
          <strong>suggested apps</strong> modal offers up to four marketplace apps chosen from the
          interests, use cases, and profession you gave during onboarding. Neither modal appears
          on a self-hosted (CE) install.
        </p>

        <h3>Self-hosted (CE): the admin setup wizard</h3>
        <p>
          Community Edition onboarding is different. There is no email code and no profile
          questionnaire. Instead, the first admin to sign in is sent through a 5-step{' '}
          <strong>setup wizard</strong> before the install is considered bootstrapped; any other
          (non-admin) user goes straight to chat.
        </p>
        <DocsTable
          head={['Step', 'What it configures']}
          rows={[
            ['1. Cloud connection', 'Linking to LiveContext Cloud is the recommended default; you can also skip it.'],
            ['2. AI providers', 'Bring your own API keys for model providers. Optional.'],
            ['3. CLI providers', 'Connect CLI-based coding agents (Claude Code, Codex, Gemini CLI, Mistral Vibe). Optional.'],
            ['4. Platform credentials', 'Credentials for third-party platforms your workflows will call. Optional.'],
            ['5. Done', 'Recap, then straight into chat.'],
          ]}
        />
        <p>
          See <a href="/self-host">Self-hosting</a> for what each of these steps unlocks in a CE
          install.
        </p>

        <h2>Build your first automation</h2>
        <p>
          Once you&apos;re in chat, you don&apos;t need to know any node types or syntax to start.
          Describe the job in plain language and refine it by talking back. Here is the whole
          loop.
        </p>

        <Steps>
          <Step n={1} title="Describe the job in chat">
            Open a new chat and say what you want, in your own words - for example:{' '}
            <em>&ldquo;When someone submits the contact form, look up the customer and email them a
            welcome message.&rdquo;</em> Be as specific or as loose as you like; you can always add
            detail later.
          </Step>
          <Step n={2} title="Watch it build">
            The assistant lays out the workflow node by node: a <strong>trigger</strong> to start it
            (here, the form submission), one or more <strong>steps</strong> to do the work (look up
            the customer, send the email), and a reply. Each node appears on the canvas, so you can
            see the logic as it&apos;s assembled - nothing is hidden.
          </Step>
          <Step n={3} title="Refine in plain language">
            Ask for changes the same way you&apos;d ask a teammate:{' '}
            <em>&ldquo;Only email premium customers,&rdquo;</em> or{' '}
            <em>&ldquo;Also log every submission to a table.&rdquo;</em> The assistant edits the
            workflow and tells you what changed.
          </Step>
          <Step n={4} title="Run it and read the results">
            Ask it to run, or hit run yourself. Results stream back into the chat step by step - each
            node shows its output, so you can see exactly what happened and where, including any
            errors.
          </Step>
          <Step n={5} title="Ship it">
            Save the workflow. The same build is now usable as a <strong>workflow</strong> you can
            trigger, an <strong>app</strong> you can share with a link, an <strong>agent</strong> you
            can put on a schedule, and a <strong>chat</strong> people can talk to.
          </Step>
        </Steps>

        <Callout variant="info">
          Give each step a clear name (&ldquo;Get customer&rdquo;, &ldquo;Send welcome email&rdquo;).
          Names become the handle you use to pass data between steps, so descriptive labels make the
          whole workflow easier to read and wire up.
        </Callout>

        <h2>How data moves between steps</h2>
        <p>
          Connecting two steps sets their <em>order</em> - it does not automatically pass data. To
          use an earlier step&apos;s result, you reference it with a template expression. Every value
          a step produces is addressable as{' '}
          <code>{'{{type:label.output.field}}'}</code>, where <code>type</code> is the kind of node
          (<code>trigger</code>, <code>mcp</code> for an integration step, <code>agent</code>, or{' '}
          <code>core</code>), <code>label</code> is the step&apos;s name, and <code>field</code> is
          the piece of output you want.
        </p>

        <CodeBlock language="text">{`{{trigger:contact_form.output.email}}   → the email the visitor submitted
{{mcp:get_customer.output.name}}        → the name returned by the "Get customer" step
{{agent:write_welcome.output.response}} → the text an agent step generated`}</CodeBlock>

        <p>
          Expressions are resolved <strong>at run time</strong>, when each step actually executes - and
          a step can only read data from steps that ran <strong>before</strong> it on its path. If you
          reference a step that hasn&apos;t run yet, the value is simply empty, so build your flow so
          data moves forward. See <a href="/workflows">Workflows</a> for how branching and merging
          change what &ldquo;ran before it&rdquo; means.
        </p>

        <Callout variant="info">
          Every run is recorded. You can reopen any past run and inspect it node by node - inputs,
          outputs, which branch was taken, and any error - which makes debugging a failed run
          straightforward.
        </Callout>

        <h2>Ship it as a workflow, app, agent, or chat</h2>
        <p>
          Saving the build is presented as usable four ways: a <strong>workflow</strong> you can
          trigger on your own schedule or event, an <strong>app</strong> you can share with a link, an{' '}
          <strong>agent</strong> you can put on a schedule, and a <strong>chat</strong> people can talk
          to. These are ways of exposing and consuming the same underlying build, not four unrelated
          things you build separately.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={BookOpen} title="Core concepts" href="/concepts">
            The handful of terms - run, node, trigger, agent, interface, credit - used everywhere.
          </Card>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            How the conversational builder works, and what you can ask it to do.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            The builder in depth: structure, branching, loops, and parallel execution.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
