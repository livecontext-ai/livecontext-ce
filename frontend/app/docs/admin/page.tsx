import { Server, Cpu, User } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Administration',
  description:
    'The settings pages reserved for the platform administrator of a self-hosted install: AI providers, platform keys, node types, tool health, cloud connection, and version updates.',
  path: '/docs/admin',
});

export default function AdminPage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Administration"
        lead="A self-hosted Community Edition install has one or more platform administrators who configure it for everyone. This page covers who the administrator is, each settings page only they can open, and how to keep the install up to date."
      />

      <DocsProse>
        <h2>Who is the administrator</h2>
        <p>
          The administrator pages require the <strong>platform administrator</strong> role. Being the
          owner or an admin of an organization is not enough: every user owns their personal
          organization, and that does not reveal these pages. For a regular user the pages are simply
          absent from the settings list, and opening one by its address does not give access to it.
        </p>
        <h3>Becoming the administrator on a self-hosted install</h3>
        <p>
          On a fresh Community Edition install, the first person to register becomes the platform
          administrator, and lands on the first-run setup wizard (cloud connection, AI providers, CLI
          providers, platform credentials). Completing the wizard marks the install as set up: from
          then on no later account is promoted automatically, even if every user is removed. See{' '}
          <a href="/self-host">Self-hosting</a> for the wizard and for how teammates join afterwards.
        </p>
        <Callout title="Never on the cloud">
          <p>
            The first-user rule applies to the Community Edition only. On the LiveContext cloud,
            registering never grants the administrator role.
          </p>
        </Callout>

        <h2>Administrator pages on a self-hosted install</h2>
        <p>
          These pages appear in the settings list of a self-hosted administrator, in addition to the
          pages every user has (see <a href="/account">Account &amp; settings</a>).
        </p>
        <DocsTable
          caption="Administrator settings pages on a self-hosted install"
          rowHeaders
          head={['Page', 'What it controls']}
          rows={[
            [
              <strong key="p">Tool Health</strong>,
              'Tools that failed, with failure counts, rate and last error.',
            ],
            [
              <strong key="p">Platform Keys</strong>,
              'The OAuth2 and API credentials your integrations use to let users connect their accounts, and which integrations are available.',
            ],
            [
              <strong key="p">AI Providers</strong>,
              'The install’s LLM provider keys, the coding-agent CLI bridges, and the model catalog.',
            ],
            [
              <strong key="p">Node Types</strong>,
              'Which workflow node types are enabled.',
            ],
            [
              <strong key="p">Cloud</strong>,
              'The connection of this install to a LiveContext cloud account, and the signed catalog bundles it receives.',
            ],
          ]}
        />

        <h3>AI Providers</h3>
        <p>
          The page the agents and chat depend on. Its tabs are <strong>API Keys</strong> (a key per
          provider), <strong>Claude Code</strong>, <strong>Codex</strong>, <strong>Gemini CLI</strong>{' '}
          and <strong>Mistral Vibe</strong> (the CLI bridges), and <strong>Models</strong> (which models
          are enabled). The setup wizard fills the same settings. See{' '}
          <a href="/models">Models &amp; providers</a> for each tab in detail.
        </p>

        <h3>Platform Keys</h3>
        <p>
          The page is titled <strong>Platform Credentials</strong>. Platform credentials (Client ID,
          Client Secret, API keys) are what let your users connect their own accounts through OAuth2,
          configured here instead of environment variables. Each integration card shows{' '}
          <strong>Configured</strong> or <strong>Not configured</strong>, and offers:
        </p>
        <ul>
          <li>
            A switch that turns the integration on or off for the install, and an expandable list of
            its endpoints (<strong>Endpoints</strong>, with how many are active).
          </li>
          <li>
            <strong>Configure</strong> (or <strong>Edit</strong>) to enter the secret fields. When an
            integration has several authentication methods, each gets its own tab and can be disabled
            separately. Leave a secret field empty to keep its current value.
          </li>
          <li>
            <strong>Pricing</strong>, once a credential is saved: publish a <strong>Markup Pricing</strong>{' '}
            version, a default markup in credits per call plus per-tool overrides. Each publish creates
            a new version; runs already in flight keep the version they started with.
          </li>
        </ul>
        <p>
          Switch between <strong>All Integrations</strong> and <strong>Configured Only</strong> to find
          what is left to set up. Users then connect their own accounts from{' '}
          <strong>Credentials &amp; Variables</strong> (see <a href="/integrations">Integrations</a>).
        </p>

        <h3>Node Types</h3>
        <p>
          Enable or disable workflow node types, filtered by category (Triggers, Actions, Control
          Flow, AI, Data and more). The page also has <strong>Integrations</strong> and{' '}
          <strong>Capabilities</strong> tabs, and a <strong>Minimum plan</strong> per node, integration
          endpoint or capability (for example vector search or email alerts).
        </p>
        <Callout title="Minimum plans are not enforced on a self-hosted install">
          <p>
            Plan requirements gate features on the cloud. A Community Edition install is not
            restricted by them, so on your own install the switch that matters is{' '}
            <strong>Enabled</strong> or <strong>Disabled</strong>.
          </p>
        </Callout>
        <p>
          The node catalog itself is described in <a href="/nodes">Node reference</a>.
        </p>

        <h3>Tool Health</h3>
        <p>
          Lists the tools that failed, over a chosen <strong>Window</strong> (last days or{' '}
          <strong>All history</strong>) and above a <strong>Min calls</strong> threshold, with columns{' '}
          <strong>Failures</strong>, <strong>Rate</strong>, <strong>Tenants affected</strong>,{' '}
          <strong>Last call</strong> and <strong>Last error</strong>. Counts cover tool calls made by
          agents; calls made by a workflow node are not recorded per tool.
        </p>
        <p>
          The verdict separates a broken integration from one person&apos;s bad credential:{' '}
          <strong>All tenants</strong> (look at the integration first), <strong>Widespread</strong>,{' '}
          <strong>Isolated</strong> (most likely their credentials) and <strong>Single tenant</strong>{' '}
          (only one tenant calls it, so nothing can be concluded). On a single-user install you will
          mostly see <strong>Single tenant</strong>.
        </p>

        <h3>Cloud</h3>
        <p>
          The <strong>Connection</strong> tab (<strong>Cloud Connection</strong>) links this install to
          a LiveContext cloud account with <strong>Connect to Cloud</strong>, or unlinks it with{' '}
          <strong>Disconnect</strong>. The link is what gives your users the cloud marketplace, referral
          rewards and automatic catalog updates. The <strong>Bundles</strong> tab shows the sync status
          of the signed <strong>Model catalog</strong>, <strong>Integrations catalog</strong> and{' '}
          <strong>Skills</strong> bundles, with <strong>Sync now</strong>. Linking and bundles are
          detailed in <a href="/self-host">Self-hosting</a>.
        </p>

        <h2>Version and updates</h2>
        <p>
          On a self-hosted install, <strong>Settings</strong> &gt; <strong>Information</strong> starts
          with a <strong>Version</strong> card: the <strong>Edition</strong>, <strong>Version</strong>,{' '}
          <strong>Commit</strong> and <strong>Built on</strong> date of the running build. Every user
          of the install can see it; the version also appears next to <strong>About</strong> in the
          account menu.
        </p>
        <p>
          The install checks the public release feed once a day and on startup. When a newer release
          exists, the card shows <strong>Update available</strong> (or <strong>Security update</strong>{' '}
          in red when the release contains a security fix), with <strong>How to update</strong> and{' '}
          <strong>Release notes</strong>. An amber dot also appears on <strong>Information</strong> in
          the settings list and next to the version in the menu. Otherwise the card reads{' '}
          <strong>You&apos;re on the latest version</strong>.
        </p>
        <p>
          LiveContext never updates itself. <strong>How to update</strong> gives the commands to run
          from your cloned LiveContext folder. Pull the repository first: the Compose file pins the
          release image tag, so without it you would pull the old image again.
        </p>
        <CodeBlock language="bash" title="Update a self-hosted install">
{`git pull
docker compose pull
docker compose up -d`}
        </CodeBlock>
        <p>Your data is kept, and database migrations run automatically on startup.</p>
        <Callout title="What the update check sends">
          <p>
            The daily check sends the version and a random install id, so live installs can be
            counted; no IP address, hostname or account is kept. Set{' '}
            <code>CE_VERSIONCHECK_SENDINSTALLID=false</code> to stop sending the id, or{' '}
            <code>CE_VERSIONCHECK_ENABLED=false</code> to disable the check.
          </p>
        </Callout>
        <p>
          Below the version, <strong>Optional components</strong> shows whether the heavy features
          that are off by default are enabled: <strong>Interface screenshots &amp; PDFs</strong> and{' '}
          <strong>Browser agent &amp; web search</strong>.
        </p>

        <h2>Cloud operator pages</h2>
        <p>
          The settings code contains more administrator pages that serve the operator of the
          LiveContext cloud. They never appear on a Community Edition install:
        </p>
        <DocsTable
          caption="Administrator pages hidden on a self-hosted install"
          rowHeaders
          head={['Page', 'Purpose']}
          rows={[
            [<strong key="p">Credits &amp; Plans</strong>, 'Manually add credits or assign a subscription plan to a user.'],
            [<strong key="p">Publication Review</strong>, 'Review and approve marketplace publications before they go live.'],
            [<strong key="p">Marketplace Highlights</strong>, 'Pick and order the publications shown in the Highlights row.'],
            [<strong key="p">Verified Accounts</strong>, 'Grant or remove the blue check shown next to a name. Managed cloud only.'],
            [<strong key="p">Agent Debug</strong>, 'Visualize system prompts and test agent tools.'],
          ]}
        />
        <p>
          On the cloud, <strong>AI Providers</strong> also has an <strong>Execution links</strong> tab
          for the operator, and <strong>Cloud</strong> lists the self-hosted instances connected to the
          account instead of a connect button.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common administration problems on a self-hosted install"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'Tool Health, Platform Keys and the other administrator pages are missing from the settings list',
              'Your account does not have the platform administrator role. Owning an organization does not count.',
              'Ask the platform administrator of the install (on a fresh install, the first person who registered).',
            ],
            [
              'The version is unchanged after docker compose pull and docker compose up -d',
              'The Compose file pins the release image tag, so without the new Compose file the old image is pulled again.',
              'Run git pull in your cloned LiveContext folder first, then docker compose pull and docker compose up -d.',
            ],
            [
              'Update available never shows, although a newer release exists',
              'The install checks the release feed once a day and on startup only, or the check is disabled with CE_VERSIONCHECK_ENABLED=false.',
              'Wait for the next daily check or restart the install, and remove CE_VERSIONCHECK_ENABLED=false if it is set.',
            ],
            [
              'The Bundles tab shows a last fetch status of “not linked”',
              'The install is not linked to a cloud account, which is the normal state of a fresh install.',
              'Link it with Connect to Cloud on the Connection tab to start receiving catalog updates.',
            ],
            [
              'Tool Health says “No tool failed often enough to appear in this window.” while a workflow step keeps failing',
              'Tool Health counts tool calls made by agents only. Calls made by a workflow node are not recorded per tool. A short Window or a high Min calls also hides tools.',
              'Widen the Window (up to All history) and lower Min calls. For a workflow step, open the failed run instead.',
            ],
            [
              'Users cannot find an integration to connect',
              'On Platform Keys, the integration is Not configured, or its switch is off.',
              'Configure its credentials on Platform Keys and turn the integration on.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Install, setup wizard, cloud link and bundles.
          </Card>
          <Card icon={Cpu} title="Models & providers" href="/models">
            Provider keys, CLI bridges and the model catalog.
          </Card>
          <Card icon={User} title="Account & settings" href="/account">
            The settings pages every user has.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
