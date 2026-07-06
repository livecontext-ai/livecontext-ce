import { Github, Store, Workflow, Server, KeyRound, RefreshCw } from 'lucide-react';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Self-hosting',
  description:
    'Run LiveContext Community Edition on your own infrastructure: how it differs from cloud, the required infra, the first-run setup wizard, bring-your-own-key and CLI bridge models, optionally linking a cloud account for signed catalog and skill bundle sync, and staying up to date.',
  path: '/docs/self-host',
});

export default function SelfHostPage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Self-hosting"
        lead="Community Edition (CE) is the free, open-source build of LiveContext you run yourself. It packs every backend service plus the web app into one process, keeps all your data in your own database, and can optionally link to a LiveContext cloud account for hosted models, the marketplace, and fresh catalogs."
      />

      <DocsProse>
        <h2>Cloud vs Community Edition</h2>
        <p>Both run the same workflow engine. The differences are about how you run and operate it:</p>
        <DocsTable
          head={['', 'Cloud', 'Community Edition']}
          rows={[
            ['Hosting', 'Managed by LiveContext', 'You run it on your own infrastructure, as one process'],
            [
              'Sign-in',
              'Keycloak: email/password or social login. SAML SSO is an optional add-on for Team/Enterprise organizations, not the base mechanism.',
              'Built-in embedded email/password login',
            ],
            [
              'Tenancy',
              'Multiple organizations, each with its own plan',
              'One organization supports multiple members via invite-link; creating additional organizations is capped by the owner’s plan (a standalone install defaults to 1)',
            ],
            [
              'Integrations',
              'Full catalog, always current',
              'Hundreds included out of the box, refreshed via a signed cloud-to-CE bundle',
            ],
            [
              'Models',
              'Managed hosted catalog, every provider available',
              'Bring your own keys per provider, or relay to cloud-hosted models when linked; two providers are blocked and one is off by default (see below)',
            ],
            ['Marketplace', 'Built in', 'Consumes the cloud marketplace once linked to a cloud account'],
            [
              'Limits & billing',
              'Plans, credit ceilings, Stripe billing',
              'No plan-limit gating, no per-node fee, no Stripe; credits are unlimited but usage is still tracked',
            ],
          ]}
        />

        <h2>Required infrastructure</h2>
        <p>
          The monolith needs three pieces of infrastructure alongside it. The shipped Docker Compose
          file provides all three by default, but each can point at your own managed service instead:
        </p>
        <DocsTable
          head={['Component', 'Role', 'Default']}
          rows={[
            [
              'PostgreSQL',
              'The single database. Everything lives here: workflows, runs, credentials, catalog data. The monolith runs its own database migrations in-process at startup.',
              'DB_HOST=localhost, DB_PORT=5432, DB_NAME=livecontext',
            ],
            [
              'Redis',
              'Backs the event bus (pub/sub), the key-value cache, and streaming run state. The monolith also runs its agent/classify/guardrail queue workers in the same JVM.',
              'REDIS_HOST=localhost, REDIS_PORT=6379',
            ],
            [
              'Object storage (S3-compatible)',
              'File storage for uploads and workflow file outputs, using path-style addressing.',
              'MinIO at http://localhost:9000, bucket workflow-files',
            ],
          ]}
        />
        <p>
          Code execution runs embedded rather than calling an external sandbox service, and web search
          is disabled by default. Mail defaults to a local SMTP relay with a console fallback, so a
          fresh install can still surface verification and notification emails in its logs without any
          mail server configured.
        </p>
        <Callout variant="warn">
          The database connection assumes UTC. If you&apos;re restoring data from a non-UTC source,
          adjust the timezone setting before the first <code>docker compose up</code>, since it&apos;s
          not something you can safely change after data has been written.
        </Callout>
        <Callout variant="warn">
          Set the credential-encryption secret and keep it safe: it&apos;s what protects stored API
          keys and OAuth tokens. The shipped Compose file auto-generates and persists it on first boot
          if you leave it blank, but if it&apos;s ever lost, every secret it protected becomes
          unrecoverable. Back it up like you would a signing key.
        </Callout>

        <h2>First-run setup wizard</h2>
        <p>
          The first admin account lands on a five-step setup wizard before reaching the app. It&apos;s
          admin-only: any other user is redirected straight to chat.
        </p>
        <Steps>
          <Step n={1} title="Cloud connection (recommended)">
            Connect a LiveContext cloud account through an OAuth flow, or explicitly skip it. This is
            the recommended path because it unlocks hosted model relay, the shared marketplace,
            community skills, and automatic catalog updates (see below); skipping keeps the install
            fully self-contained on bring-your-own-key models only.
          </Step>
          <Step n={2} title="AI providers (bring your own keys)">
            Paste API keys for the providers you want to use directly. The list only shows providers
            available on CE, so the two blocked providers never appear here.
          </Step>
          <Step n={3} title="CLI providers">
            Configure the coding-agent CLI bridge providers (Claude Code, Codex CLI, Gemini CLI,
            Mistral Vibe). &ldquo;Verify&rdquo; probes the bridge host, and if the CLI is found
            installed and authenticated, its access policy is automatically upgraded from disabled to
            admin-only so it becomes usable right away.
          </Step>
          <Step n={4} title="Platform credentials">
            Configure integration secrets for the tools you plan to use in agents and workflows (for
            example Gmail or Slack), through the same credential wizard used elsewhere in the app.
          </Step>
          <Step n={5} title="Done">
            Completing the wizard records the install as bootstrapped, server-side, once. There&apos;s
            nothing to redo on a page refresh or a different browser: the state lives in the database,
            not in local storage.
          </Step>
        </Steps>
        <Callout variant="info">
          If a TLS-intercepting antivirus or corporate proxy breaks the cloud OAuth token exchange
          during step 1, the wizard detects it and offers a one-click way to trust that proxy&apos;s
          certificate, rather than failing silently.
        </Callout>

        <h2>Models on a self-hosted install</h2>
        <p>
          CE sources models the same way cloud does, bring-your-own-key providers plus the CLI bridge,
          with one difference: not every provider is available.
        </p>
        <DocsTable
          head={['', 'Detail']}
          rows={[
            [
              'Enabled by default',
              'Anthropic, OpenAI, Google, and Mistral, each reading its own API-key environment variable.',
            ],
            [
              'Blocked entirely',
              <>
                <strong>OpenRouter</strong> and <strong>Cohere</strong> are hidden from the setup
                wizard and rejected at the backend. Multi-provider aggregation across every model is
                the hosted product&apos;s value, so cloud keeps every provider while CE does not offer
                these two.
              </>,
            ],
            [
              'Off by default, not blocked',
              <>
                <strong>DeepSeek</strong> isn&apos;t shipped as a default enabled provider on a fresh
                install. An admin opts in by enabling it in configuration, or a user can add their own
                DeepSeek key as a regular bring-your-own-key credential.
              </>,
            ],
            [
              'CLI bridge providers',
              'Claude Code, Codex, Gemini CLI, and Mistral Vibe route through a bridge running on the host machine. A CLI provider is only offered once the bridge confirms it’s installed and authenticated, same as on cloud.',
            ],
          ]}
        />
        <p>
          See <a href="/models">Models &amp; providers</a> for the full picture: reasoning effort,
          the admin model catalog, and model categories all work identically on CE once a provider is
          available.
        </p>

        <h2>Linking to a cloud account (optional)</h2>
        <p>
          Community Edition runs fully on its own. Linking it to a LiveContext cloud account is
          optional, done through the same OAuth wizard as step 1 above (also reachable later from
          settings), and unlocks:
        </p>
        <ul>
          <li>
            <strong>Cloud-hosted models</strong>, relayed to the linked account instead of bringing
            your own keys, across a broad set of providers.
          </li>
          <li>
            The <strong>shared marketplace</strong>, to browse, acquire, and publish.
          </li>
          <li>
            <strong>Community and global skills</strong> shared by the platform.
          </li>
          <li>
            <strong>Fresh catalogs</strong>, kept up to date automatically (see the next section).
          </li>
        </ul>
        <p>
          Linking exposes a status view and a single disconnect action; there is no separate
          &ldquo;reset&rdquo; or new-instance-identity action. To relink, disconnect first and then run
          the connect wizard again.
        </p>
        <p>
          If the cloud is ever unreachable, the instance keeps running on its own; only the
          cloud-dependent features (relay, marketplace, catalog freshness) pause until it&apos;s
          reachable again.
        </p>

        <h2>Keeping models &amp; integrations fresh</h2>
        <p>
          A linked CE install stays current through <strong>signed bundle sync</strong> from cloud,
          rather than by shipping updates through a new image release. Every bundle is verified
          against a shared trust key before being applied, so a compromised or spoofed source can&apos;t
          silently rewrite your catalogs.
        </p>
        <DocsTable
          head={['Bundle', 'What it keeps current', 'Behavior when unlinked']}
          rows={[
            [
              'LLM model catalog',
              'Which models exist and their pricing, synced on a recurring schedule plus at startup.',
              'Sync is skipped and the install keeps its last-seeded model list.',
            ],
            [
              'API catalog',
              'The hundreds of third-party API integrations, applied as an update-or-insert so your own custom APIs are never touched.',
              'Sync is skipped; your existing catalog and any custom APIs are untouched.',
            ],
            [
              'Skill bundle',
              'The cloud’s admin-managed global skills, applied as read-only rows that are active for everyone by default (a user can still hide one for themselves).',
              'Sync is skipped; locally created skills are unaffected.',
            ],
          ]}
        />
        <p>
          Every sync is gated behind an active cloud link on purpose: the fresh-catalog benefit is one
          of the reasons to link in the first place, and a bundle sync never overwrites edits an admin
          has already made locally, or custom models and custom APIs added on the instance.
        </p>
        <p>
          Independently of any link, a fresh install also seeds a starter model catalog and API
          catalog at boot, so CE is useful immediately even before it&apos;s ever linked to cloud.
        </p>

        <h2>Staying up to date</h2>
        <p>
          CE surfaces its own running build and lets you know when a newer one is available, without
          ever updating itself.
        </p>
        <ul>
          <li>
            A <strong>version card</strong>, shown only on self-hosted installs (cloud has no need
            for one), displays the running version, edition, build commit, and build time.
          </li>
          <li>
            CE periodically checks the cloud&apos;s public release feed to learn the latest published
            version, comparing it against its own. When it&apos;s behind, the card flips to an amber
            &ldquo;update available&rdquo; state, or red if the release is flagged as a security fix,
            and links to that release&apos;s notes.
          </li>
          <li>
            This check works whether or not the install is linked: it&apos;s an anonymous, public feed,
            not something gated behind cloud linking.
          </li>
        </ul>
        <Callout variant="info">
          LiveContext never auto-updates itself. The in-app &ldquo;how to update&rdquo; guidance walks
          through pulling the new image and restarting the stack yourself, the same way you&apos;d
          update any other self-hosted app.
        </Callout>
        <CodeBlock language="bash">
{`docker compose pull
docker compose up -d`}
        </CodeBlock>

        <h2>Signing in</h2>
        <p>
          CE authenticates locally with embedded email and password, no external identity provider
          required. The first person to register on a fresh install automatically becomes its first
          admin.
        </p>
        <p>
          Once the setup wizard has been completed, public registration closes automatically. From
          then on, an admin brings in teammates one of two ways:
        </p>
        <ul>
          <li>
            <strong>Invite link</strong>, the normal path: an admin invites by email, and the invitee
            registers through that link, joining the organization with the invited role even while
            public registration is otherwise closed.
          </li>
          <li>
            <strong>Reopen registration</strong>: an admin can flip public registration back open at
            any time, letting anyone create an account without an invite.
          </li>
        </ul>

        <h2>What CE includes vs omits</h2>
        <DocsTable
          head={['', '']}
          rows={[
            [
              'Included',
              'The full workflow engine, agent tools, hundreds of catalog integrations (seeded, then bundle-refreshed), embedded auth, embedded code execution, S3-compatible file storage, and unlimited resource usage.',
            ],
            [
              'Omitted or disabled',
              'Keycloak SSO, the external code-execution service, web search, Stripe and any billing provider, the per-node workflow fee, plan-limit resource gating, rate limiting, platform-credential markup, and the cloud LLM price multiplier (neutralized).',
            ],
            [
              'Model execution links',
              'A cloud-only monetization feature that decouples billed identity from the model that actually runs. Off by default on CE.',
            ],
            [
              'Credits',
              <>
                Unlimited: there is no ceiling on usage. Consumption is nonetheless still tracked in
                the ledger, at the true provider cost with no markup, so you retain full visibility
                into what you&apos;re spending even though nothing is capped or billed through
                LiveContext.
              </>,
            ],
          ]}
        />

        <h2>Running it</h2>
        <p>
          The public repository ships a ready-to-use Docker Compose file. It runs Postgres, Redis,
          MinIO (plus a one-shot job that creates the storage bucket), the CLI bridge, the LiveContext
          monolith, and the frontend as separate containers, wired together with sensible defaults. An
          optional web-search sidecar for the browser agent is off unless you opt into that profile.
        </p>
        <CodeBlock language="bash">
{`git clone ${SELF_HOSTED_GITHUB_URL}
cd livecontext-ce
docker compose up -d
# open http://localhost:3000`}
        </CodeBlock>
        <p>Operator-facing environment variables mirror the defaults above:</p>
        <DocsTable
          head={['Variable', 'Purpose']}
          rows={[
            ['DB_USERNAME / DB_PASSWORD', 'PostgreSQL credentials'],
            ['REDIS_HOST / REDIS_PORT', 'Redis connection, wired to the bundled redis service'],
            ['STORAGE_S3_*', 'Object storage endpoint and credentials, wired to the bundled MinIO'],
            ['JWT_SECRET', 'Signs and verifies the instance’s own access/refresh tokens'],
            [
              'CREDENTIAL_ENCRYPTION_PASSWORD / SALT',
              'Protects stored API keys and OAuth tokens; back this up',
            ],
            [
              'ANTHROPIC_API_KEY / OPENAI_API_KEY / GOOGLE_API_KEY / MISTRAL_API_KEY',
              'Bring-your-own-key providers, settable at deploy time or later in the setup wizard',
            ],
          ]}
        />
        <Callout variant="warn">
          Put a TLS-terminating reverse proxy (Caddy, Traefik, nginx) in front before exposing the app
          to anything beyond your own machine, and keep the database off the public internet.
        </Callout>

        <h2>Operating it</h2>
        <ul>
          <li>
            <strong>Back up PostgreSQL</strong> regularly. It holds everything, workflows, runs,
            credentials, catalog data, so restoring it restores your whole instance.
          </li>
          <li>
            <strong>Guard the encryption secret.</strong> Store it in a vault and never rotate it on a
            live instance without a migration plan; losing it makes stored secrets unrecoverable.
          </li>
          <li>
            <strong>Terminate TLS</strong> in front, and keep the database off the public internet.
          </li>
          <li>
            <strong>Plan capacity.</strong> There are no built-in resource limits on CE, so size the
            host and database for your actual load.
          </li>
        </ul>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={KeyRound} title="Models & providers" href="/models">
            Bring-your-own-key providers, the CLI bridge, and the admin model catalog in full.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            What linking to cloud unlocks: browsing, acquiring, and publishing.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Everything you build works the same on a self-hosted install.
          </Card>
          <Card icon={RefreshCw} title="Integrations" href="/integrations">
            The catalog of third-party APIs kept fresh by bundle sync.
          </Card>
          <Card icon={Server} title="Getting started" href="/getting-started">
            The broader tour of building your first agent or workflow.
          </Card>
          <Card icon={Github} title="Get the code" href={SELF_HOSTED_GITHUB_URL}>
            The open-source repository, with setup instructions and the Docker Compose file.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
