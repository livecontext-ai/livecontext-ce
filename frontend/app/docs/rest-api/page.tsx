import { Server, Workflow, Globe, KeyRound } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'REST API & webhooks',
  description:
    'Call LiveContext from your own code: lc_live_ API keys, webhook trigger URLs, the Respond to Webhook node, share links, chat over the API, and the HTTP Request node.',
  path: '/docs/rest-api',
});

export default function RestApiPage() {
  return (
    <>
      <DocsHero
        eyebrow="Reference"
        title="REST API & webhooks"
        lead="How your own systems talk to LiveContext and how a workflow talks back. This page covers API keys, calling a webhook trigger, the response and error format, share links, chat over the API, and outbound calls with the HTTP Request node."
      />

      <DocsProse>
        <h2>Overview</h2>
        <p>There are three ways in and one way out:</p>
        <ul>
          <li>
            A <strong>webhook trigger</strong> starts a workflow when your system sends an HTTP request to its URL. No
            API key is needed; the webhook has its own optional authentication.
          </li>
          <li>
            An <strong>API key</strong> (<code>lc_live_</code>) lets a script call the authenticated API as you, or
            connect an MCP client to the <a href="/mcp-server">MCP server</a>.
          </li>
          <li>
            A <strong>share link</strong> gives read or interactive access to one shared resource without a login.
          </li>
          <li>
            The <strong>HTTP Request node</strong> is the way out: your workflow calls an external API.
          </li>
        </ul>
        <p>
          In the examples below, <code>https://livecontext.ai</code> is the cloud address. On a self-hosted Community
          Edition install, use the address of your own instance instead.
        </p>

        <h2>API keys</h2>
        <p>
          An API key looks like <code>lc_live_</code> followed by 64 hexadecimal characters. You can hold up to{' '}
          <strong>20 active keys</strong>, each with its own name, so you can give every script or client its own key
          and revoke one without touching the others.
        </p>
        <DocsTable
          caption="API key access levels"
          head={['Access', 'What the key can do']}
          rowHeaders
          rows={[
            [
              'Full access',
              'Authenticates every API call as you, on the MCP endpoint and on the rest of the API (for example the chat API).',
            ],
            [
              'Limited to specific tools',
              <>
                Works only on the MCP endpoint (<code>/mcp</code>), and only for the tools you picked. Any other API path
                answers <strong>403</strong> with <code>{'{"error": "scope_forbidden"}'}</code>.
              </>,
            ],
          ]}
        />
        <Callout variant="tip" title="Pick the smallest key">
          Give a key full access only when a script needs the REST API. For an MCP client, a key limited to the tools it
          needs cannot be used to create other keys or reach the rest of your account.
        </Callout>

        <h3>Create a key</h3>
        <Steps>
          <Step n={1} title="Open the key settings">
            Go to <strong>Settings</strong> &gt; <strong>MCP Server</strong>. The <strong>API keys</strong> section lists
            your keys.
          </Step>
          <Step n={2} title="Start a new key">
            Select <strong>New key</strong>, then type a <strong>Name</strong> that says where the key is used (for
            example &quot;Nightly export script&quot;).
          </Step>
          <Step n={3} title="Choose the access">
            Leave <strong>Limit to specific tools</strong> off for a full-access key. Turn it on to pick tools under{' '}
            <strong>Tools</strong>; you must pick at least one.
          </Step>
          <Step n={4} title="Copy the key now">
            Select <strong>New key</strong> to create it. The <strong>Key created</strong> dialog shows the full key{' '}
            <strong>once</strong>: copy it and store it somewhere safe, then select <strong>Done</strong>. Afterwards the
            list only shows a masked hint such as <code>lc_live_...a1b2</code>.
          </Step>
        </Steps>

        <h3>Revoke a key</h3>
        <p>
          In the <strong>API keys</strong> list, select the <strong>Revoke</strong> (bin) icon next to the key, then{' '}
          <strong>Revoke key</strong> to confirm. Revocation is immediate: the next request with that key fails with{' '}
          <strong>401</strong>. Your other keys keep working.
        </p>

        <h3>Authenticate a request</h3>
        <p>Send the key in either header. Both forms are accepted everywhere a key is accepted:</p>
        <CodeBlock language="text" title="Headers">{`X-API-Key: lc_live_xxxxxxxx
Authorization: Bearer lc_live_xxxxxxxx`}</CodeBlock>
        <CodeBlock language="bash" title="List your keys">{`curl https://livecontext.ai/api/auth/api-keys \\
  -H "X-API-Key: lc_live_xxxxxxxx"`}</CodeBlock>
        <p>
          Keys are sent in a header, never in the URL. Treat a full-access key like a password: anyone who has it acts
          as you.
        </p>

        <h3>Manage keys over the API</h3>
        <p>A full-access key (or your signed-in session) can manage keys directly:</p>
        <DocsTable
          caption="API key management requests"
          head={['Request', 'What it does']}
          rowHeaders
          rows={[
            [
              <code key="l">GET /api/auth/api-keys</code>,
              <>
                Lists your active keys: <code>id</code>, <code>name</code>, <code>maskedApiKey</code>,{' '}
                <code>scopes</code> (<code>null</code> for full access), <code>createdAt</code>,{' '}
                <code>lastUsedAt</code>. Never returns the full key.
              </>,
            ],
            [
              <code key="c">POST /api/auth/api-keys</code>,
              <>
                Body <code>{'{"name": "...", "scopes": ["table"]}'}</code>. Omit <code>scopes</code> or send{' '}
                <code>null</code> for full access; an empty list is rejected. Returns the key entry plus{' '}
                <code>apiKey</code>, the full key, shown only in this response. Returns <strong>400</strong> when the
                name is missing or longer than 100 characters, or when you already have 20 active keys.
              </>,
            ],
            [
              <code key="d">{'DELETE /api/auth/api-keys/{id}'}</code>,
              <>
                Revokes the key immediately. Returns <strong>204</strong>, or <strong>404</strong> if the key is not
                yours.
              </>,
            ],
          ]}
        />
        <CodeBlock language="bash" title="Create a key limited to the table tool">{`curl -X POST https://livecontext.ai/api/auth/api-keys \\
  -H "X-API-Key: lc_live_xxxxxxxx" \\
  -H "Content-Type: application/json" \\
  -d '{"name": "Reporting client", "scopes": ["table"]}'`}</CodeBlock>
        <p>
          The tool names you can use as scopes are the ones listed under <strong>Tools</strong> when you create a key in
          Settings. See <a href="/mcp-server">MCP server</a> for what each key can reach there.
        </p>

        <h2>Rate limits</h2>
        <Callout variant="info" title="LiveContext Cloud">
          <p>
            On LiveContext Cloud, each user has a per-minute request ceiling that depends on the plan. It counts your
            browser session and all your API keys together. Calls without a user identity (such as webhook calls) are limited per client IP address, and share
            links have their own limits.
          </p>
        </Callout>
        <DocsTable
          caption="Rate limits on LiveContext Cloud"
          head={['Traffic', 'Ceiling']}
          rowHeaders
          rows={[
            ['Free plan', '1,000 requests per minute'],
            ['Starter plan', '2,000 requests per minute'],
            ['Pro plan', '3,000 requests per minute'],
            ['Team plan', '6,000 requests per minute'],
            ['Enterprise plans', '10,000 requests per minute'],
            ['Requests without a user (per IP address)', '600 requests per minute'],
            ['One share link', '60 requests per minute'],
            ['All share links of one owner', '500 requests per day'],
          ]}
        />
        <p>
          Over the ceiling, the response is <strong>429 Too Many Requests</strong> with an empty body and a{' '}
          <code>Retry-After</code> header giving the number of seconds until the window resets. Wait that long before
          you retry.
        </p>

        <h2 id="errors">Errors from the API</h2>
        <p>
          Authentication errors come back as JSON with an <code>error</code> and a <code>message</code> field:
        </p>
        <CodeBlock language="json" title="401 response">{`{
  "error": "Unauthorized",
  "message": "Invalid or unknown API key."
}`}</CodeBlock>
        <DocsTable
          caption="API error status codes"
          head={['Status', 'Meaning', 'What to do']}
          rowHeaders
          rows={[
            ['401', 'No credentials, or the key is unknown or revoked.', 'Check the header name and the key; create a new key if it was revoked.'],
            [
              '403',
              <>
                <code>scope_forbidden</code>: a key limited to specific tools was used outside <code>/mcp</code>.
              </>,
              'Use a full-access key for REST calls.',
            ],
            ['429', 'Rate limit reached (cloud).', 'Wait for the seconds given in Retry-After, then retry.'],
          ]}
        />

        <h2>Call a webhook trigger</h2>
        <p>
          A <strong>Webhook</strong> trigger gives a workflow its own URL, shaped{' '}
          <code>{'https://livecontext.ai/webhook/{token}'}</code>, where the token starts with <code>wh_</code>. You
          find the URL on the trigger in the workflow builder, and every webhook you own is listed in{' '}
          <strong>Settings</strong> &gt; <strong>Public Access</strong>, on the <strong>Webhooks</strong> tab. See{' '}
          <a href="/triggers">Triggers</a> to set one up and <a href="/public-access">Public access &amp; sharing</a>{' '}
          for public URLs, forms, and token regeneration.
        </p>
        <p>
          A webhook call needs no API key. Each webhook accepts exactly one HTTP method (GET, POST, PUT, PATCH, or
          DELETE; POST by default). Another method returns <strong>405 Method Not Allowed</strong>.
        </p>
        <CodeBlock language="bash" title="Start a workflow">{`curl -X POST https://livecontext.ai/webhook/wh_xxxxxxxx \\
  -H "Content-Type: application/json" \\
  -d '{"orderId": 42, "status": "paid"}'`}</CodeBlock>

        <h3>Webhook authentication</h3>
        <DocsTable
          caption="Webhook authentication types"
          head={['Auth type', 'How the caller authenticates']}
          rowHeaders
          rows={[
            ['None', 'No check (the default). Anyone with the URL can call it.'],
            ['Basic', <>HTTP Basic auth: <code>Authorization: Basic base64(user:pass)</code>.</>],
            ['Header', 'A header name and value you choose, sent with every call.'],
            [
              'JWT',
              <>
                <code>Authorization: Bearer &lt;JWT&gt;</code>, verified against a secret you configure (HS256 by
                default, or HS384 / HS512).
              </>,
            ],
          ]}
        />
        <p>A failed check returns <strong>401 Unauthorized</strong>.</p>
        <Callout variant="info" title="Meta verification handshake">
          A <code>GET</code> carrying <code>hub.mode=subscribe</code> and <code>hub.challenge</code> (sent by
          WhatsApp, Facebook, and Instagram when you register a webhook) is answered by echoing the challenge back as
          plain text, so the subscription completes without running the workflow.
        </Callout>

        <h3>Reading the incoming request</h3>
        <p>The Webhook trigger exposes the request to the rest of the workflow:</p>
        <DocsTable
          caption="Webhook trigger output fields"
          head={['Field', 'Notes']}
          rowHeaders
          rows={[
            [
              'payload',
              'The request body. For a GET request the query string becomes the payload; for other methods the JSON body is used, with query parameters merged in as a fallback.',
            ],
            ['headers', 'All request headers.'],
            ['query', 'Query string parameters (alias queryParams).'],
            ['method', 'The HTTP method of this call.'],
            ['triggered_at', 'ISO timestamp of the call (alias triggeredAt).'],
            ['triggered_by', 'Display name of the workflow owner (alias triggeredBy); empty for an unauthenticated webhook.'],
          ]}
        />

        <h3>What the caller gets back</h3>
        <p>
          By default the call is asynchronous: LiveContext acknowledges it and the workflow keeps running. The body is
          JSON with <code>executionId</code>, <code>status</code>, and <code>message</code>:
        </p>
        <CodeBlock language="json" title="202 response">{`{
  "executionId": "3f6c2a8e-1b7d-4c55-9e0a-7d1f2b3c4d5e",
  "status": "triggered",
  "message": "Workflow run triggered successfully"
}`}</CodeBlock>
        <DocsTable
          caption="Webhook response statuses"
          head={['status', 'HTTP status', 'Meaning']}
          rowHeaders
          rows={[
            ['accepted, triggered', '202', 'The workflow started.'],
            ['completed', '200', 'The workflow finished and replied (sync mode, see below).'],
            ['not_found', '404', 'No webhook with this token, or it is disabled. The body is empty.'],
            ['not_active', '409', 'The workflow has no run waiting for this trigger.'],
            ['insufficient_credits', '402', 'The owner has no credits left.'],
            ['rate_limited', '429', 'Too many calls to this webhook; wait and retry.'],
            ['method_not_allowed', '405', 'The HTTP method does not match the webhook.'],
            ['unauthorized', '401', 'The webhook authentication failed.'],
            ['error', '400', 'Any other error; message says why.'],
          ]}
        />

        <h3>Reply from the workflow (Respond to Webhook node)</h3>
        <p>
          To have the workflow write the reply itself, add <code>?sync=true</code> to the URL and place a{' '}
          <strong>Respond to Webhook</strong> node in the workflow.
        </p>
        <CodeBlock language="bash" title="Wait for the workflow's reply">{`curl -X POST "https://livecontext.ai/webhook/wh_xxxxxxxx?sync=true" \\
  -H "Content-Type: application/json" \\
  -d '{"question": "order status", "orderId": 42}'`}</CodeBlock>
        <Callout variant="warn" title="60 second window">
          In sync mode the HTTP response waits until a Respond to Webhook node answers it. After{' '}
          <strong>60 seconds</strong> without an answer, the caller gets the standard <code>202</code> response
          instead, and the workflow keeps running.
        </Callout>
        <DocsTable
          caption="Respond to Webhook node settings"
          head={['Setting', 'Default', 'Notes']}
          rowHeaders
          rows={[
            ['statusCode', '200', 'A value of zero or less is replaced by 200.'],
            ['body', 'Empty', <>Supports <code>{'{{...}}'}</code> templates resolved against workflow data.</>],
            ['contentType', 'application/json', 'The Content-Type of the reply.'],
            ['headers', 'None', 'Extra response headers.'],
          ]}
        />
        <p>
          The node outputs <code>responded</code> (false when no sync webhook call was waiting, for example on a manual
          run), <code>statusCode</code>, and <code>contentType</code>. Because <code>responded=false</code> is not a
          failure, the same workflow runs safely whether or not it was called in sync mode.
        </p>

        <h2>Public share links</h2>
        <p>
          Sharing an application or a conversation creates a share link. A share link gives access to that
          one resource, without a login: a shared application is interactive (visitors use it as you built it), and a shared conversation is read-only. A client presents the token as{' '}
          <code>Authorization: ShareToken &lt;token&gt;</code>. See{' '}
          <a href="/public-access">Public access &amp; sharing</a> to create and revoke links, and{' '}
          <a href="/interfaces">Interfaces &amp; apps</a> and <a href="/marketplace">Marketplace</a> for publishing.
        </p>

        <h2>Chat over the API</h2>
        <p>There are two chat surfaces, and they are not interchangeable.</p>
        <h3>Public chat endpoint (no login)</h3>
        <p>
          Built for chatbot apps wired to a chat trigger. It is synchronous request and response: no streaming, no stop
          endpoint.
        </p>
        <DocsTable
          caption="Public chat endpoints"
          head={['Endpoint', 'Purpose']}
          rowHeaders
          rows={[
            [<code key="s">{'POST /chat/{token}/session'}</code>, 'Create or resume a session.'],
            [<code key="m">{'POST /chat/{token}/message'}</code>, <>Body <code>{'{"sessionId": "...", "message": "..."}'}</code>; returns the reply.</>],
            [<code key="h">{'GET /chat/{token}/history'}</code>, <>Needs an <code>X-Chat-Session</code> header.</>],
            [<code key="c">{'GET /chat/{token}/config'}</code>, 'The chat endpoint configuration.'],
          ]}
        />
        <h3>Authenticated chat API (full-access key)</h3>
        <p>
          For driving a full conversation from your own backend. <code>POST /api/v3/chat</code> sends a message and
          returns <code>{'{conversationId, streamId, model}'}</code> right away while the agent works. Token and tool
          events stream over the WebSocket at <code>/ws</code>, not from this REST call.{' '}
          <code>POST /api/v3/chat/stop</code> with <code>{'{"conversationId": "..."}'}</code> stops an active answer. A
          key limited to specific tools cannot call this API (<code>scope_forbidden</code>).
        </p>

        <h2>Call external APIs from a workflow (HTTP Request node)</h2>
        <p>
          The HTTP Request node makes your workflow the caller. Methods: GET (default), POST, PUT, PATCH, DELETE. The
          URL, query values, header values, body, and auth values all accept <code>{'{{...}}'}</code> templates.
        </p>
        <DocsTable
          caption="HTTP Request node authentication types"
          head={['Auth type', 'Behavior']}
          rowHeaders
          rows={[
            ['none', 'The default; no auth added.'],
            ['basic', <>Base64(user:pass) sent as <code>Authorization: Basic</code>.</>],
            ['bearer', <><code>Authorization: Bearer &lt;token&gt;</code>.</>],
            ['api-key', 'A named key sent in a header or a query parameter.'],
            ['custom-header', 'Any header name and value.'],
          ]}
        />
        <DocsTable
          caption="HTTP Request node body types"
          head={['Body type', 'Behavior']}
          rowHeaders
          rows={[
            ['none', 'The default; no body.'],
            ['json', 'Parsed, then sent as JSON.'],
            ['x-www-form-urlencoded', 'Parsed into form fields.'],
            ['form-data, raw', 'Sent as is.'],
          ]}
        />
        <p>
          Content-Type is set from the body type when you do not supply it; Accept defaults to{' '}
          <code>application/json, */*</code>. An optional <code>timeout</code> (milliseconds) is capped at{' '}
          <strong>300,000 ms (5 minutes)</strong>.
        </p>
        <Callout variant="warn" title="Private addresses are blocked">
          Every URL is checked before the call: only http and https are allowed, and private, loopback, and link-local
          addresses (including <code>localhost</code> and cloud metadata addresses) are refused. You cannot turn this
          off per node.
        </Callout>
        <DocsTable
          caption="HTTP Request node outputs"
          head={['Output', 'Notes']}
          rowHeaders
          rows={[
            ['success', 'false when the server answered with an error status (4xx or 5xx).'],
            ['status', 'The HTTP status code (alias statusCode).'],
            ['statusText', 'The status line, for example 200 OK.'],
            ['data', 'The response body (alias body, response). JSON is parsed; anything else stays a string.'],
            ['headers', 'The first value of each response header; headersMulti holds every value.'],
            ['error', 'The error message, if any (alias errorMessage).'],
          ]}
        />
        <Callout variant="info">
          A non-2xx response does <strong>not</strong> fail the node. It completes with <code>success=false</code> and
          the error body in <code>data</code>, and the workflow continues. Branch on <code>success</code> or{' '}
          <code>status</code> with a Decision node when a failed call must stop the flow.
        </Callout>

        <h2>Troubleshooting</h2>
        <p>
          For what each status code means, see <a href="#errors">Errors from the API</a> and the webhook response
          statuses above. This table starts from what you observe.
        </p>
        <DocsTable
          caption="REST API and webhook troubleshooting"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              <>A webhook call answers <code>409</code> with status <code>not_active</code></>,
              'The workflow has no pinned production version, or the production run of that version has ended.',
              <>
                Pin a version with <strong>Set as production</strong> (see <a href="/workflows">Workflows</a>) and make
                sure its production run is live. See <a href="/public-access">Public access &amp; sharing</a>.
              </>,
            ],
            [
              <>A call with <code>?sync=true</code> returns <code>202</code> instead of your reply</>,
              'No Respond to Webhook node answered within 60 seconds: the workflow has none on the path that ran, or it reached it too late.',
              'Add a Respond to Webhook node on every path the call can take, and place it before the slow steps. The workflow keeps running either way.',
            ],
            [
              <>A REST call with your key answers <code>403</code> <code>scope_forbidden</code></>,
              <>The key is limited to specific tools, so it only works on <code>/mcp</code>.</>,
              'Create a full-access key for scripts that call the REST API, and keep the limited key for your MCP client.',
            ],
            [
              'An HTTP Request node is green, but the next step gets an error message instead of data',
              <>
                A 4xx or 5xx response does not fail the node: it completes with <code>success=false</code> and the error
                body in <code>data</code>.
              </>,
              <>Branch on <code>success</code> or <code>status</code> with a Decision node before using <code>data</code>.</>,
            ],
            [
              <>
                An HTTP Request node fails with <code>Only http and https schemes are allowed (got: &lt;none&gt;)</code>
              </>,
              'The URL has no http or https scheme. This usually means a template in the URL resolved to an empty value.',
              'Check the output of the step the URL template points to, and write the full address, including https://.',
            ],
            [
              <>
                An HTTP Request node fails with <code>Requests to localhost are not allowed</code> or{' '}
                <code>Requests to private/internal network addresses are not allowed</code>
              </>,
              'Private, loopback, and link-local addresses are always refused.',
              'Expose the service on a public address (for example through a tunnel) and call that address instead.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Server} title="MCP server" href="/mcp-server">
            Connect Claude Code, Cursor, Codex, and other MCP clients with an API key.
          </Card>
          <Card icon={Workflow} title="Triggers" href="/triggers">
            Every way a run can start, including the Webhook trigger.
          </Card>
          <Card icon={Globe} title="Public access & sharing" href="/public-access">
            Public URLs, forms, chats, and share links in one place.
          </Card>
          <Card icon={KeyRound} title="Account & settings" href="/account">
            Where your settings pages live.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
