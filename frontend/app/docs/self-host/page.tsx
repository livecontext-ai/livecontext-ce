import { Github, Store, Workflow } from 'lucide-react';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Self-hosting',
  description:
    'Run LiveContext Community Edition on your own infrastructure: how it differs from cloud, what you need to run it, optionally linking to a cloud account for the marketplace and hosted models, and how to operate it.',
  path: '/docs/self-host',
});

export default function SelfHostPage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Self-hosting"
        lead="Community Edition is the free, open-source build of LiveContext you run yourself. It uses the same engine as the cloud, runs as a single process on your own infrastructure, and keeps all your data in your own database."
      />

      <DocsProse>
        <h2>Cloud vs. Community Edition</h2>
        <p>Both run the same workflow engine. The differences are about how you run and operate it:</p>
        <DocsTable
          head={['', 'Cloud', 'Community Edition']}
          rows={[
            ['Hosting', 'Managed by LiveContext', 'You run it on your own infrastructure'],
            ['Sign-in', 'SAML SSO (Keycloak)', 'Built-in email / social login'],
            ['Tenancy', 'Multi-organization', 'Single organization per instance'],
            ['Integrations', 'Full catalog, always current', 'Hundreds included out of the box; cloud link keeps the model catalog fresh'],
            ['Models', 'Managed', 'Bring your own keys, or relay to cloud-hosted models when linked'],
            ['Marketplace', 'Built in', 'Link to a cloud account to browse and acquire'],
            ['Limits & billing', 'Plans and credits', 'No plan limits - you own capacity'],
          ]}
        />

        <h2>Running it</h2>
        <p>
          Community Edition bundles every backend service into one process plus the web app. To run it
          you provide three things and start the container:
        </p>
        <ul>
          <li><strong>PostgreSQL</strong> - your database (everything lives here: workflows, runs, credentials, data).</li>
          <li><strong>Redis</strong> - for coordination and caching.</li>
          <li><strong>Object storage</strong> - MinIO or any S3-compatible bucket for files.</li>
        </ul>
        <p>
          Point it at those, open the app in your browser, and sign up to create the first account.
          The exact commands, environment variables, and a ready-to-use Docker Compose file are in the
          repository.
        </p>
        <CardGrid cols={2}>
          <Card icon={Github} title="Get the code" href={SELF_HOSTED_GITHUB_URL}>
            The open-source repository with setup instructions and a Docker Compose file.
          </Card>
        </CardGrid>
        <Callout variant="warn">
          Set your credential-encryption secret before the first run and keep it safe - it&apos;s what
          protects stored API keys and tokens, and if you lose it those secrets can&apos;t be
          recovered. Put a TLS reverse proxy (Caddy, Traefik, nginx) in front before exposing the app.
        </Callout>

        <h2>Linking to a cloud account (optional)</h2>
        <p>
          Community Edition runs fully on its own. Linking it to a LiveContext cloud account is optional
          and unlocks three things:
        </p>
        <ul>
          <li>The <strong>shared marketplace</strong> - browse, acquire, and publish to your cloud account.</li>
          <li><strong>Cloud-hosted models</strong> - relay model calls to the cloud, billed to that account, instead of bringing your own keys.</li>
          <li>A <strong>fresh model catalog</strong>, kept up to date for you.</li>
        </ul>
        <p>
          Linking is a quick sign-in wizard in settings. You can <strong>reset</strong> the link (issue
          a new instance identity - handy after cloning a VM) or <strong>disconnect</strong> it
          entirely; re-linking is the wizard again. If the cloud is ever unreachable, your instance keeps
          working - only the cloud-dependent features pause.
        </p>
        <Callout variant="info">
          Cloned a Community Edition VM that was already linked? Reset the link on the clone first, so
          the original and the copy don&apos;t share an identity.
        </Callout>

        <h2>Operating it</h2>
        <ul>
          <li><strong>Back up PostgreSQL</strong> regularly - it holds everything; restoring it restores your whole instance.</li>
          <li><strong>Guard the encryption secret</strong> - store it in a vault; never change it on a live instance.</li>
          <li><strong>Terminate TLS</strong> in front and keep the database off the public internet.</li>
          <li><strong>Plan capacity</strong> - there are no built-in limits, so size the host (and database) for your load.</li>
        </ul>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Store} title="Marketplace" href="/marketplace">What linking unlocks: publish and fork.</Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">Everything you build works the same in CE.</Card>
          <Card icon={Github} title="Overview" href="/">Back to the big picture.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
