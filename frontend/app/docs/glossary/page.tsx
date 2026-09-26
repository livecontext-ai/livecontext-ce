import type { ReactNode } from 'react';
import { docsMetadata } from '../_meta';
import { BookOpen, Braces, Rocket } from 'lucide-react';
import { DocsHero, DocsProse, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Glossary',
  description:
    'Definitions of the terms used across the LiveContext docs: workflow, node, trigger, run, epoch, agent, interface, table, credit, Community Edition, and more.',
  path: '/docs/glossary',
});

interface Term {
  term: string;
  definition: ReactNode;
}

// Alphabetical. Each definition is one or two sentences and links to the page
// that covers the subject in depth. Grouped by first letter below, so the
// "On this page" sidebar doubles as an A-Z index.
const TERMS: Term[] = [
  {
    term: 'Agent',
    definition: (
      <>
        An AI worker that calls a model, reasons, and uses a scoped set of tools within a credit budget it
        cannot exceed. It can run inside a workflow as an Agent node or on its own. See{' '}
        <a href="/agents">Agents</a>.
      </>
    ),
  },
  {
    term: 'Application',
    definition: (
      <>
        A workflow that has an entry interface, used as a web app by the people you share it with. See{' '}
        <a href="/interfaces">Interfaces &amp; apps</a>.
      </>
    ),
  },
  {
    term: 'Budget',
    definition: (
      <>
        The maximum number of credits an agent may spend. A sub-agent&apos;s budget is reserved from its
        parent&apos;s, so the whole tree is capped by the top budget. See <a href="/agents">Agents</a>.
      </>
    ),
  },
  {
    term: 'Chat channel',
    definition: (
      <>
        An external messaging app linked to your account, where agents can reach you outside LiveContext. See{' '}
        <a href="/channels">Chat channels</a>.
      </>
    ),
  },
  {
    term: 'Cloud',
    definition: (
      <>
        The managed LiveContext service, with plans, credits, and multiple organizations. Compare with{' '}
        <em>Community Edition</em>.
      </>
    ),
  },
  {
    term: 'Cloud link',
    definition: (
      <>
        An optional connection from a self-hosted instance to a cloud account. It unlocks the shared
        marketplace, cloud-hosted models, and model catalog updates. See <a href="/self-host">Self-hosting</a>.
      </>
    ),
  },
  {
    term: 'Community Edition (CE)',
    definition: (
      <>
        The self-hosted edition you run on your own infrastructure. It runs the same workflow engine as the
        cloud. See <a href="/self-host">Self-hosting</a>.
      </>
    ),
  },
  {
    term: 'Credential',
    definition: (
      <>
        A saved secret (an OAuth connection, an API key, a password) that lets a node act on a third-party
        service on your behalf. Credentials never travel with a marketplace publication. See{' '}
        <a href="/integrations">Integrations</a>.
      </>
    ),
  },
  {
    term: 'Credit',
    definition: (
      <>
        The unit LiveContext uses to meter work that costs money, such as model calls and media generation.
        See <a href="/billing">Plans &amp; billing</a>.
      </>
    ),
  },
  {
    term: 'Decision',
    definition: (
      <>
        A control node that takes exactly one branch: the first condition that matches (
        <code>if</code>, <code>elseif_N</code>) or <code>else</code>. See <a href="/nodes">Node reference</a>.
      </>
    ),
  },
  {
    term: 'Edge',
    definition: (
      <>
        A connection from one node to another. It sets the order of execution only; it does not carry data.
        See <a href="/workflows">Workflows</a>.
      </>
    ),
  },
  {
    term: 'Epoch',
    definition: (
      <>
        One firing of a trigger inside a run. Each fire opens a new epoch, and the results of every epoch stay
        browsable. See <a href="/runs">Runs &amp; execution</a>.
      </>
    ),
  },
  {
    term: 'Expression',
    definition: (
      <>
        A template such as <code>{'{{mcp:fetch_user.output.email}}'}</code> that reads a value produced
        earlier in the run. See <a href="/expressions">Expressions &amp; variables</a>.
      </>
    ),
  },
  {
    term: 'FileRef',
    definition: (
      <>
        The object that stands for a stored file in node outputs (name, path, type, size). Pass it whole to a
        node that takes a file. See <a href="/files">Files &amp; storage</a>.
      </>
    ),
  },
  {
    term: 'Fork',
    definition: (
      <>
        Running several branches in parallel. It happens with a Fork node or whenever a node has more than one
        outgoing edge. See <a href="/workflows">Workflows</a>.
      </>
    ),
  },
  {
    term: 'Integration',
    definition: (
      <>
        A third-party service (an API) you can call from a workflow or an agent once you have connected a
        credential for it. See <a href="/integrations">Integrations</a>.
      </>
    ),
  },
  {
    term: 'Interface',
    definition: (
      <>
        A web page (HTML, CSS, JavaScript) that shows workflow data and collects input. In a workflow it can
        pause the run until the user continues. See <a href="/interfaces">Interfaces &amp; apps</a>.
      </>
    ),
  },
  {
    term: 'Label',
    definition: (
      <>
        The name you give a node. It is normalized into the node key (lowercase, accents removed, every other
        character turned into <code>_</code>), so <em>My API Call</em> becomes <code>my_api_call</code>.
      </>
    ),
  },
  {
    term: 'Loop',
    definition: (
      <>
        A control node that repeats its body until its exit condition is met. The last step of the body
        connects back into the loop&apos;s <code>iterate</code> input. See <a href="/nodes">Node reference</a>.
      </>
    ),
  },
  {
    term: 'Marketplace',
    definition: (
      <>
        The catalog where people publish complete automations (workflow, agents, interfaces, tables, files)
        and others fork them. See <a href="/marketplace">Marketplace</a>.
      </>
    ),
  },
  {
    term: 'MCP',
    definition: (
      <>
        Model Context Protocol, the open standard AI clients use to call tools. LiveContext exposes its tools
        to external MCP clients. See <a href="/mcp-server">MCP server</a>.
      </>
    ),
  },
  {
    term: 'Merge',
    definition: (
      <>
        Waiting for every incoming branch before continuing. It happens with a Merge node or whenever a node
        has more than one incoming edge. See <a href="/workflows">Workflows</a>.
      </>
    ),
  },
  {
    term: 'Node',
    definition: (
      <>
        One step of a workflow, identified by a key of the form <code>prefix:label</code>. The prefix (
        <code>trigger:</code>, <code>mcp:</code>, <code>agent:</code>, <code>core:</code>,{' '}
        <code>table:</code>, <code>interface:</code>, <code>note:</code>) tells you its kind. See{' '}
        <a href="/nodes">Node reference</a>.
      </>
    ),
  },
  {
    term: 'Organization',
    definition: (
      <>
        A workspace with members and roles. Every account has a personal one, and a team shares one; resources created in it belong to that workspace. See{' '}
        <a href="/organizations">Organizations &amp; roles</a>.
      </>
    ),
  },
  {
    term: 'Pin',
    definition: (
      <>
        Marking one version of a workflow as the one its production triggers (webhooks, schedules,
        public chat and form links, table events, chained workflows) run. See <a href="/triggers">Triggers</a>.
      </>
    ),
  },
  {
    term: 'Port',
    definition: (
      <>
        A named output of a branching node, written <code>core:label:port</code>, for example{' '}
        <code>core:check:if</code>. Each port leads to one successor. See <a href="/workflows">Workflows</a>.
      </>
    ),
  },
  {
    term: 'Run',
    definition: (
      <>
        One execution of a workflow. It records the status and output of every step and stays browsable. See{' '}
        <a href="/runs">Runs &amp; execution</a>.
      </>
    ),
  },
  {
    term: 'Signal',
    definition: (
      <>
        A point where a run pauses until something happens: a timer, an approval, a webhook, a user action on an
        interface, a background agent run, or a Browser Agent takeover. See <a href="/runs">Runs &amp; execution</a>.
      </>
    ),
  },
  {
    term: 'Skill',
    definition: (
      <>
        A reusable set of instructions an agent loads when a task calls for it. See <a href="/skills">Skills</a>.
      </>
    ),
  },
  {
    term: 'Spawn',
    definition: (
      <>
        A re-execution of nodes within the same epoch, for example a loop iteration or a retry. See{' '}
        <a href="/runs">Runs &amp; execution</a>.
      </>
    ),
  },
  {
    term: 'Split',
    definition: (
      <>
        A node that fans a list out into one parallel branch per item. Inside the branch,{' '}
        <code>{'{{item}}'}</code> is the current item. See <a href="/nodes">Node reference</a>.
      </>
    ),
  },
  {
    term: 'Step-by-step mode',
    definition: (
      <>
        An execution mode that pauses after each node so you can advance one step at a time, useful for
        debugging. The default is automatic mode. See <a href="/runs">Runs &amp; execution</a>.
      </>
    ),
  },
  {
    term: 'Studio',
    definition: (
      <>
        The place where you generate images, video, and audio from a prompt. See <a href="/studio">Studio</a>.
      </>
    ),
  },
  {
    term: 'Switch',
    definition: (
      <>
        A control node that compares a value with a list of cases and runs the first match (
        <code>case_N</code>) or <code>default</code>. See <a href="/nodes">Node reference</a>.
      </>
    ),
  },
  {
    term: 'Table',
    definition: (
      <>
        A built-in spreadsheet with a flexible schema that workflows and agents read and write. See{' '}
        <a href="/tables">Tables &amp; data</a>.
      </>
    ),
  },
  {
    term: 'Trigger',
    definition: (
      <>
        The node that starts a run: a webhook, a schedule, a chat message, a form, a manual click, a table
        change, another workflow, or an error. See <a href="/triggers">Triggers</a>.
      </>
    ),
  },
  {
    term: 'Version',
    definition: (
      <>
        A saved state of a workflow&apos;s plan. Runs execute a specific version, and you pin the one that
        serves production. See <a href="/workflows">Workflows</a>.
      </>
    ),
  },
  {
    term: 'Workflow',
    definition: (
      <>
        An automation: a graph of nodes joined by edges, started by one or more triggers. See{' '}
        <a href="/workflows">Workflows</a>.
      </>
    ),
  },
];

