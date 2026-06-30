import { Plug, Bot, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Integrations',
  description:
    'Connect external tools in LiveContext: hundreds of pre-built integrations, how tools and credentials work, OAuth connections and scope checks, API keys, and registering your own custom APIs.',
  path: '/docs/integrations',
});

export default function IntegrationsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Integrations"
        lead="LiveContext ships with hundreds of pre-built integrations - together exposing thousands of operations across the services your team already uses. Drop one into a workflow as a tool, or give it to an agent."
      />

      <DocsProse>
        <h2>Tools</h2>
        <p>
          An integration (Slack, GitHub, Google Drive, ...) is a collection of <strong>tools</strong> -
          one tool is one operation, like &ldquo;send a message&rdquo; or &ldquo;create an issue&rdquo;.
          Add a tool as a step and reference its result downstream like any other node:
        </p>
        <CodeBlock language="text">{`{{mcp:send_message.output.ts}}     → the id Slack returned for the message
{{mcp:create_issue.output.url}}    → the URL of the issue you just opened`}</CodeBlock>
        <p>
          Agents use the same tools - an agent with a tool in scope can call it on its own (see{' '}
          <a href="/agents">Agents</a>).
        </p>

        <h2>Credentials</h2>
        <p>
          You connect a service once, in Settings, and reuse that connection across every workflow and
          agent. There are two layers:
        </p>
        <ul>
          <li>
            <strong>The app registration</strong> (shared) - the OAuth app or key configuration an admin
            sets up for an integration. For the built-in catalog this is already done for you.
          </li>
          <li>
            <strong>Your credential</strong> (private) - the actual secret (an access token, an API
            key), stored encrypted and visible only to you. One credential per integration, reused by
            all of that integration&apos;s tools.
          </li>
        </ul>
        <Callout variant="warn">
          Never paste a token or API key into a workflow variable or trigger payload - those are
          auditable but not secret. Always store secrets as a credential in Settings, where they&apos;re
          encrypted at rest and, for OAuth, refreshed automatically.
        </Callout>

        <h2>OAuth connections &amp; scopes</h2>
        <p>
          Connecting an OAuth integration sends you through the provider&apos;s login to grant access;
          the tokens come back and are stored for you. LiveContext refreshes them in the background, so
          tools keep working. If a connection is missing a permission a tool needs - or its access has
          lapsed - you get a clear <strong>&ldquo;reconnect&rdquo;</strong> prompt up front instead of a
          cryptic error mid-run.
        </p>
        <Callout variant="info">
          API-key credentials don&apos;t auto-refresh - if a key is rotated or revoked, update it in
          Settings. Prefer OAuth where a service offers it.
        </Callout>

        <h2>Custom APIs</h2>
        <p>
          Need something that isn&apos;t in the catalog? Register your own API with the same shape as the
          built-ins - its base URL, endpoints, parameters, and auth (OAuth2, API key, bearer, or basic).
          Its operations then appear as tools you can drop into workflows. A couple of guardrails:
        </p>
        <ul>
          <li>The base URL must be publicly reachable - private/internal addresses are rejected for safety.</li>
          <li>Some providers require PKCE on their OAuth flow; set it in the spec or the authorize step is refused.</li>
          <li>Registering the API spec is one thing; completing an OAuth connection still happens in Settings (it needs the browser flow).</li>
        </ul>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents">Scope which tools an agent may call.</Card>
          <Card icon={Workflow} title="Node reference" href="/nodes">Integration step, HTTP Request, and more.</Card>
          <Card icon={Plug} title="Tables &amp; data" href="/tables">Persist what your integrations return.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
