import {
  MessageSquare,
  Workflow,
  LayoutPanelLeft,
  Bot,
  Rocket,
  BookOpen,
  Boxes,
  Store,
  Server,
  Sparkles,
} from 'lucide-react';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { docsMetadata } from './_meta';
import { DOCS_NAV } from './_nav';
import { DocsHero, DocsProse, DocsTable, CardGrid, Card, Steps, Step, Callout } from './_components';

export const metadata = docsMetadata({
  title: 'Overview',
  description:
    'LiveContext is one AI automation platform that is chat, workflow, app, and agent at once. Describe a job, watch the automation build, run it with budgeted agents, and ship it.',
  path: '/docs',
});

export default function DocsOverviewPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Overview"
        lead="LiveContext is one platform that is four things at once: a chat, a workflow builder, an app builder, and a fleet of agents. You describe a job in plain language, the automation builds itself in front of you, and you ship it as something your team or your customers can use."
      />

      <DocsProse>
        <p>
          Most teams stitch a job together across several tools: one product to chat with an AI, another
          to draw a workflow, a third to build the app, a fourth to run agents. LiveContext collapses
          that stack. You build once, and the same automation can be driven from a chat, drawn as a
          visual workflow, wrapped in a shareable app, and handed to a scheduled agent. Every step stays
          visible, every agent is scoped and budgeted, and every run is recorded.
        </p>

        <h2>The four surfaces</h2>
        <p>
          These are not separate products. They are four views of the same automation: start in any one
          of them and the others come along.
        </p>

        <CardGrid cols={2}>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            Describe what you want done to Orbi, the assistant. It builds and edits the workflow for you,
            runs it, and shows you the results. You iterate by talking to it.
          </Card>
          <Card icon={Workflow} title="Workflow" href="/workflows">
            The same automation, drawn. Branch, loop, fan out in parallel, and call agents and tools in a
            readable graph that can grow from five steps to fifty.
          </Card>
          <Card icon={LayoutPanelLeft} title="App / Interface" href="/interfaces">
            Wrap the workflow in a real web page: search, forms, cards, buttons. Share the link with your
            team or customers, or let an agent open it and click on its own.
          </Card>
          <Card icon={Bot} title="Agent" href="/agents">
            Always-on workers, one per job, which can run on a schedule. Each gets its own model, a scoped
            set of tools, and a credit budget it cannot exceed.
          </Card>
        </CardGrid>

        <p>Around those four surfaces, the workspace gives you a few more places to work:</p>
        <ul>
          <li>
            <strong>Studio</strong> generates images, video clips, voices, audio, and music from a prompt.
            It sits next to Chat on the home screen (the <strong>Chat</strong> / <strong>Studio</strong>{' '}
            switch). See <a href="/studio">Studio</a>.
          </li>
          <li>
            <strong>Chat channels</strong> link Telegram, Slack, Discord, WhatsApp, or Microsoft Teams, so
            approvals and agent questions reach you outside the app. See{' '}
            <a href="/channels">Chat channels</a>.
          </li>
          <li>
            <strong>Tasks &amp; board</strong> tracks work handed to people and agents, and the{' '}
            <strong>Agenda</strong> shows every scheduled automation on a calendar. See{' '}
            <a href="/board">Tasks &amp; board</a> and <a href="/agenda">Agenda</a>.
          </li>
        </ul>

        <h2>One model underneath: workflows, nodes, triggers</h2>
        <p>
          Whichever surface you start from, the underlying object is the same: a{' '}
          <strong>workflow</strong>, a graph of <strong>nodes</strong> connected by <strong>edges</strong>.
          A <strong>trigger</strong> starts a <strong>run</strong>. Nodes execute in dependency order, and
          data flows forward through template expressions like{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. Agents, interfaces, and tables are not bolted on
          beside the workflow: they are all kinds of node.
        </p>
        <p>
          Every node is identified by a normalized <code>prefix:label</code> key, and the prefix tells you
          what kind of node it is:
        </p>
        <DocsTable
          caption="Node key prefixes and the kind of node each one marks"
          head={['Prefix', 'Kind', 'Examples']}
          rows={[
            [
              <code key="t">trigger:</code>,
              'Entry point that starts a run',
              'Webhook, Manual, Chat, Tables (a row change), Scheduler, Form, Workflows (another workflow finishes a cycle without a failed step), Error (a cycle of another workflow has a failed step)',
            ],
            [<code key="m">mcp:</code>, 'Integration / tool operation', 'a call to one of the catalog integrations, such as Gmail or Slack'],
            [<code key="a">agent:</code>, 'AI node', 'Agent, Browser Agent, Guardrail, Classify, Generate'],
            [
              <code key="c">core:</code>,
              'Control flow and utilities',
              'Decision, Switch, Loop, Fork, Merge, Split, Aggregate, Transform, Wait, HTTP Request, Sub-workflow',
            ],
            [<code key="tb">table:</code>, 'Built-in spreadsheet operation', 'find, create, update, delete rows'],
            [<code key="i">interface:</code>, 'Web page', 'a page rendered in an iframe'],
            [<code key="n">note:</code>, 'Canvas note', 'documentation on the canvas, not executed'],
          ]}
        />
        <p>
          <a href="/concepts">Core concepts</a> covers the full mental model (runs, edges, ports, signals,
          versions, credits). <a href="/nodes">Node reference</a> is the catalog of every node type, and{' '}
          <a href="/triggers">Triggers</a> details the eight ways a run can start.
        </p>

        <h2>How a build flows</h2>
        <Steps>
          <Step n={1} title="Describe the job in chat">
            Say what you want done in plain language.
          </Step>
          <Step n={2} title="LiveContext drafts a workflow">
            A graph of connected nodes appears in the conversation, ready to run, branch, or extend.
          </Step>
          <Step n={3} title="Wire an interface">
            Wrap it in a web page so people, or agents, can interact with it.
          </Step>
          <Step n={4} title="Put an agent in the loop">
            Give it the exact tools and credit budget the job needs, and nothing more.
          </Step>
        </Steps>
        <p>
          Triggers (a webhook, a schedule, a chat message, a new table row, another workflow finishing)
          can start the whole thing on their own, and every run is recorded so you can see exactly what
          happened.
        </p>

        <h2>Start here</h2>
        <CardGrid cols={3}>
          <Card icon={Rocket} title="Getting started" href="/getting-started">
            Sign up and build your first workflow from a single chat message.
          </Card>
          <Card icon={BookOpen} title="Core concepts" href="/concepts">
            The mental model: workflows, runs, nodes, triggers, versions, agents, credits.
          </Card>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            The catalog of building blocks: triggers, control flow, AI, data, and integrations.
          </Card>
        </CardGrid>

        <h2>Cloud or self-hosted</h2>
        <p>
          LiveContext runs as a managed cloud service or as a self-hostable{' '}
          <strong>Community Edition (CE)</strong> that you run on your own infrastructure. Both run the
          same workflow engine. What differs is how you run and operate it:
        </p>
        <DocsTable
          head={['Aspect', 'Cloud', 'Community Edition']}
          rowHeaders
          caption="Differences between LiveContext Cloud and the Community Edition"
          rows={[
            ['Hosting', 'Managed by LiveContext', 'You run it on your own infrastructure'],
            [
              'Sign-in',
              'LiveContext account (email or social sign-in). SAML single sign-on is available on Team and Enterprise workspaces.',
              'Built-in email or social login on your instance',
            ],
            [
              'Workspaces & teammates',
              'Depend on your plan',
              'Extra workspaces and inviting teammates follow the plan of the cloud account you link. Without a link, the plan badge reads Community.',
            ],
            [
              'Integrations',
              'Full catalog, always current',
              'Hundreds included out of the box. The integration catalog refreshes from the cloud automatically, with or without a cloud link.',
            ],
            ['Models', 'Managed', 'Bring your own provider keys, or use cloud-hosted models through a cloud link'],
            [
              'Marketplace',
              'Built in',
              'The cloud marketplace, once the instance is linked to a cloud account. Until then, the Marketplace page only offers to connect.',
            ],
            [
              'Limits & billing',
              'Plans and credits',
              'No local plan limits. Cloud-hosted model usage and paid apps are billed to the linked cloud account.',
            ],
          ]}
        />
        <p>
          The Community Edition runs the backend as a single application plus the web app, with a few
          helper services (web search, page screenshots, and the bridge for command-line AI agents). It
          needs a PostgreSQL database with the pgvector extension (all your data), Redis (coordination and
          caching), and S3-compatible object storage such as MinIO for files. It is distributed under the
          LiveContext Sustainable Use License, a source-available license. See{' '}
          <a href="/self-host">Self-hosting</a> for how to run it, and the{' '}
          <a href={SELF_HOSTED_GITHUB_URL} target="_blank" rel="noopener noreferrer">
            public source repository
          </a>
          .
        </p>
        <p>
          A self-hosted instance runs fully on its own. Linking it to a cloud account adds four things:{' '}
          <strong>cloud-hosted models</strong> (model calls relayed and billed to that account instead of
          your own keys), <strong>model catalog updates</strong>, the cloud&apos;s{' '}
          <strong>shared skills</strong>, and the <strong>cloud marketplace</strong> (browse, install, and
          publish). You can disconnect the link from your instance, or revoke the instance from
          your cloud account, at any time. If the cloud is unreachable, your instance keeps working and
          only the cloud-dependent features pause.
        </p>
        <Callout variant="info">
          The edition is fixed when the app is built, not chosen per user or per request. There is no
          runtime toggle between Cloud and Community Edition.
        </Callout>

        <h2>Marketplace: publish and install</h2>
        <p>
          A marketplace publication is the whole working stack, not a screenshot: the workflow together
          with its agents, interfaces, tables, and files. Credentials are stripped at publish time, so your
          secrets never travel with it. Acquiring a publication clones the entire stack with fresh IDs
          into your own workspace as a run-only application. To change it or publish your own version,
          use <strong>Create an editable copy</strong> first.
        </p>
        <CardGrid cols={2}>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Install automations others built (the whole stack comes with them), and publish your own.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Run the Community Edition yourself, and optionally link it to a cloud account.
          </Card>
        </CardGrid>

        <h2>Map of the docs</h2>
        <p>
          The guides follow the order you meet LiveContext in, from your first run to sharing and hosting
          it:
        </p>
        <DocsTable
          head={['Section', 'Pages']}
          rowHeaders
          caption="Every section of the documentation and its pages"
          rows={DOCS_NAV.map((section) => [
            section.title,
            <span key={section.title}>
              {section.items.map((item, i) => (
                <span key={item.title}>
                  {i > 0 ? ', ' : null}
                  {item.href ? <a href={item.href}>{item.title}</a> : item.title}
                </span>
              ))}
            </span>,
          ])}
        />

        <Callout variant="tip" title="New here?">
          Read <a href="/concepts">Core concepts</a> first. It defines the handful of terms (run, node,
          trigger, version, agent, interface, credit) used throughout these guides, and the{' '}
          <a href="/glossary">Glossary</a> has the rest.
        </Callout>

        <CardGrid cols={2}>
          <Card icon={Sparkles} title="Studio" href="/studio">
            Generate images, video, voice, audio, and music.
          </Card>
          <Card icon={BookOpen} title="Glossary" href="/glossary">
            Every term used in these docs, defined in a sentence or two.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
