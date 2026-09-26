import { Code, Bot, Plug, Server } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'MCP server',
  description:
    'Connect Claude Code, Codex, Cursor, and other MCP clients to LiveContext over Streamable HTTP with a named lc_live_ API key.',
  path: '/docs/mcp-server',
});

export default function McpServerPage() {
  return (
    <>
      <DocsHero
        eyebrow="Reference"
        title="MCP server"
        lead="LiveContext is also an MCP server. Connect an external MCP client, such as Claude Code, Codex, or Cursor, and it can build and run workflows, work with your tables, and call your integrations with the same tools the LiveContext agent uses."
      />

      <DocsProse>
        <h2>Overview</h2>
        <p>
          The Model Context Protocol (MCP) is an open standard that lets an AI client call tools on a server.
          LiveContext exposes its agent tools through one MCP endpoint, using the <strong>Streamable HTTP</strong>{' '}
          transport and JSON-RPC 2.0. Any client that supports remote HTTP servers can connect with an API key.
        </p>
        <p>
          The MCP server is available on LiveContext Cloud and on the self-hosted Community Edition. Everything you do
          through it runs as you, on your own account and data.
        </p>

        <h2>Find your connection details</h2>
        <p>
          Go to <strong>Settings</strong> &gt; <strong>MCP Server</strong>. The page has three sections:
        </p>
        <DocsTable
          caption="Sections of the MCP Server settings page"
          head={['Section', 'What it shows']}
          rowHeaders
          rows={[
            [
              'Endpoint',
              <>
                The URL of your MCP endpoint, with a copy button, and the number of tools available (shown as{' '}
                &quot;N tools available&quot;). On LiveContext Cloud the URL is <code>https://livecontext.ai/mcp</code>.
              </>,
            ],
            ['API keys', 'Your named keys, their access, and the buttons to create and revoke them.'],
            [
              'Client configuration',
              'Ready-to-copy snippets for Claude Code, JSON configuration files, and Codex, already filled with your endpoint URL.',
            ],
          ]}
        />

        <h2>Create an API key</h2>
        <p>
          Every request to the endpoint needs an API key: <code>lc_live_</code> followed by 64 hexadecimal characters.
          You can hold up to <strong>20 active keys</strong>. Create one key per client so you can revoke one without
          breaking the others.
        </p>
        <Steps>
          <Step n={1} title="Start a new key">
            In <strong>Settings</strong> &gt; <strong>MCP Server</strong>, under <strong>API keys</strong>, select{' '}
            <strong>New key</strong>.
          </Step>
          <Step n={2} title="Name it">
            Type a <strong>Name</strong> that says which client uses it, for example &quot;Cursor on my laptop&quot;.
          </Step>
          <Step n={3} title="Choose the access">
            Leave <strong>Limit to specific tools</strong> off to give the key access to all MCP tools. Turn it on to
            choose tools one by one under <strong>Tools</strong>; you must pick at least one.
          </Step>
          <Step n={4} title="Copy the key">
            Select <strong>New key</strong>. The <strong>Key created</strong> dialog shows the full key{' '}
            <strong>once</strong>. Copy it now, then select <strong>Done</strong>. The list only shows a masked hint
            afterwards.
          </Step>
        </Steps>
        <p>
          To revoke a key, select the <strong>Revoke</strong> (bin) icon next to it, then <strong>Revoke key</strong>.
          Revocation is immediate, and every client configured with that key stops connecting.
        </p>

        <h3>Full access or limited to specific tools</h3>
        <DocsTable
          caption="What full-access and limited keys can reach"
          head={['Key', 'On the MCP endpoint', 'On the rest of the API']}
          rowHeaders
          rows={[
            ['Full access', 'Lists and calls every tool.', 'Works as you on every API call.'],
            [
              'Limited to specific tools',
              <>
                <code>tools/list</code> returns only the tools you picked. Calling any other tool fails exactly like an
                unknown tool.
              </>,
              <>
                Refused with <strong>403</strong> and <code>{'{"error": "scope_forbidden"}'}</code>.
              </>,
            ],
          ]}
        />
        <Callout variant="tip" title="Limit keys you hand to a client">
          A limited key cannot create other keys or reach the REST API, so a leaked one exposes only the tools you
          picked. Use a full-access key only when the same key must also call the <a href="/rest-api">REST API</a>.
        </Callout>

        <h2>Authentication</h2>
        <p>Send the key in either header. Use the first form unless your client only supports Authorization:</p>
        <CodeBlock language="text" title="Headers">{`X-API-Key: lc_live_xxxxxxxx
Authorization: Bearer lc_live_xxxxxxxx`}</CodeBlock>
        <p>
          A request without a valid key gets <strong>401 Unauthorized</strong> with a{' '}
          <code>WWW-Authenticate: Bearer</code> challenge, so your client reports a credentials problem.
        </p>

        <h2>Connect a client</h2>
        <p>
          The <strong>Client configuration</strong> section of the settings page offers the three snippets below, with
          your endpoint URL already filled in. Replace <code>YOUR_API_KEY</code> with the key you created. The examples
          use the cloud URL; on a self-hosted instance, copy the snippets from your own settings page.
        </p>

        <h3>Claude Code</h3>
        <p>Run this command once in a terminal. It adds the server to Claude Code under the name livecontext.</p>
        <CodeBlock language="bash" title="Claude Code (command)">{`claude mcp add --transport http livecontext https://livecontext.ai/mcp --header "X-API-Key: YOUR_API_KEY"`}</CodeBlock>

        <h3>Cursor, Claude Desktop, and other JSON configurations</h3>
        <p>
          Clients that read an <code>mcpServers</code> configuration file take this JSON block. Add the{' '}
          <code>livecontext</code> entry to the file your client uses; its location depends on the client, so check the
          client&apos;s own documentation, and make sure your version supports remote HTTP servers.
        </p>
        <CodeBlock language="json" title="JSON configuration">{`{
  "mcpServers": {
    "livecontext": {
      "type": "http",
      "url": "https://livecontext.ai/mcp",
      "headers": {
        "X-API-Key": "YOUR_API_KEY"
      }
    }
  }
}`}</CodeBlock>

        <h3>Codex</h3>
        <p>
          Add these lines to <code>~/.codex/config.toml</code>:
        </p>
        <CodeBlock language="toml" title="~/.codex/config.toml">{`[mcp_servers.livecontext]
url = "https://livecontext.ai/mcp"

[mcp_servers.livecontext.http_headers]
"X-API-Key" = "YOUR_API_KEY"`}</CodeBlock>

        <h3>Other clients</h3>
        <p>
          Any client that speaks MCP over Streamable HTTP works: point it at the endpoint URL and send the key in the{' '}
          <code>X-API-Key</code> header (or <code>Authorization: Bearer</code>).
        </p>

        <h2>Which tools are exposed</h2>
        <p>
          The server exposes the same tools as the LiveContext agent in chat, for example <code>workflow</code> (build,
          run, and inspect workflows), <code>table</code> (your tables), <code>catalog</code> (search and call your
          connected integrations), <code>interface</code>, and <code>files</code>. The exact list for your install is
          the one under <strong>Tools</strong> when you create a limited key, and the one your client receives from{' '}
          <code>tools/list</code>. The <strong>Endpoint</strong> section shows how many there are.
        </p>
        <p>
          Integrations are not listed one tool per endpoint: your client reaches them through the <code>catalog</code>{' '}
          tool, with the connections you set up in LiveContext. See <a href="/integrations">Integrations</a>.
        </p>

        <h2>Protocol details</h2>
        <DocsTable
          caption="MCP protocol details"
          head={['Property', 'Detail']}
          rowHeaders
          rows={[
            ['Endpoint', <>A single <code>POST /mcp</code>. GET and DELETE return 405 with <code>Allow: POST</code>: the server does not open streams of its own.</>],
            ['Sessions', <>Stateless. No <code>Mcp-Session-Id</code> is issued.</>],
            ['Protocol versions', '2025-06-18, 2025-03-26, 2024-11-05. A supported requested version is echoed back; otherwise the newest is used.'],
            [
              'Methods',
              <>
                <code>initialize</code>, <code>ping</code>, <code>tools/list</code>, <code>tools/call</code>,{' '}
                <code>resources/list</code>, <code>resources/templates/list</code>, <code>resources/read</code>.
              </>,
            ],
            ['Notifications and batches', 'Notifications return 202. JSON-RPC batches are accepted.'],
            [
              'Errors',
              <>
                A tool that fails returns a normal result with <code>isError: true</code>. Protocol errors use JSON-RPC
                codes -32600, -32601, -32602 (including &quot;Unknown tool&quot;), -32603, and -32002 for a resource
                that does not exist.
              </>,
            ],
            ['Resources', 'Resources are not filtered by key: a limited key can list and read them.'],
          ]}
        />

        <h2>Cloud and Community Edition</h2>
        <p>
          The endpoint, the keys, the headers, and the 403 <code>scope_forbidden</code> rule behave the same in both
          editions. The endpoint URL differs: on a self-hosted instance it is built from the public address configured
          for your install, and the <strong>Endpoint</strong> section of your own settings page shows it. The
          per-minute request ceilings described in <a href="/rest-api">REST API &amp; webhooks</a> are those of
          LiveContext Cloud.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="MCP connection troubleshooting"
          head={['Symptom', 'Cause', 'Fix']}
          rowHeaders
          rows={[
            [
              '401 Unauthorized',
              'The key is missing, mistyped, sent in the wrong header, or revoked.',
              <>Send it as <code>X-API-Key</code> (or <code>Authorization: Bearer</code>); create a new key if it was revoked.</>,
            ],
            [
              <>403 <code>scope_forbidden</code></>,
              <>A key limited to specific tools was used on a path other than <code>/mcp</code>.</>,
              'Use a full-access key for REST calls, or keep the limited key on the MCP endpoint.',
            ],
            [
              'Unknown tool',
              "The tool is not in the key's list, or the name is wrong.",
              'Create a key that includes the tool, or use a full-access key.',
            ],
            ['405 Method Not Allowed', 'The client tried a GET or DELETE (for example to open a stream).', 'Use a client that supports Streamable HTTP over POST.'],
            [
              '429 Too Many Requests (cloud)',
              'The per-minute ceiling of your plan is reached.',
              <>Wait for the seconds given in <code>Retry-After</code>.</>,
            ],
            [
              'Timeout or 524 on a long call',
              'A call that runs for minutes, such as executing a long workflow, outlived the HTTP request. The run keeps going on the server.',
              <>
                Do not re-send the call: that can start another run. Find the run with the <code>workflow</code> tool&apos;s{' '}
                <code>runs</code> action and follow it with <code>get_run</code>.
              </>,
            ],
          ]}
        />
        <Callout variant="warn" title="Long runs: fire, then poll">
          On LiveContext Cloud, an HTTP request held open for about 100 seconds can be closed by the network edge with
          status 524, while the work continues on the server. Treat a timeout on an execution as &quot;still
          running&quot;, not as a failure. Start the run with <code>execute</code>, then poll it with{' '}
          <code>get_run</code>, or wait with <code>wait_run</code> and a <code>timeout_seconds</code> below 100 (its default, 120, is
          above that); when{' '}
          <code>wait_run</code> answers <code>timed_out: true</code>, the run is still going, so call it again.
        </Callout>

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Code} title="REST API & webhooks" href="/rest-api">
            API keys over REST, webhook triggers, and error formats.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            The same tools, used by the agents inside LiveContext.
          </Card>
          <Card icon={Plug} title="Integrations" href="/integrations">
            The connections the catalog tool calls for you.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Run your own instance; the MCP server works there too.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
