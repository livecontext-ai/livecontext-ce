import { Plug, Bot, Workflow, Server } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Integrations',
  description:
    'Connect external tools in LiveContext: the ~600+ pre-built integrations, how tools and reusable credentials work, OAuth (platform-shared vs bring-your-own, PKCE, scope preflight, auto-refresh), custom private APIs, sending files to a tool, and connecting external MCP clients to your own registry.',
  path: '/docs/integrations',
});

export default function IntegrationsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Integrations"
        lead="LiveContext ships a catalog of ~600+ pre-built third-party API integrations. Every endpoint of every integration is exposed as exactly one tool - connect a service once, then drop its tools into any workflow or give them to an agent."
      />

      <DocsProse>
        <h2>Tools: one endpoint, one tool</h2>
        <p>
          An integration (Slack, GitHub, Google Drive, ...) is a collection of <strong>tools</strong>.
          Each tool is exactly one operation on one endpoint, like &ldquo;send a message&rdquo; or
          &ldquo;create an issue&rdquo; - there is no bundling of several operations into a single
          tool. Every tool declares its HTTP method (GET/POST/PUT/PATCH/DELETE), typed parameters
          (each with an <code>in</code> of query, path, or body), and a required output schema.
        </p>
        <p>
          Add a tool as a workflow step and reference its result downstream like any other node.
          Agents use the exact same tools: an agent with a tool in scope can call it on its own (see{' '}
          <a href="/agents">Agents</a>). Agents discover what&apos;s available with the catalog search
          action, and narrow that down to what your account has actually connected with{' '}
          <code>get_connected_services</code> (below).
        </p>
        <CodeBlock language="text">{`{{mcp:send_message.output.ts}}     → the id Slack returned for the message
{{mcp:create_issue.output.url}}    → the URL of the issue you just opened`}</CodeBlock>
        <p>
          The same <code>{`{{mcp:<tool>.output.<field>}}`}</code> reference also works inside decision
          conditions and split items, anywhere an expression can appear inside <code>{`{{...}}`}</code>.
        </p>

        <h2>Credentials: connect once, reuse everywhere</h2>
        <p>
          You connect a service once, in Settings, and every tool from that integration reuses the
          same connection. There are two layers underneath a connection:
        </p>
        <ul>
          <li>
            <strong>The app registration</strong> (shared) - the OAuth client or key configuration for
            an integration. For the built-in catalog this is already set up for you; it&apos;s the
            &ldquo;LiveContext is allowed to talk to this provider at all&rdquo; layer.
          </li>
          <li>
            <strong>Your credential</strong> (private) - the actual secret: an OAuth access/refresh
            token, an API key, a bearer token, or basic-auth username/password. It&apos;s stored
            encrypted at rest (access tokens, refresh tokens, and OAuth client secrets are all
            encrypted before they&apos;re persisted) and visible only to you.
          </li>
        </ul>
        <p>
          You can hold <strong>more than one</strong> credential per integration (for example two
          Slack workspaces, or a Standard and a BYOK connection for the same API) - only the one
          credential marked <strong>default</strong> is used when a tool actually executes.
        </p>
        <p>Credentials support these auth types:</p>
        <DocsTable
          head={['Auth type', 'What it stores']}
          rows={[
            ['oauth2', 'Access + refresh token from the provider\'s OAuth flow (see below).'],
            ['api_key', 'A single API key/token, entered directly.'],
            ['bearer', 'A bearer token, sent as an Authorization header.'],
            ['basic', 'A username/password pair, sent as HTTP Basic auth.'],
            ['none', 'No credential needed (public endpoints).'],
            ['custom', 'Dynamic fields defined by the specific integration.'],
          ]}
        />
        <p>
          Some APIs support more than one auth type for the same integration (for example Gmail
          offers both OAuth2 and API key). When an integration has two or more variants, the
          connection wizard shows a tab picker so you choose which one to set up.
        </p>
        <Callout variant="warn">
          Never paste a token or API key into a workflow variable or trigger payload - those are
          auditable but not secret. Always store secrets as a credential in Settings.
        </Callout>

        <h2>Connected services &amp; the default credential</h2>
        <p>
          <code>get_connected_services</code> takes no parameters and lists every credential you&apos;ve
          connected: its integration, its status, whether it&apos;s the default, and a best-effort
          account identifier (pulled from fields like email, username, workspace, or team name on the
          credential itself). Only the credential with <code>isDefault=true</code> is used when tools
          from that integration execute.
        </p>
        <DocsTable
          head={['Status', 'Meaning', 'How to fix it']}
          rows={[
            ['active', 'Ready - tools using this credential will work.', 'Nothing to do.'],
            ['expiring', 'Still works today, but the token is expiring soon.', 'Reconnect proactively to avoid a gap.'],
            ['needs_reauth', 'The token was revoked or has expired.', 'Only you can fix this - reconnect the credential.'],
            ['error', 'The credential is misconfigured (for example a bad app registration).', 'An admin must fix the configuration - reconnecting alone will not help.'],
          ]}
        />

        <h2>OAuth: platform-shared vs bring-your-own, PKCE, scopes, auto-refresh</h2>
        <p>
          When an integration uses OAuth, connecting it sends you through the provider&apos;s login
          screen to grant access, and the resulting tokens are stored for you automatically.
        </p>
        <h3>Standard vs Advanced (BYOK)</h3>
        <ul>
          <li>
            <strong>Standard (platform-shared)</strong> - uses LiveContext&apos;s own OAuth client for
            that integration. It can only grant the catalog&apos;s standard scope list for that API.
          </li>
          <li>
            <strong>Advanced (bring-your-own OAuth / BYOK)</strong> - you supply your own{' '}
            <code>client_id</code>/<code>client_secret</code> for the provider. A BYOK connection can
            request the full scope set for that integration, including scopes that only a BYOK app is
            allowed to request.
          </li>
          <li>
            Some integrations are <strong>fully BYOK</strong> (for example Google Classroom): the
            platform app declares no scopes of its own for them, so connecting always routes straight
            to the Advanced form and the Standard toggle is hidden.
          </li>
        </ul>
        <Callout variant="info">
          Self-hosted (CE) installs don&apos;t have a platform-shared OAuth app: your own install&apos;s
          OAuth client always requests the full scope set for an integration, so the Standard/BYOK
          split doesn&apos;t apply there.
        </Callout>
        <h3>PKCE</h3>
        <p>
          Providers that require PKCE (Airtable, Microsoft, Twitter/X, Shopify, Atlassian, and others)
          get an RFC 7636 S256 verifier/challenge generated automatically as part of the flow - there
          is nothing to configure when connecting a built-in integration.
        </p>
        <h3>Scope preflight</h3>
        <p>
          If a workflow step needs a scope your connected OAuth2 credential doesn&apos;t have, the
          workflow inspector shows a banner up front - before the run ever starts - offering to{' '}
          <strong>Reconnect (Standard)</strong> or <strong>Switch to Advanced</strong> to grant the
          missing scope, instead of failing mid-run with a cryptic 403.
        </p>
        <h3>Auto-refresh</h3>
        <p>
          OAuth2 credentials refresh themselves in the background as tokens approach expiry, so tools
          keep working without you doing anything. If a refresh ultimately fails, the credential is
          scrubbed of its tokens and flips to <code>needs_reauth</code> (you reconnect) or{' '}
          <code>error</code> (an admin fixes the app configuration).
        </p>
        <Callout variant="warn">
          API-key, bearer, and basic-auth credentials do <strong>not</strong> auto-refresh - only
          OAuth2 has a refresh path. If a key is rotated or revoked, update it yourself in Settings.
        </Callout>
        <p>
          LiveContext stores the scopes actually <strong>granted</strong> by the provider (not just
          the ones requested), so unchecking an optional scope on the provider&apos;s consent screen
          doesn&apos;t cause a phantom permission error later. The consent screen itself renders in
          your app&apos;s language when the provider supports it.
        </p>

        <h2>Registering your own custom APIs</h2>
        <p>
          Need something that isn&apos;t in the catalog? Register your own private API with the same
          shape as the built-ins: a name, base URL, auth type, category, and a list of endpoints.
        </p>
        <Steps>
          <Step n={1} title="Basics">
            Give the API a name, a base URL, and pick its <code>authType</code>: <code>none</code>,{' '}
            <code>bearer</code>, <code>apikey</code>, or <code>oauth2</code>.
          </Step>
          <Step n={2} title="Endpoints">
            Add one entry per operation. Each endpoint needs an HTTP method, its parameters (query,
            path, or body, each with a type and a description), and a required, non-empty output
            schema (an array of <code>{`{key, type, description}`}</code>) - an endpoint without one
            is rejected. Allowed output types: <code>string</code>, <code>number</code>,{' '}
            <code>boolean</code>, <code>datetime</code>, <code>object</code>, <code>array</code>,{' '}
            <code>fileRef</code>.
          </Step>
          <Step n={3} title="Execution mode">
            Pick how the endpoint runs: <code>sync</code> (default, request/response), <code>async_poll</code>{' '}
            (fire, then poll for completion), <code>upload</code>, or <code>streaming</code>.
          </Step>
          <Step n={4} title="Icon (optional)">
            Upload an image icon, max 2 MB.
          </Step>
          <Step n={5} title="Connect it">
            Registering the spec only defines the API. If it uses OAuth2, complete the actual
            connection separately in the credential wizard (Settings) - that step still needs the
            browser-based OAuth flow.
          </Step>
        </Steps>
        <p>
          Custom APIs are always <strong>private</strong>: only the person who registered one can see,
          update, or delete it. Registering an API with a name that already exists returns an error
          telling you to update it instead; an update deletes and re-creates the API under a new id.
        </p>
        <Callout variant="info">
          The base URL is only format-checked when you register the API (no DNS lookup). The full
          safety check that blocks requests to private/internal addresses runs at execution time,
          every time the tool actually runs.
        </Callout>
        <Callout variant="warn">
          The custom-API form has no PKCE toggle - its auth-type choice is limited to none/bearer/apikey/oauth2.
          If a custom OAuth2 API needs PKCE, it can only be set through the underlying JSON spec
          (<code>oauth2Config.pkce: true</code>), not through the Settings form.
        </Callout>

        <h2>Sending stored files to a tool (multipart FileRef)</h2>
        <p>
          Some tools accept file uploads as a multipart body part (for example Telegram&apos;s send
          photo, or a document upload endpoint). A part can source its content in three ways:
        </p>
        <DocsTable
          head={['source', 'Behavior']}
          rows={[
            [
              <code key="a">fileRef</code>,
              <>Expects a structured file reference (a stored file&apos;s type/path/name). The bytes are downloaded from storage, scoped to your tenant, and sent as the file part.</>,
            ],
            [
              <code key="b">auto</code>,
              <>Polymorphic: a file reference is downloaded and sent as a file part, a map/list is JSON-encoded, and a plain value is sent as text.</>,
            ],
            [
              <code key="c">multipart_related</code>,
              <>A JSON metadata part plus a binary media part in one request - used for Google media uploads (for example YouTube video uploads) that reject plain multipart/form-data.</>,
            ],
          ]}
        />
        <p>
          In practice: point the tool&apos;s file parameter at a file reference produced upstream
          (for example a Download File node&apos;s output, or a file a user uploaded), set{' '}
          <code>source: &quot;auto&quot;</code> for the common case, and the engine handles fetching
          and attaching the bytes for you.
        </p>

        <h2>Connecting an external MCP client to your own tools</h2>
        <p>
          The relationship also runs the other way: any external MCP client, such as Claude Code,
          Cursor, or Claude Desktop, can connect to <strong>your</strong> connected tool registry over
          the standard MCP Streamable HTTP transport at <code>POST /mcp</code>, authenticated with a
          personal <code>lc_live_</code> API key. Once connected, the client can list and call every
          tool your account has access to, exactly as an in-app agent would. Full endpoint, auth, and
          transport details live on the <a href="/rest-api">REST API</a> page.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={2}>
          <Card icon={Bot} title="Agents" href="/agents">Scope which tools an agent may call.</Card>
          <Card icon={Workflow} title="Node reference" href="/nodes">Integration step, HTTP Request, and more.</Card>
          <Card icon={Plug} title="Tables & data" href="/tables">Persist what your integrations return.</Card>
          <Card icon={Server} title="REST API" href="/rest-api">Connect an external MCP client with an lc_live_ key.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
