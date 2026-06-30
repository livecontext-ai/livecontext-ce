import { Server, LayoutPanelLeft, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Marketplace',
  description:
    'Publish and acquire automations on the LiveContext marketplace: visibility and showcase runs, what a publication snapshots, how forking gives you a full independent clone, updates and re-acquisition, and cloud vs self-hosted.',
  path: '/docs/marketplace',
});

export default function MarketplacePage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Marketplace"
        lead="Publish what you build, fork what others share. A publication isn't a screenshot - it's the whole working stack. Acquire one and you get your own independent copy of the workflow, its agents, its pages, its tables, even its files."
      />

      <DocsProse>
        <h2>Publishing</h2>
        <p>When you publish a workflow you choose how visible it is and what visitors preview:</p>
        <DocsTable
          head={['Visibility', 'Who sees it', 'Needs a showcase run?']}
          rows={[
            ['Public', 'Listed on the marketplace.', 'Yes'],
            ['Unlisted', 'Anyone with the direct link.', 'Yes'],
            ['Private', 'Only you (or people you share it with).', 'No'],
          ]}
        />
        <p>
          A <strong>showcase run</strong> is a frozen preview captured at publish time, so visitors see
          the real thing in motion without ever touching your live workflow. It must be a completed
          automatic run (a paused, step-by-step run can&apos;t be used as a showcase). Publishing
          captures a self-contained snapshot of the workflow <strong>and everything it uses</strong> -
          agents, interfaces, tables, and files.
        </p>
        <Callout variant="warn">
          Credentials are <strong>stripped</strong> at publish time - API keys, HTTP auth, email
          credentials. Your secrets never travel with a publication; acquirers connect their own.
        </Callout>
        <p>
          Public publications are free; if you want to charge, use <strong>unlisted</strong> or{' '}
          <strong>private</strong>. An agent&apos;s tool access is also tightened to just the resources
          in the published plan, so a forked agent can&apos;t reach beyond what you shipped.
        </p>

        <h2>What forking gives you</h2>
        <p>
          Acquiring (forking) a publication clones the entire stack into your workspace with fresh
          IDs - independent copies of every interface, agent, table, and file, with all the internal
          references rewritten to point at <em>your</em> new resources. The result is a normal workflow
          in your list that you can run, edit, and even re-publish as your own. Because credentials were
          stripped, connect your own before the integration and email steps will run.
        </p>

        <h2>Updates &amp; re-acquiring</h2>
        <p>
          Publishing an update <strong>does not</strong> push to people who already forked - every copy
          is independent. To pick up a newer version, fork again; you&apos;ll get the latest snapshot.
          And because a <strong>receipt</strong> is kept when you acquire, re-acquiring later - even after
          you deleted your copy - is free.
        </p>
        <Callout variant="info">
          The flip side of independence: an automation you forked won&apos;t change under you, and your
          edits never affect the original. If you want the publisher&apos;s latest, re-acquire on
          purpose.
        </Callout>

        <h2>Cloud &amp; self-hosted</h2>
        <p>
          The cloud marketplace is the shared, central catalog. A self-hosted{' '}
          <a href="/self-host">Community Edition</a> can link to a cloud account to browse and
          acquire from it, and can also keep its own local marketplace for internal automations.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={LayoutPanelLeft} title="Interfaces &amp; apps" href="/interfaces">Package a workflow as a shareable app.</Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">Build the thing you&apos;ll publish.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Run your own instance, linked to cloud.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
