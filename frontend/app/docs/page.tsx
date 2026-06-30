import { MessageSquare, Workflow, LayoutPanelLeft, Bot, Rocket, BookOpen, Boxes, Store, Server } from 'lucide-react';
import { docsMetadata } from './_meta';
import { DocsHero, DocsProse, CardGrid, Card, Callout } from './_components';

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
        lead="LiveContext is one platform that is four things at once: a chat, a workflow builder, an app builder, and a fleet of agents. You describe a job in plain language, the automation builds itself in front of you, and you ship it as something your team - or your customers - can actually use."
      />

      <DocsProse>
        <p>
          Most teams stitch a job together across several tools: one product to chat with an AI, another
          to draw a workflow, a third to build the app, a fourth to run agents. LiveContext collapses
          that stack. You build once, and the same thing runs as a chat, a visual workflow, a shareable
          app, and a scheduled agent - with every step visible, every agent scoped and budgeted, and a
          full audit trail.
        </p>

        <h2>The four surfaces</h2>
        <p>
          These are not separate products - they are four views of the same automation. Start in any one
          of them and the others come along.
        </p>

        <CardGrid cols={2}>
          <Card icon={MessageSquare} title="Chat" href="/chat">
            Describe what you want done. The assistant builds and edits the workflow for you, runs it, and
            shows you the results - you iterate by talking to it.
          </Card>
          <Card icon={Workflow} title="Workflow" href="/workflows">
            The same automation, drawn. Branch, loop, fan out in parallel, call agents and tools - a
            readable graph you can grow from five steps to fifty.
          </Card>
          <Card icon={LayoutPanelLeft} title="App / Interface" href="/interfaces">
            Wrap the workflow in a real web page: search, forms, cards, buttons. Share the link with your
            team or customers - or let an agent open it and click on its own.
          </Card>
          <Card icon={Bot} title="Agent" href="/agents">
            Always-on workers, one per job. Each gets its own model, a scoped set of tools, a credit
            budget it cannot exceed, and a full audit trail.
          </Card>
        </CardGrid>

        <h2>How a build flows</h2>
        <p>
          A typical path: you <strong>describe the job in chat</strong> → LiveContext drafts a{' '}
          <strong>workflow</strong> of connected nodes → you wire an <strong>interface</strong> so people
          (or agents) can interact with it → you put an <strong>agent</strong> in the loop with the exact
          tools and budget it needs. Triggers (a webhook, a schedule, a chat message, a new table row)
          start it on their own, and every run is recorded so you can see exactly what happened.
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
            The catalog of building blocks - triggers, control flow, AI, data, integrations.
          </Card>
        </CardGrid>

        <h2>Cloud or self-hosted</h2>
        <p>
          LiveContext runs as a managed cloud service or as a self-hostable{' '}
          <strong>Community Edition</strong> you run on your own infrastructure. A self-hosted instance
          can optionally link to a cloud account to reach the shared marketplace and stay current.
        </p>

        <CardGrid cols={2}>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Fork automations others built - the whole stack comes with it - and publish your own.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Run the Community Edition yourself, and optionally link it to a cloud account.
          </Card>
        </CardGrid>

        <Callout variant="tip">
          New here? Read <a href="/concepts">Core concepts</a> first - it defines the handful of
          terms (run, node, trigger, agent, interface, credit) used throughout these guides.
        </Callout>
      </DocsProse>
    </>
  );
}
