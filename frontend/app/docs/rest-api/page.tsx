import { Server, Workflow, Table2, Bot } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'REST API & webhooks',
  description:
    'How external systems talk to LiveContext: webhook trigger URLs and the Respond to Webhook node, outbound calls with the HTTP Request node, public share links, chat over the API, and the official MCP Streamable HTTP server authenticated by a personal lc_live_ key.',
  path: '/docs/rest-api',
});

export default function RestApiPage() {
  return (
    <>
      <DocsHero
        eyebrow="Integrate"
        title="REST API & webhooks"
        lead="Every external integration point funnels through the Gateway, which owns routing and token-based edge auth. This page covers the three ways in (webhook triggers, share links, chat and MCP), the one way out (the HTTP Request node), and how to reply to a caller from inside a workflow."
      />

      <DocsProse>
        <h2>Overview</h2>
        <p>
          External systems reach LiveContext through the <strong>Gateway</strong>, which registers
          the webhook, chat, form, and share surfaces as public, token-based routes. Three patterns
          exist: a <strong>webhook trigger</strong> starts a workflow from an incoming HTTP call, the{' '}
          <strong>HTTP Request node</strong> makes the workflow the caller of an external API, and{' '}
          <strong>share tokens / API keys</strong> expose read or interactive access to a shared
          resource or to your personal tool registry (chat, MCP).
        </p>
        <Callout variant="info">
          API access itself is <strong>not plan-gated</strong>. Every plan, including Free, gets a
          personal API key and the same endpoints. What differs by plan is the per-user{' '}
          <strong>requests/minute rate ceiling</strong>: Free 1000, Starter 2000, Pro 3000,
          Team/Pay-as-you-go 6000, Enterprise 10000. Anonymous/public endpoints are capped at 600/min per IP;
          share-token traffic is capped at 60/min per token and 500/day per owner. Going over any
          ceiling returns HTTP 429 with a <code>Retry-After</code> header giving the seconds left in
          the window.
        </Callout>

        <h2>Webhook triggers</h2>
        <p>
          A webhook trigger listens on a URL shaped <code>{'{method} {base}/webhook/{token}'}</code>.
          Tokens look like <code>wh_&lt;32 hex&gt;</code>. Accepted methods are GET, POST, PUT,
          PATCH, DELETE; a webhook is configured for exactly one, the default is POST, and a
          mismatching method returns <strong>405 Method Not Allowed</strong>.
        </p>
        <DocsTable
          head={['Auth type', 'How it works']}
          rows={[
            ['none', 'No verification (the default); anyone with the URL can call it.'],
            ['basic', 'HTTP Basic auth: Authorization: Basic base64(user:pass).'],
            ['header', 'A custom header name and value you choose, the "API key" style of auth.'],
            [
              'jwt',
              'Authorization: Bearer <JWT>, HMAC-verified against a configured secret (HS256 by default, or HS384 / HS512).',
            ],
          ]}
        />
        <p>A failed auth check returns <strong>401 Unauthorized</strong>.</p>
        <Callout variant="info">
          A plain <code>GET</code> carrying <code>hub.mode=subscribe&amp;hub.verify_token=...&amp;
          hub.challenge=...</code> is recognized before auth/dispatch as a platform verification
          handshake (Meta WhatsApp / Facebook / Instagram) and echoes the challenge back as{' '}
          <code>text/plain</code>, completing the subscription.
        </Callout>

        <h3>Reading the incoming payload</h3>
        <p>
          The Webhook Trigger node exposes the request to the rest of the workflow:
        </p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['payload', 'The request body. For GET requests the query string becomes the payload; for body methods the JSON body is used, with query params merged in as a fallback.'],
            ['headers', 'All request headers.'],
            ['query', 'Query string params (alias queryParams).'],
            ['method', 'The HTTP method used for this call.'],
            ['triggered_at', 'ISO timestamp (alias triggeredAt).'],
            ['triggered_by', 'Display name of the workflow owner (alias triggeredBy), empty for an unauthenticated webhook.'],
          ]}
        />
        <p>
          The controller also injects two internal metadata fields, <code>_webhookMethod</code> and{' '}
          <code>_webhookTimestamp</code>, into the dispatched payload.
        </p>

        <h3>What the caller sees back (async, the default)</h3>
        <DocsTable
          head={['Outcome', 'HTTP status']}
          rows={[
            ['accepted / triggered', '202'],
            ['completed', '200'],
            ['not_found', '404'],
            ['not_active', '409'],
            ['insufficient_credits', '402'],
            ['rate_limited', '429'],
            ['unauthorized', '401'],
            ['error', '400'],
          ]}
        />

        <h2>Responding to a webhook caller (Respond to Webhook node)</h2>
        <p>
          By default a webhook call gets an immediate async acknowledgement (202) while the workflow
          keeps running in the background. To have the workflow itself craft the reply, call the
          webhook with <code>?sync=true</code>.
        </p>
        <Callout variant="warn">
          In sync mode, once the dispatch returns <code>triggered</code> the HTTP response is{' '}
          <strong>deferred</strong> until a <strong>Respond to Webhook</strong> node resolves it. The
          deferral times out after <strong>60 seconds</strong>, after which the caller falls back to
          the standard <code>202 Accepted</code>.
        </Callout>
        <p>The node&apos;s configuration:</p>
        <DocsTable
          head={['Field', 'Default', 'Notes']}
          rows={[
            ['statusCode', '200', 'Any non-positive value is coerced back to 200.'],
            ['body', 'empty', 'Supports SpEL / {{...}} templates resolved against workflow data.'],
            ['contentType', 'application/json', ''],
            ['headers', 'empty map', 'Custom response headers.'],
          ]}
        />
        <p>
          Outputs: <code>responded</code> (boolean, false when there was no pending sync webhook to
          answer, for example a manual trigger or a call made without <code>sync</code>),{' '}
          <code>statusCode</code>, <code>contentType</code>. Because <code>responded=false</code> is
          not a failure, the same workflow runs safely whether or not it was actually invoked through
          a synchronous webhook.
        </p>

        <h2>Calling external APIs from a workflow (HTTP Request node)</h2>
        <p>
          The HTTP Request node makes your workflow the caller. Methods: GET (default), POST, PUT,
          PATCH, DELETE. The URL, query-param values, header values, body, and auth
          values all support <code>{'{{...}}'}</code> template resolution.
        </p>
        <DocsTable
          head={['Auth type', 'Behavior']}
          rows={[
            ['none', 'Default, no auth added.'],
            ['basic', 'Base64(user:pass) sent as Authorization: Basic.'],
            ['bearer', 'Authorization: Bearer <token>.'],
            ['api-key', 'A custom-named key sent in a header or a query param, per apiKeyLocation.'],
            ['custom-header', 'An arbitrary header name and value.'],
          ]}
        />
        <DocsTable
          head={['Body type', 'Behavior']}
          rows={[
            ['none', 'Default, no body.'],
            ['json', 'Parsed then sent as JSON.'],
            ['x-www-form-urlencoded', 'Parsed into form fields.'],
            ['form-data / raw', 'Sent as-is.'],
          ]}
        />
        <p>
          Content-Type is auto-set from the body type when not supplied; Accept defaults to{' '}
          <code>application/json, */*</code>. An optional per-node <code>timeout</code> (ms) is
          clamped to a <strong>300000 ms (5 minute)</strong> ceiling, applied to both connect and
          read.
        </p>
        <Callout variant="warn">
          Every resolved URL is validated before the call: non-HTTP(S) schemes and
          private/loopback/link-local targets are blocked (10.0.0.0/8, 172.16.0.0/12,
          192.168.0.0/16, 127.0.0.0/8 and ::1 loopback, 169.254.0.0/16 link-local metadata,{' '}
          <code>localhost</code> by name), behind a 3 second DNS-resolution guard. This is
          server-side SSRF protection, not something you configure per node.
        </Callout>
        <p>Outputs:</p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['success', 'Boolean.'],
            ['status', 'Alias statusCode.'],
            ['statusText', ''],
            ['data', 'Alias body / response. JSON is auto-parsed, otherwise the raw string.'],
            ['headers', 'First value per response header key, plus headersMulti for the full multi-value form.'],
            ['error', 'Alias errorMessage.'],
          ]}
        />
        <Callout variant="info">
          A non-2xx response does <strong>not</strong> fail the node: it comes back with{' '}
          <code>success=false</code> and the parsed error body in <code>data</code>/<code>error</code>,
          and the workflow continues so you can branch on it with a Decision node. Stopping a run
          aborts an in-flight request within roughly 200 ms.
        </Callout>

        <h2>Public share links & tokens</h2>
        <p>
          Sharing an application or a conversation issues a token, resolved at the Gateway to the
          owner&apos;s user context and cached for about 60 seconds. Two families exist:{' '}
          <code>sl_...</code> for published resources such as applications (validated via
          publication-service), and any other prefix such as <code>cs_...</code> for conversation
          share tokens (validated via conversation-service; legacy <code>cs_</code> tokens carry the
          conversation id in the token itself).
        </p>
        <p>
          A share request is read-only and scoped to that one resource. Clients present the token as{' '}
          <code>Authorization: ShareToken &lt;token&gt;</code>. The Gateway injects an{' '}
          <code>X-Share-Context</code> marker plus <code>X-Share-Resource-Type</code> (
          <code>WORKFLOW</code>/<code>CONVERSATION</code>/<code>APPLICATION</code>),{' '}
          <code>X-Share-Resource-Token</code>, and <code>X-Share-Resource-Id</code> so downstream
          services only see that one resource.
        </p>
        <DocsTable
          head={['Public surface', 'What it serves']}
          rows={[
            ['/s/**', 'Universal share page.'],
            ['/share/**', 'Share-token resolution API.'],
            ['/c/**', 'Shared conversations.'],
            ['/app/public/**', 'Public application endpoints, for apps published as standalone.'],
            ['/api/shared, /api/publications/marketplace|search|by-id|by-publisher|highlights', 'Anonymous marketplace browsing.'],
          ]}
        />
        <p>
          None of these need a login. See <a href="/interfaces">Interfaces &amp; apps</a> and{' '}
          <a href="/marketplace">Marketplace</a> for how publishing and sharing produce these links.
        </p>

        <h2>Chat over the API</h2>
        <p>
          There are two distinct chat surfaces, and they are not interchangeable.
        </p>
        <h3>Public chat endpoint (token-based, no login)</h3>
        <p>
          Built for chatbot apps wired to a chat trigger. It is <strong>synchronous
          request/response</strong>: no SSE streaming, no stop endpoint.
        </p>
        <DocsTable
          head={['Endpoint', 'Purpose']}
          rows={[
            ['POST /chat/{token}/session', 'Create or resume a session.'],
            ['POST /chat/{token}/message', 'Body {sessionId, message}; returns a synchronous reply.'],
            ['GET /chat/{token}/history', 'Needs an X-Chat-Session header.'],
            ['GET /chat/{token}/config', 'Chat endpoint configuration.'],
          ]}
        />
        <h3>Authenticated chat API (JWT or lc_live_ key)</h3>
        <p>
          For driving a full conversation from your own backend. <code>POST /api/v3/chat</code>{' '}
          sends a message and returns <code>{'{conversationId, streamId, model}'}</code>{' '}
          immediately while the agent loop runs asynchronously. Token and tool events stream over{' '}
          <strong>WebSocket <code>/ws</code></strong> (Redis Pub/Sub), not from this REST call.{' '}
          <code>POST /api/v3/chat/stop</code> stops an active stream.
        </p>
        <Callout variant="warn">
          Don&apos;t expect streaming or a stop endpoint on the public <code>/chat/{'{token}'}</code>{' '}
          surface: streaming and stop are exclusive to the authenticated <code>/api/v3/chat</code>{' '}
          API over the WebSocket.
        </Callout>

        <h2>Official MCP server</h2>
        <p>
          LiveContext ships a standard <strong>MCP Streamable HTTP</strong> server, speaking JSON-RPC
          2.0, at a single endpoint: <code>POST {'{base}'}/mcp</code>. It is connectable from any MCP
          client (Claude Code, Cursor, Claude Desktop, and others) and available in both the cloud
          edition and self-hosted Community Edition.
        </p>
        <DocsTable
          head={['Property', 'Detail']}
          rows={[
            ['Transport', 'Stateless Streamable HTTP over POST; no Mcp-Session-Id. GET and DELETE return 405 (Allow: POST). Notifications return 202. JSON-RPC batches are supported (an empty batch returns one Invalid Request error).'],
            ['Protocol versions', '2025-06-18, 2025-03-26, 2024-11-05 (newest negotiated by default).'],
            ['Methods', 'initialize, ping, tools/list, tools/call, resources/list, resources/templates/list, resources/read.'],
          ]}
        />
        <h3>Authentication: a personal lc_live_ key</h3>
        <p>Two header forms both work:</p>
        <CodeBlock language="text">{`X-API-Key: lc_live_...
Authorization: Bearer lc_live_...`}</CodeBlock>
        <p>
          Missing or invalid credentials return <strong>401</strong> with{' '}
          <code>WWW-Authenticate: Bearer realm=&quot;mcp&quot;</code>. Each user has a single key:{' '}
          <code>GET /api/auth/api-keys/current</code> returns a masked hint (the plaintext is never
          shown again), and <code>POST /api/auth/api-keys/regenerate</code> mints a new one (
          <code>lc_live_</code> plus 64 hex characters), shown <strong>once</strong>, immediately
          overwriting and invalidating the previous key.
        </p>
        <p>
          Connection details for your own settings page are available from{' '}
          <code>GET /api/mcp-server/connection</code>, which returns{' '}
          <code>{'{url, serverName, authHeader, toolCount}'}</code>.
        </p>
        <h3>Client configuration example</h3>
        <CodeBlock language="json">{`{
  "mcpServers": {
    "livecontext": {
      "type": "http",
      "url": "https://livecontext.ai/mcp",
      "headers": {
        "X-API-Key": "lc_live_..."
      }
    }
  }
}`}</CodeBlock>
        <p>
          Connecting this way gives the MCP client access to your own tool registry, the same tools
          available inside LiveContext&apos;s own agents and workflows: see{' '}
          <a href="/agents">Agents</a> and <a href="/integrations">Integrations</a> for what those
          tools cover.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Triggers" href="/triggers">All eight ways a run can start, including the webhook trigger.</Card>
          <Card icon={Table2} title="Tables & data" href="/tables">Row-level CRUD backing the Table trigger and datasources.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Agents, tools, and how the same registry is exposed over MCP.</Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">Run your own instance; the MCP server works the same in CE.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