function groupByLetter(terms: Term[]): [string, Term[]][] {
  const groups = new Map<string, Term[]>();
  for (const t of [...terms].sort((a, b) => a.term.localeCompare(b.term, 'en'))) {
    const letter = t.term[0].toUpperCase();
    groups.set(letter, [...(groups.get(letter) ?? []), t]);
  }
  return Array.from(groups.entries());
}

export default function GlossaryPage() {
  const groups = groupByLetter(TERMS);
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Glossary"
        lead="The words used across these docs, defined in a sentence or two, with a link to the page that covers each one in depth."
      />

      <DocsProse>
        <nav aria-label="Glossary letters">
          <ul className="docs-glossary-letters">
            {groups.map(([letter]) => (
              <li key={letter}>
                <a href={`#letter-${letter.toLowerCase()}`}>{letter}</a>
              </li>
            ))}
          </ul>
        </nav>

        {groups.map(([letter, terms]) => (
          // A plain section (no accessible name), so the letter groups do not each become a
          // region landmark: the h2 already makes every letter reachable by heading.
          <section key={letter}>
            <h2 id={`letter-${letter.toLowerCase()}`}>{letter}</h2>
            <dl className="docs-glossary">
              {terms.map((t) => (
                <div key={t.term}>
                  <dt>{t.term}</dt>
                  <dd>{t.definition}</dd>
                </div>
              ))}
            </dl>
          </section>
        ))}

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={BookOpen} title="Core concepts" href="/concepts">
            How runs, nodes, versions, and credits fit together.
          </Card>
          <Card icon={Rocket} title="Getting started" href="/getting-started">
            Build and run your first workflow.
          </Card>
          <Card icon={Braces} title="Expressions & variables" href="/expressions">
            The template syntax that moves data between steps.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
