import { Webhook, GitBranch, Bot, Table2, Plug, LayoutPanelLeft } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Node reference',
  description:
    'The catalog of LiveContext workflow nodes, grouped: triggers, control flow, AI nodes, table operations, integrations and actions, and interfaces - with the ports each branching node exposes.',
  path: '/docs/nodes',
});

export default function NodesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Node reference"
        lead="Every workflow is assembled from these building blocks. They are grouped by what they do; branching nodes also list the ports you connect from. For how they fit together, see Workflows."
      />

      <DocsProse>
        <h2>Triggers</h2>
        <p>A trigger starts a run. A workflow needs at least one; each runs its own graph.</p>
        <DocsTable
          head={['Trigger', 'Starts a run when...']}
          rows={[
            ['Webhook', 'an HTTP request arrives at the workflow URL.'],
            ['Schedule', 'a recurring time is reached (cron).'],
            ['Chat', 'a user sends a chat message.'],
            ['Form', 'a form is submitted.'],
            ['Manual', 'someone runs it on demand.'],
            ['Table', 'a row changes in a built-in table.'],
            ['Workflow', 'another workflow calls it.'],
          ]}
        />

        <h2>Control flow</h2>
        <p>
          <code>core:</code> nodes route, repeat, parallelize, and join. Branching ones expose ports
          (see <a href="/workflows">Workflows</a> for the full semantics).
        </p>
        <DocsTable
          head={['Node', 'What it does', 'Ports']}
          rows={[
            ['Decision', 'Takes the first branch whose boolean condition is true (exactly one).', <code key="p">if · elseif_N · else</code>],
            ['Switch', 'Runs the first case that matches a value (exactly one).', <code key="p">case_N · default</code>],
            ['Loop', 'Repeats its body while a condition holds, then exits.', <code key="p">body · iterate · exit</code>],
            ['Fork', 'Runs all branches in parallel - different tasks.', <code key="p">branch_N</code>],
            ['Split', 'Fans a list into N parallel item contexts - same task per item.', '-'],
            ['Merge', 'Waits for all predecessors, then continues (AND only).', '-'],
            ['Aggregate', 'Collects items from a Split into arrays.', '-'],
            ['Transform', 'Computes new fields from expressions.', '-'],
            ['Wait', 'Pauses for a duration (e.g. 5s, 5m, 1h, 1d).', '-'],
            ['User Approval', 'Pauses until someone approves or rejects.', <code key="p">approved · rejected · timeout</code>],
            ['Data Input', 'Collects a value to feed the run.', '-'],
            ['Response', 'Sends a message back to chat (text / markdown / HTML).', '-'],
            ['Exit', 'Ends the current branch (others continue).', '-'],
          ]}
        />

        <h2>AI nodes</h2>
        <p><code>agent:</code> nodes call a model. See <a href="/agents">Agents</a> for models, tools, and budgets.</p>
        <DocsTable
          head={['Node', 'What it does', 'Ports']}
          rows={[
            ['Agent', 'Reasons over a prompt and (optionally) calls tools to produce a result.', '-'],
            ['Classify', 'Routes input into exactly one category by meaning; returns the category and a confidence score.', <code key="p">category_N</code>],
            ['Guardrail', 'Checks content against rules and flags, blocks, or redacts it.', <code key="p">pass · fail</code>],
          ]}
        />
        <Callout variant="info">
          An agent&apos;s tool list controls access precisely: leave it empty to allow{' '}
          <strong>all</strong> tools, set it to an explicit empty selection to allow{' '}
          <strong>none</strong>, or list specific tools to allow exactly those. Tools are called by the
          agent at run time - they don&apos;t add edges to the graph.
        </Callout>

        <h2>Tables &amp; data</h2>
        <p><code>table:</code> nodes read and write the built-in spreadsheets. See <a href="/tables">Tables &amp; data</a>.</p>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Find rows', 'Queries rows by a condition, then processes each match in parallel (like Split).'],
            ['Read rows', 'Returns matching rows as a single output.'],
            ['Create row', 'Inserts a new row.'],
            ['Update row', 'Updates rows matching a condition.'],
            ['Delete row', 'Deletes rows matching a condition.'],
            ['Create column', 'Adds a column to a table.'],
          ]}
        />

        <h2>Integrations &amp; actions</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Integration step', 'Calls one operation of a connected integration (an mcp: tool from the catalog).'],
            ['HTTP Request', 'Calls any URL (GET/POST/PUT/PATCH/DELETE) with optional auth; returns status, data, and headers.'],
            ['Download File', 'Fetches a file from a URL into storage and returns a file reference for later steps or interfaces.'],
            ['Code', 'Runs a small custom script to shape data when no node fits.'],
          ]}
        />
        <Callout variant="warn">
          An HTTP Request to a URL that answers <code>404</code> or <code>500</code> still completes -{' '}
          <code>success</code> only means no transport error. Check the returned status code in a
          Decision to handle API errors.
        </Callout>

        <h2>Interfaces</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Interface', 'Renders a web page (in an iframe) that displays workflow data and collects user input. Can block the run or just display.'],
          ]}
        />

        <h2>Go deeper</h2>
        <CardGrid cols={3}>
          <Card icon={Webhook} title="Triggers" href="/triggers">Entry points in detail.</Card>
          <Card icon={GitBranch} title="Workflows" href="/workflows">Ports, branching, parallelism.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Configure the AI nodes.</Card>
          <Card icon={Table2} title="Tables &amp; data" href="/tables">CRUD and query semantics.</Card>
          <Card icon={Plug} title="Integrations" href="/integrations">Connect tools and APIs.</Card>
          <Card icon={LayoutPanelLeft} title="Interfaces" href="/interfaces">Build interactive pages.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
