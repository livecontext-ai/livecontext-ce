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
} from 'lucide-react';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { docsMetadata } from './_meta';
import { DocsHero, DocsProse, DocsTable, CardGrid, Card, Steps, Step, Callout } from './_components';

export const metadata = docsMetadata({
  title: 'Overview',
  description:
    'LiveContext is one AI automation platform that is chat, workflow, app, and agent at once. Describe a job and it builds the automation in front of you, runs it with scoped, budgeted agents, and ships it as an app.',
  path: '/docs',
});

export default function DocsOverviewPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="What is LiveContext?"
        lead="LiveContext is one platform that is four things at once: a chat, a workflow builder, an app builder, and a fleet of agents. You describe a job in plain language, the automation builds itself in front of you, and you ship it as something your team, or your customers, can actually use."
      />

      <DocsProse>
        <p>
          Most teams stitch a job together across several tools: one product to chat with an AI, another
          to draw a workflow, a third to build the app, a fourth to run agents. LiveContext collapses
          that stack. You build once, and the same thing runs as a chat, a visual workflow, a shareable
          app, and a scheduled agent, with every step visible, every agent scoped and budgeted, and a
          full audit trail.
        </p>

        <h2>The four surfaces</h2>
        <p>
          These are not separate products, they are four views of the same automation. Start in any one
          of them and the others come along.
        </p>

        <CardGrid cols={2}>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            Describe what you want done. The assistant builds and edits the workflow for you, runs it, and
            shows you the results, you iterate by talking to it.
          </Card>
          <Card icon={Workflow} title="Workflow" href="/workflows">
            The same automation, drawn. Branch, loop, fan out in parallel, call agents and tools, a
            readable graph you can grow from five steps to fifty.
          </Card>
          <Card icon={LayoutPanelLeft} title="App / Interface" href="/interfaces">
            Wrap the workflow in a real web page: search, forms, cards, buttons. Share the link with your
            team or customers, or let an agent open it and click on its own.
          </Card>
          <Card icon={Bot} title="Agent" href="/agents">
            Always-on workers, one per job. Each gets its own model, a scoped set of tools, a credit
            budget it cannot exceed, and a full audit trail.
          </Card>
        </CardGrid>

        <h2>One model underneath: workflows, nodes, triggers</h2>
        <p>
          Whichever surface you start from, the underlying object is the same: a{' '}
          <strong>workflow</strong>, a graph of <strong>nodes</strong> connected by <strong>edges</strong>.
          A <strong>trigger</strong> starts a <strong>run</strong>; nodes execute in dependency order, and
          data flows forward through template expressions like{' '}
          <code>{'{{prefix:label.output.field}}'}</code>. Agents, interfaces, and tables are not bolted on
          beside the workflow, they are all kinds of node.
        </p>
        <p>
          Every node is identified by a normalized <code>prefix:label</code> key, and the prefix tells you
          what kind of node it is:
        </p>
        <DocsTable
          head={['Prefix', 'Kind', 'Examples']}
          rows={[
            [<code key="t">trigger:</code>, 'Entry point that starts a run', 'webhook, schedule, chat, form, manual, table row'],
            [<code key="m">mcp:</code>, 'Integration / tool operation', 'an API call, an HTTP request'],
            [<code key="a">agent:</code>, 'AI node', 'Agent, Guardrail, Classify'],
            [<code key="c">core:</code>, 'Control flow and utilities', 'Decision, Switch, Loop, Fork, Merge, Split, Transform, Wait'],
            [<code key="tb">table:</code>, 'Built-in spreadsheet operation', 'find, create, update, delete rows'],
            [<code key="i">interface:</code>, 'Web page', 'a page rendered in an iframe'],
            [<code key="n">note:</code>, 'Canvas note', 'documentation on the canvas, not executed'],
          ]}
        />
        <p>
          <a href="/concepts">Core concepts</a> covers the full mental model (runs, edges, ports, signals,
          credits); <a href="/nodes">Node reference</a> is the catalog of every node type.
        </p>

        <h2>How a build flows</h2>
        <Steps>
          <Step n={1} title="Describe the job in chat">
            Say what you want done in plain language.
          </Step>
          <Step n={2} title="LiveContext drafts a workflow">
            A graph of connected nodes appears, ready to run, branch, or extend.
          </Step>
          <Step n={3} title="Wire an interface">
            Wrap it in a web page so people, or agents, can interact with it.
          </Step>
          <Step n={4} title="Put an agent in the loop">
            Give it the exact tools and credit budget the job needs, nothing more.
          </Step>
        </Steps>
        <p>
          Triggers (a webhook, a schedule, a chat message, a new table row) can start the whole thing on
          their own, and every run is recorded so you can see exactly what happened.
        </p>

        <h2>Start here</h2>
        <CardGrid cols={3}>
          <Card icon={Rocket} title="Getting started" href="/getting-started">
            Build and run your first workflow from a single chat message.
          </Card>
          <Card icon={BookOpen} title="Core concepts" href="/concepts">
            The mental model: workflows, runs, nodes, triggers, agents, interfaces, tables, credits.
          </Card>
          <Card icon={Boxes} title="Node reference" href="/nodes">
            The catalog of building blocks, triggers, control flow, AI, data, integrations.
          </Card>
        </CardGrid>

        <h2>Cloud or self-hosted</h2>
        <p>
          LiveContext runs as a managed cloud service or as a self-hostable{' '}
          <strong>Community Edition (CE)</strong> you run on your own infrastructure. Both run the exact
          same workflow engine; what differs is how you run and operate it:
        </p>
        <DocsTable
          head={['', 'Cloud', 'Community Edition']}
          rows={[
            ['Hosting', 'Managed by LiveContext', 'You run it on your own infrastructure'],
            ['Sign-in', 'Keycloak sign-in (email or social); SAML SSO optional for orgs', 'Built-in email / social login'],
            ['Tenancy', 'Multi-organization', 'Single organization per instance'],
            ['Integrations', 'Full catalog, always current', 'Hundreds included out of the box; cloud link keeps the model catalog fresh'],
            ['Models', 'Managed', 'Bring your own keys, or relay to cloud-hosted models when linked'],
            ['Marketplace', 'Built in', 'Link to a cloud account to browse and acquire'],
            ['Limits & billing', 'Plans and credits', 'No plan limits, you own capacity'],
          ]}
        />
        <p>
          Community Edition bundles every backend service into a single process plus the web app. It
          needs a PostgreSQL database (all your data), Redis (coordination and caching), and
          S3-compatible object storage such as MinIO for files. See{' '}
          <a href="/self-host">Self-hosting</a> for how to run it, including the{' '}
          <a href={SELF_HOSTED_GITHUB_URL} target="_blank" rel="noopener noreferrer">
            open-source repository
          </a>
          .
        </p>
        <p>
          A self-hosted instance runs fully on its own, and can optionally link to a cloud account to
          unlock three things: the <strong>shared marketplace</strong> (browse, acquire, and publish to
          your cloud account), <strong>cloud-hosted models</strong> (relay model calls billed to that
          account instead of bringing your own keys), and a <strong>fresh model catalog</strong> kept up
          to date for you. You can reset the link (issue a new instance identity) or disconnect it at any
          time, and if the cloud is ever unreachable your instance keeps working, only the
          cloud-dependent features pause.
        </p>
        <Callout variant="info">
          The edition is fixed at build time, not per user or per request: a build with embedded
          authentication is Community Edition, everything else is cloud. There is no runtime toggle.
        </Callout>

        <h2>Marketplace: publish and fork</h2>
        <p>
          A marketplace publication is the whole working stack, not a screenshot: the workflow together
          with its agents, interfaces, tables, and files. Credentials are stripped at publish time, so
          your secrets never travel with it. Forking (acquiring) a publication clones the entire stack
          with fresh IDs into your own workspace as an independent copy you can run, edit, and even
          re-publish. The cloud marketplace is the shared central catalog; a self-hosted instance can
          link to it to browse and acquire, or keep its own local marketplace.
        </p>
        <CardGrid cols={2}>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Fork automations others built, the whole stack comes with it, and publish your own.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Run the Community Edition yourself, and optionally link it to a cloud account.
          </Card>
        </CardGrid>

        <h2>Map of the docs</h2>
        <p>These guides are organized the same way LiveContext is built, from first run to sharing it:</p>
        <DocsTable
          head={['Section', 'Pages']}
          rows={[
            ['Get started', 'Overview, Getting started, Core concepts'],
            ['Build', 'Chat, Workflows, Node reference, Triggers, Interfaces & apps, Runs & execution'],
            ['AI', 'Agents, Models & providers, Browser Agent, Skills'],
            ['Data', 'Tables & data, Integrations, Files & storage'],
            ['Share & host', 'Marketplace, Self-hosting, Organizations & roles, Plans & billing'],
            ['Reference', 'Expressions & variables, REST API & webhooks'],
          ]}
        />

        <Callout variant="info">
          New here? Read <a href="/concepts">Core concepts</a> first, it defines the handful of terms
          (run, node, trigger, agent, interface, credit) used throughout these guides.
        </Callout>
      </DocsProse>
    </>
  );
}
