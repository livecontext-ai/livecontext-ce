import { Github, Store, KeyRound, RefreshCw, ShieldCheck, CreditCard } from 'lucide-react';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step, CodeBlock } from '../_components';

export const metadata = docsMetadata({
  title: 'Self-hosting',
  description:
    'Install and run LiveContext Community Edition: license, requirements, install with npx or Docker Compose, configuration, the setup wizard, linking a cloud account, updates, and backups.',
  path: '/docs/self-host',
});

export default function SelfHostPage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Self-hosting"
        lead="Community Edition (CE) is the free, self-hosted build of LiveContext. It runs every backend service plus the web app with Docker Compose, keeps all your data on your own machine, and can optionally link to a LiveContext Cloud account for hosted models, the marketplace, shared skills, and fresh model catalogs."
      />

      <DocsProse>
        <h2>License</h2>
        <p>
          CE is published under the LiveContext Sustainable Use License 1.0. You may
          use, copy, modify, and redistribute it for free, including in production and for your
          organization&apos;s internal business. What the license forbids is offering it to third
          parties, hosted or embedded, to compete with LiveContext&apos;s paid versions. Read the{' '}
          <code>LICENSE</code> file in the repository for the exact terms.
        </p>

        <h2>Cloud vs Community Edition</h2>
        <p>Both run the same workflow engine. The differences are about how you run and operate it:</p>
        <DocsTable
          head={['Topic', 'Cloud', 'Community Edition']}
          caption="Differences between LiveContext Cloud and Community Edition"
          rowHeaders
          rows={[
            ['Hosting', 'Managed by LiveContext', 'You run it on your own infrastructure with Docker Compose'],
            ['Sign-in', 'Email and password, social login, and workspace SAML SSO (Team and Enterprise)', 'Built-in email and password; no SAML SSO'],
            [
              'Integrations',
              'Full catalog, always current',
              'The catalog shipped with the release, then refreshed automatically from the cloud (see below)',
            ],
            [
              'Models',
              'Hosted catalog across many providers',
              'Your own provider keys and CLI tools, or cloud-hosted models once linked; OpenRouter and Cohere are not available',
            ],
            ['Marketplace', 'Built in', 'The cloud marketplace, once the instance is linked to a cloud account (the page only offers to connect until then)'],
            [
              'Limits and billing',
              'Plans, credit limits, Stripe billing',
              'No plan-limit gating, no Stripe; credits are unlimited but usage is still tracked',
            ],
          ]}
        />

        <h2>Requirements</h2>
        <ul>
          <li>Docker Engine 24 or later with Compose v2, or Docker Desktop with Linux containers.</li>
          <li>4 GB of RAM minimum, 8 GB recommended, and several GB of free disk for images and data.</li>
          <li>
            A <code>linux/amd64</code> (x86-64) machine. ARM64 images depend on the release: check that
            the release you install publishes <code>linux/arm64</code> images before using Apple
            Silicon, Raspberry Pi, or other ARM hardware.
          </li>
          <li>For the npm launcher: Node.js LTS with npm. For the repository install: Git.</li>
          <li>For AI features: your own provider key, or a connected LiveContext Cloud account.</li>
        </ul>

        <h2>Install</h2>
        <p>
          Choose one method. Do not run both on the same machine: they use the same container names and
          ports.
        </p>

        <h3>Option 1: npm launcher (local install)</h3>
        <p>Run this from the folder where you want to keep the configuration:</p>
        <CodeBlock language="bash">{`npx livecontext@latest`}</CodeBlock>
        <p>
          The launcher pulls the images, starts the stack, and prints the app URL (normally{' '}
          <code>http://localhost:3000</code>). From the same folder, manage it with{' '}
          <code>npx livecontext@latest status</code>, <code>logs</code>, <code>down</code>, and{' '}
          <code>update</code>. Configuration lives in <code>./livecontext</code>: copy{' '}
          <code>livecontext/.env.example</code> to <code>livecontext/.env</code> to change settings. The
          optional add-ons below need Option 2.
        </p>

        <h3>Option 2: Docker Compose (local or server)</h3>
        <Steps>
          <Step n={1} title="Get the code and create your configuration file">
            <CodeBlock language="bash">{`git clone ${SELF_HOSTED_GITHUB_URL}.git
cd livecontext-ce
cp docker/.env.ce.example .env`}</CodeBlock>
          </Step>
          <Step n={2} title="Edit .env before the first start">
            On a server, set your own <code>DB_PASSWORD</code>, <code>MINIO_ROOT_USER</code>, and{' '}
            <code>MINIO_ROOT_PASSWORD</code>. Leave the encryption settings empty: the first start
            generates the keys and keeps them. Do not change database credentials or encryption keys on
            an existing install without a backup and a migration plan.
          </Step>
          <Step n={3} title="Start the stack">
            <CodeBlock language="bash">{`docker compose up -d
docker compose ps`}</CodeBlock>
            The first start downloads several GB and prepares the database. Wait until{' '}
            <code>livecontext</code> is healthy, then open <code>http://localhost:3000</code> (or the
            port set in <code>FRONTEND_PORT</code>).
          </Step>
          <Step n={4} title="Create the first account">
            The first person to register becomes the install&apos;s administrator and goes through the
            setup wizard.
          </Step>
        </Steps>
        <p>
          The stack runs PostgreSQL, Redis, MinIO (S3-compatible file storage, plus a one-time job that
          creates its bucket), the tools bridge, the LiveContext backend, and the web app. Code execution
          runs inside the backend; no separate sandbox service is needed.
        </p>

        <h3>Running it on a server, NAS, or VPS</h3>
        <p>
          The browser must reach two ports: the web app (<code>FRONTEND_PORT</code>, 3000 by default)
          and the backend (<code>BACKEND_PORT</code>, 8080 by default). Tell the install its public
          addresses in <code>.env</code>, without a trailing slash, so that email links
          and OAuth callbacks point at the server rather than at localhost:
        </p>
        <CodeBlock language="bash" title=".env (LAN example)">{`PUBLIC_BASE_URL=http://192.168.1.50:3000
GATEWAY_PUBLIC_URL=http://192.168.1.50:8080`}</CodeBlock>
        <p>
          For internet access, use HTTPS with a reverse proxy (Caddy, Traefik, nginx). A simple setup
          uses two hostnames: one proxied to port 3000, one to port 8080 with WebSocket upgrades, for
          example <code>PUBLIC_BASE_URL=https://app.example.com</code> and{' '}
          <code>GATEWAY_PUBLIC_URL=https://api.example.com</code>. Then run{' '}
          <code>docker compose up -d</code> again. Register{' '}
          <code>&lt;GATEWAY_PUBLIC_URL&gt;/api/credentials/oauth2/callback</code> as the redirect URL with
          each integration provider whose account you connect through OAuth.
        </p>
        <Callout variant="warn">
          Keep the database and MinIO off the public internet, and expose the app only through TLS.
        </Callout>

        <h2>Configuration reference</h2>
        <p>
          All settings go in the <code>.env</code> file at the repository root. Compose reads it on
          every command. Main variables (names and purpose; the example file has the defaults):
        </p>
        <DocsTable
          head={['Variable', 'Purpose']}
          caption="Main CE environment variables"
          rowHeaders
          rows={[
            [<code key="v">DB_USERNAME</code>, 'PostgreSQL user.'],
            [<code key="v">DB_PASSWORD</code>, 'PostgreSQL password. Change it before exposing the install.'],
            [<code key="v">MINIO_ROOT_USER</code>, 'MinIO user, also used by the app to store files.'],
            [<code key="v">MINIO_ROOT_PASSWORD</code>, 'MinIO password, also used by the app to store files.'],
            [
              <code key="v">CREDENTIAL_ENCRYPTION_PASSWORD</code>,
              'Protects stored API keys and OAuth tokens. Leave empty to have it generated on first start, then back it up.',
            ],
            [<code key="v">CREDENTIAL_ENCRYPTION_SALT</code>, 'Companion of the encryption password. Same rules.'],
            [<code key="v">FRONTEND_PORT</code>, 'Port of the web app (default 3000).'],
            [<code key="v">BACKEND_PORT</code>, 'Port of the backend (default 8080).'],
            [<code key="v">PUBLIC_BASE_URL</code>, 'Address where browsers reach the web app. Used in email links and OAuth. No trailing slash.'],
            [<code key="v">GATEWAY_PUBLIC_URL</code>, 'Address where browsers reach the backend. Used for OAuth callbacks. No trailing slash.'],
            [
              <code key="v">ANTHROPIC_API_KEY</code>,
              'Optional provider key. The same goes for OPENAI_API_KEY, GOOGLE_API_KEY, GEMINI_API_KEY, MISTRAL_API_KEY, and DEEPSEEK_API_KEY. Keys can also be added later in the app.',
            ],
            [<code key="v">MAIL_HOST</code>, 'Your SMTP relay. See Email below. Related: MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM.'],
            [<code key="v">MAIL_SMTP_STARTTLS</code>, 'Encrypts mail when the relay supports it (on by default). Leave it on.'],
            [<code key="v">COMPOSE_PROFILES</code>, 'Turns on optional add-ons: renderer, browser-agent, or both.'],
            [<code key="v">SCREENSHOT_RENDERER_URL</code>, 'Connects the app to the renderer add-on.'],
            [<code key="v">WEBSEARCH_ENABLED</code>, 'Turns on web search and the Browser Agent (with the browser-agent profile).'],
            [<code key="v">CE_VERSIONCHECK_ENABLED</code>, 'Set to false to turn off the daily update check.'],
            [<code key="v">CE_VERSIONCHECK_SENDINSTALLID</code>, 'Set to false to keep the update check without the anonymous install id.'],
            [<code key="v">CHANGELOG_ENABLED</code>, "Set to false to remove the in-app \"What's new\" panel."],
          ]}
        />
        <p>
          The backend reads these at startup: run <code>docker compose up -d</code> after changing them.
        </p>

        <h3>Email (SMTP)</h3>
        <p>
          Nothing is required to run, but without a working relay every email is lost, including the
          password reset link, which is the only way back in for a locked-out administrator. Set{' '}
          <code>MAIL_HOST</code> and <code>MAIL_PORT</code> (587 on a real relay), set both{' '}
          <code>MAIL_USERNAME</code> and <code>MAIL_PASSWORD</code> or neither, and use a{' '}
          <code>MAIL_FROM</code> address your relay accepts. If your relay uses a certificate from a
          private certificate authority, mount that CA (see the next section) instead of turning TLS off.
          CE never emails workspace invitations: it uses copyable invite links instead.
        </p>

        <h3>Behind a TLS-intercepting proxy</h3>
        <p>
          If a corporate proxy or antivirus intercepts TLS, the setup wizard detects it during the cloud
          connection and offers a one-click way to trust that proxy&apos;s certificate. To make it
          permanent, put the proxy&apos;s root CA (PEM) in a folder, mount it as{' '}
          <code>./extra-ca:/app/extra-ca:ro</code> on the <code>livecontext</code> service, and set{' '}
          <code>NODE_EXTRA_CA_CERTS</code> to that file on the <code>bridge</code> service.
        </p>

        <h2>Optional add-ons</h2>
        <p>
          Both are off by default, need extra memory and disk, and require the repository install. Put
          the settings in <code>.env</code> so every start, update, and stop keeps them.
        </p>
        <DocsTable
          caption="Optional add-ons and their .env settings"
          head={['Add-on', 'What it adds', 'Settings in .env']}
          rowHeaders
          rows={[
            [
              'Renderer',
              'PNG screenshots and PDFs from interface nodes (a headless browser, about 1 GB). Without it, interface nodes still run but produce no screenshot or PDF.',
              <code key="c">COMPOSE_PROFILES=renderer and SCREENSHOT_RENDERER_URL=http://screenshot-renderer:8094</code>,
            ],
            [
              'Browser agent and web search',
              'The Browser Agent and web search (adds a search engine and a browser container). The Browser Agent also needs an available LLM provider.',
              <code key="c">COMPOSE_PROFILES=browser-agent and WEBSEARCH_ENABLED=true</code>,
            ],
          ]}
        />
        <p>
          To enable both, use one combined value, <code>COMPOSE_PROFILES=renderer,browser-agent</code>,
          together with both settings. Then run <code>docker compose up -d</code>.
        </p>

        <h2>First-run setup wizard</h2>
        <p>
          The first administrator goes through a five-step wizard before reaching the app. Other users
          are sent straight to chat.
        </p>
        <Steps>
          <Step n={1} title="Cloud connection (recommended)">
            Connect a LiveContext Cloud account, or skip. Linking unlocks cloud-hosted models, the
            marketplace, community skills, and fresh catalogs (see below). Skipping keeps the install
            fully self-contained on your own keys.
          </Step>
          <Step n={2} title="AI providers">
            Paste API keys for the providers you want: Anthropic, OpenAI, Google, Mistral, DeepSeek,
            xAI, Perplexity, Z.AI, Qwen, Moonshot, or MiniMax.
          </Step>
          <Step n={3} title="CLI providers">
            Set up the coding-agent CLIs (Claude Code, Codex, Gemini CLI, Mistral Vibe) that run through
            the bridge. <strong>Verify</strong> checks that the CLI is installed and signed in, and then
            makes it available to administrators right away.
          </Step>
          <Step n={4} title="Platform credentials">
            Configure credentials for the integrations you plan to use (for example Gmail or Slack).
          </Step>
          <Step n={5} title="Done">
            The install is marked as set up, once, on the server. Refreshing or changing browser does not
            bring the wizard back.
          </Step>
        </Steps>

        <h2>Models on a self-hosted install</h2>
        <DocsTable
          caption="Model availability on a self-hosted install"
          head={['Group', 'Detail']}
          rowHeaders
          rows={[
            [
              'Enabled by default',
              'Anthropic, OpenAI, Google, Mistral, and DeepSeek, each with its own key. The TypeSafe decision engine for the Classify node is also on, with its own key, and only when the LLM source is API keys.',
            ],
            [
              'More API providers',
              'xAI, Perplexity, Z.AI, Qwen, Moonshot, and MiniMax can be added with a key in the setup wizard or in Settings > AI Providers.',
            ],
            [
              'Not available',
              'OpenRouter and Cohere are not offered on CE and are refused if configured.',
            ],
            [
              'CLI providers',
              'Claude Code, Codex, Gemini CLI, and Mistral Vibe run through the bridge. A CLI is offered only once the bridge confirms it is installed and signed in.',
            ],
          ]}
        />
        <p>
          See <a href="/models">Models &amp; providers</a> for reasoning effort, the admin model catalog,
          and model categories, which work the same on CE.
        </p>

        <h2>Linking to a cloud account (optional)</h2>
        <p>
          CE runs fully on its own. An administrator can link it to a LiveContext Cloud account in the
          wizard or later in <strong>Settings &gt; Cloud</strong>. The <strong>Connection</strong> tab has{' '}
          <strong>Connect to Cloud</strong> and <strong>Disconnect</strong>; the{' '}
          <strong>Bundles</strong> tab shows the sync status of the <strong>Model catalog</strong>, the{' '}
          <strong>Integrations catalog</strong>, and <strong>Skills</strong>. Linking unlocks:
        </p>
        <ul>
          <li>
            Cloud-hosted models, billed to the cloud account instead of your own keys.
          </li>
          <li>
            The <a href="/marketplace">marketplace</a>: browsing, installing, and publishing Applications.
          </li>
          <li>
            Community and global skills shared by the platform.
          </li>
          <li>
            Model and skill updates between releases (see the next section).
          </li>
        </ul>
        <p>Once linked, two switches decide what runs through the cloud:</p>
        <DocsTable
          head={['Switch', 'Where', 'Cloud', 'Local']}
          caption="Cloud source switches of a linked install"
          rowHeaders
          rows={[
            [
              'LLM source',
              'Settings > AI Providers',
              'API model calls use the linked cloud account. Tools and traces still run locally.',
              'API keys: model calls use the keys configured on this install.',
            ],
            [
              'Integration credentials',
              'Settings > Cloud',
              "Integration calls, and media generation (images, video, sound, speech, music), run through the cloud account's platform credentials, with a small per-call markup in credits billed there. Needs an active paid subscription on the cloud account.",
              'Local keys: integration credentials are configured and used on this install.',
            ],
          ]}
        />
        <p>
          When either switch is on Cloud, the cloud account&apos;s plan also governs the install (see{' '}
          <a href="/billing">Plans &amp; billing</a>). If the cloud is unreachable, the install keeps
          running; only the cloud-dependent features pause.
        </p>

        <h2>Keeping models, integrations, and skills fresh</h2>
        <p>
          Catalogs arrive as signed bundles. Each bundle is checked against a trusted
          signing key before it is applied, and a bundle never overwrites your own edits, custom models,
          or custom APIs.
        </p>
        <DocsTable
          caption="Catalog bundles and how they reach a self-hosted install"
          head={['Bundle', 'How it arrives', 'Needs a cloud link']}
          rowHeaders
          rows={[
            [
              'Model catalog',
              'Every release ships the cloud\'s model catalog as of release day, applied at startup. A linked install also syncs updates between releases, at startup and on a schedule.',
              'Only for updates between releases',
            ],
            [
              'Integrations catalog',
              'Seeded at first start, then refreshed from the cloud\'s public catalog about every 15 minutes. Your custom APIs are never touched.',
              'No',
            ],
            [
              'Skills',
              'The cloud\'s global skills, added as read-only skills that are on for everyone by default (each user can hide one).',
              'Yes',
            ],
          ]}
        />

        <h2>Updating</h2>
        <p>
          CE never updates itself. <strong>Settings &gt; Information</strong> shows the running version,
          edition, commit, and build date (also reachable from <strong>About</strong> in the user menu).
          When a newer release exists, the card shows <strong>Update available</strong> (or{' '}
          <strong>Security update</strong> for a security fix) with a <strong>How to update</strong>{' '}
          button and the release notes.
        </p>
        <Steps>
          <Step n={1} title="Back up first">
            Back up your data, keys, and configuration (see <a href="#backups">Backups</a>).
          </Step>
          <Step n={2} title="Run the update from your cloned folder">
            <CodeBlock language="bash">{`git pull
docker compose pull
docker compose up -d`}</CodeBlock>
            Run <code>git pull</code> first: the Compose file pins the release&apos;s image version, so
            pulling images without updating the repository gets the same version again. Your data is
            kept, and database migrations run automatically on startup. With the npm launcher, run{' '}
            <code>npx livecontext@latest update</code> instead.
          </Step>
        </Steps>
        <Callout variant="info" title="The update check">
          Once a day (and shortly after startup) the install asks the LiveContext release feed whether a
          newer version exists. The request carries the running version and a random install id,
          generated once and stored in your database, so that live installs can be counted. The record
          kept contains no IP address, hostname, or account. Set{' '}
          <code>CE_VERSIONCHECK_SENDINSTALLID=false</code> to stop sending the id, or{' '}
          <code>CE_VERSIONCHECK_ENABLED=false</code> to turn the check off (no update badge either).
        </Callout>
        <p>
          After an update, a one-time <strong>What&apos;s new</strong> panel describes the newest change.
          It ships inside the image and makes no network request.
        </p>

        <h2>Signing in and adding people</h2>
        <p>
          CE uses built-in email and password sign-in. The first person to register becomes the
          administrator. Once the setup wizard is completed, public registration closes. An
          administrator then adds people in one of two ways:
        </p>
        <ul>
          <li>
            Invite link: invite by email, then share the copyable link. The person
            registers through it and joins with the invited role, even while registration is closed.
          </li>
          <li>
            Reopen registration: let anyone create an account without an invitation.
          </li>
        </ul>
        <p>
          See <a href="/organizations">Organizations &amp; roles</a> for workspaces and roles.
        </p>

        <h2>What CE includes and leaves out</h2>
        <DocsTable
          caption="What the Community Edition includes and leaves out"
          head={['Area', 'Detail']}
          rowHeaders
          rows={[
            [
              'Included',
              'The full workflow engine, agents and their tools, the integration catalog, built-in sign-in, embedded code execution, S3-compatible file storage, and unlimited usage. Web search, the Browser Agent, and the renderer are available as opt-in add-ons.',
            ],
            [
              'Left out',
              'SAML SSO, Stripe billing, plan-limit gating, and the platform margin on model costs.',
            ],
            [
              'Credits',
              'Unlimited: nothing is capped or billed. Usage is still recorded at the providers\' own prices, so you can see what you spend in Quota & Usage.',
            ],
          ]}
        />

        <h2 id="backups">Backups</h2>
        <p>
          Your data lives in Docker volumes, not only in the cloned folder. A database-only backup
          without the keys and the file storage is not a complete recovery plan. Back up
          all of these together, from the same stopped snapshot:
        </p>
        <DocsTable
          caption="Docker volumes to back up"
          head={['Volume', 'What it holds']}
          rowHeaders
          rows={[
            [<code key="v">livecontext_data</code>, 'The PostgreSQL database.'],
            [<code key="v">livecontext_minio</code>, 'Stored files and generated assets.'],
            [<code key="v">livecontext_keys</code>, 'Authentication and credential-encryption keys.'],
            [<code key="v">livecontext_redis</code>, 'Redis state and queued work.'],
            [<code key="v">livecontext_logs</code>, 'Logs and audit history.'],
          ]}
        />
        <p>
          The actual volume names carry a project prefix (<code>livecontext-ce</code> for the repository
          install, <code>livecontext</code> for the npm launcher): check them with{' '}
          <code>docker volume ls</code>. Also keep <code>.env</code>, the Compose file, any overrides,
          and the <code>catalog-seeds</code> folder. Stop the stack with <code>docker compose stop</code>{' '}
          before exporting the volumes, and restrict access to the backup: it contains secrets.
        </p>
        <Callout variant="warn">
          <code>docker compose down</code> keeps your volumes, but <code>docker compose down -v</code>{' '}
          deletes all of the install&apos;s data. Never use <code>-v</code> to update or troubleshoot.
        </Callout>
        <p>
          Test a restore on a separate machine: same project name, same image versions, then check
          sign-in, a saved credential, and a stored file.
        </p>

        <h2>Troubleshooting</h2>
        <p>
          Start with <code>docker compose ps</code> and{' '}
          <code>docker compose logs --tail=100 livecontext frontend</code> from the cloned folder. None of
          the fixes below needs <code>docker compose down -v</code>.
        </p>
        <DocsTable
          caption="Common self-hosting problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'After an update, Settings > Information still shows the old version',
              'The images were pulled without updating the repository. The Compose file pins the release version, so the same images come back.',
              <span key="f">
                Run <code>git pull</code>, then <code>docker compose pull</code> and{' '}
                <code>docker compose up -d</code>.
              </span>,
            ],
            [
              'On a server, email links or OAuth sign-ins send people back to localhost, or the provider rejects the redirect URL',
              <span key="c">
                <code>PUBLIC_BASE_URL</code> and <code>GATEWAY_PUBLIC_URL</code> are not set, or end with a
                slash. A trailing slash produces a callback with a double slash that does not match the URL
                you registered.
              </span>,
              <span key="f">
                Set both addresses without a trailing slash, run <code>docker compose up -d</code>, and
                register <code>&lt;GATEWAY_PUBLIC_URL&gt;/api/credentials/oauth2/callback</code> with the
                provider.
              </span>,
            ],
            [
              <span key="s">
                <code>livecontext</code> does not become healthy
              </span>,
              'The first start downloads several GB and prepares the database, which takes a few minutes. A backend that keeps restarting has usually hit its memory limit (1.5 GB) or failed a database migration.',
              'Wait, then read the backend logs. For out-of-memory errors, raise the livecontext memory limit in the Compose file. For a migration error, keep your volumes and check the release notes.',
            ],
            [
              'The stack will not start because a port is already in use',
              'Another program already uses port 3000 or 8080.',
              <span key="f">
                Set <code>FRONTEND_PORT</code> and <code>BACKEND_PORT</code> in <code>.env</code> and run{' '}
                <code>docker compose up -d</code>. No rebuild is needed.
              </span>,
            ],
            [
              'Connecting to the cloud fails with: An antivirus or corporate proxy is intercepting the connection to the cloud.',
              'Your network re-signs HTTPS traffic with a certificate the containers do not trust. Bundle syncs then fail with certificate (PKIX) errors in the Bundles tab.',
              <span key="f">
                Click <strong>Trust &amp; reconnect</strong>, or mount the proxy&apos;s root CA as described
                in Behind a TLS-intercepting proxy above.
              </span>,
            ],
            [
              'Switching Integration credentials to Cloud is refused',
              'Relayed calls need an active paid subscription on the linked cloud account.',
              'Subscribe on the cloud account, or keep Local keys and add the credentials on this install.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={KeyRound} title="Models & providers" href="/models">
            Provider keys, the CLI bridge, and the admin model catalog.
          </Card>
          <Card icon={ShieldCheck} title="Administration" href="/admin">
            Running the install day to day as its administrator.
          </Card>
          <Card icon={CreditCard} title="Plans & billing" href="/billing">
            How usage is tracked on CE, and what a cloud plan changes.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            What a cloud link unlocks for Applications.
          </Card>
          <Card icon={RefreshCw} title="Integrations" href="/integrations">
            The integration catalog kept fresh by bundle sync.
          </Card>
          <Card icon={Github} title="Get the code (opens in a new tab)" href={SELF_HOSTED_GITHUB_URL}>
            The public repository, with the Compose file and the full setup guide.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
