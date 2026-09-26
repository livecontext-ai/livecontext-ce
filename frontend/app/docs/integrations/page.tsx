import { Plug, Bot, Workflow, Server, Cloud } from 'lucide-react';
import { CATALOG_INTEGRATIONS_CLAIM } from '@/lib/integrations/integrationCount';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Integrations',
  description: `Connect the ${CATALOG_INTEGRATIONS_CLAIM} catalog integrations, IMAP, SMTP, SSH, SFTP and databases, manage credentials and OAuth, and register your own custom APIs.`,
  path: '/docs/integrations',
});

export default function IntegrationsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Data"
        title="Integrations"
        lead={`LiveContext ships a catalog of ${CATALOG_INTEGRATIONS_CLAIM} third-party integrations, plus native nodes for email, SSH, SFTP, and SQL databases. This page shows how to connect an account once, use it in workflows and agents, keep it healthy, and add an API that is not in the catalog.`}
      />

      <DocsProse>
        <h2>How integrations work</h2>
        <p>
          An integration (Slack, GitHub, Google Drive, and so on) is a collection of{' '}
          <strong>tools</strong>. Each tool is exactly one operation on one endpoint of the
          provider&apos;s API, such as &ldquo;send a message&rdquo; or &ldquo;create an issue&rdquo;.
          Every tool declares its HTTP method, its typed parameters, and the fields it returns.
        </p>
        <p>
          The same tools serve two places. In a workflow, you add a tool as a step. In an agent,
          you put tools in its scope and the agent decides when to call them (see{' '}
          <a href="/agents">Agents</a>). Either way, the tool runs with the account you connected
          for that integration.
        </p>
        <p>
          The catalog holds more than 1,000 integrations. On LiveContext Cloud it is maintained for
          you. A self-hosted install keeps its copy up to date on its own, see{' '}
          <a href="#catalog-updates-on-a-self-hosted-install">Catalog updates on a self-hosted install</a>.
        </p>

        <h2>Before you begin</h2>
        <p>Three Settings pages are involved. Open <strong>Settings</strong> from the sidebar, then:</p>
        <DocsTable
          caption="Settings pages used by integrations"
          rowHeaders
          head={['Settings page', 'What you do there']}
          rows={[
            [
              <strong key="c">Credentials &amp; Variables</strong>,
              'Connect accounts, see their status, reconnect, rename, pick a default, and manage your custom OAuth connections.',
            ],
            [<strong key="a">Custom APIs</strong>, 'Register an API that is not in the catalog.'],
            [
              <strong key="p">Platform Keys</strong>,
              'Administrators only. Holds the OAuth apps and keys the whole install uses. On a self-hosted install, this is where the Standard connection gets its OAuth client.',
            ],
          ]}
        />

        <h2>Connect an account</h2>
        <p>
          You connect a service once, and every tool from that integration reuses the connection.
          The connection is called a <strong>credential</strong>.
        </p>
        <Steps>
          <Step n={1} title="Open the list of integrations">
            Go to <strong>Settings</strong> &gt; <strong>Credentials &amp; Variables</strong>, then
            open the <strong>Available Integrations</strong> tab. Search by name, or filter by
            authentication type. An integration you already connected carries an{' '}
            <strong>Added</strong> badge.
          </Step>
          <Step n={2} title="Pick the integration">
            Click its row. The <strong>Configure</strong> dialog opens. To set up several at once,
            tick their boxes and click <strong>Connect</strong> (with the count, such as <strong>Connect (3)</strong>): the dialog walks you through
            each one in turn.
          </Step>
          <Step n={3} title="Choose how to authenticate">
            Some integrations accept more than one method (for example Airtable takes OAuth2 or a
            bearer token, and Apollo.io an API key or OAuth2). In that case the dialog shows a
            picker so you choose one. Optionally set a <strong>Credential Name</strong> that tells
            accounts apart, such as &ldquo;Gmail (work account)&rdquo;.
          </Step>
          <Step n={4} title="Finish the connection">
            For OAuth2, click <strong>Connect</strong>: you sign in on the provider&apos;s page and
            grant access, then return to LiveContext. For an API key, a bearer token, or a username
            and password, paste the values and click <strong>Save</strong>.
          </Step>
          <Step n={5} title="Check the result">
            The credential now appears on the <strong>My Credentials</strong> tab with its status.
            For OAuth2 credentials, a badge shows how many scopes the provider granted; hover it to
            see them.
          </Step>
        </Steps>
        <p>
          You can also connect from where you need the account. In the workflow builder, an
          integration step without an account offers <strong>Configure Credential</strong>. In
          chat, when the assistant needs a service you have not connected, it shows a{' '}
          <strong>Connection Required</strong> card with a <strong>Connect</strong> button.
        </p>
        <Callout variant="warn">
          Never paste a token, API key, or password into a workflow parameter, a trigger payload, or
          a plain variable. Store it as a credential: credentials are encrypted at rest.
        </Callout>

        <h3>Several accounts for one service</h3>
        <p>
          You can hold more than one credential per integration, for example two Slack workspaces.
          One of them is the <strong>default</strong>: use <strong>Set as default</strong> on the{' '}
          <strong>My Credentials</strong> tab to change it. A tool that runs without a specific
          account uses the default.
        </p>
        <p>
          A workflow step can use another account instead. In the step&apos;s inspector, pick it
          under <strong>Account used by this step</strong>. To choose the account at run time, turn
          on the toggle next to it and write an{' '}
          <strong>Account expression</strong> that resolves to the name (or id) of one of your
          credentials for that integration, for example from a table row or a split item. One
          workflow can then serve several accounts.
        </p>
        <Callout variant="info" title="No silent fallback">
          If the account expression resolves to nothing, or to an account that is not active, the
          step fails. It never falls back to your default account. Bind the expression to data you
          control, not to a field that a visitor can fill in (a public webhook body or a form on a
          shared app).
        </Callout>

        <h3>Personal and team credentials</h3>
        <p>
          A credential belongs to the workspace you are in when you create it. In your personal
          workspace it is yours alone. In an organization, all members can view, use, edit, and
          delete the organization&apos;s credentials. Switch workspace to see the other set. See{' '}
          <a href="/organizations">Organizations &amp; roles</a>.
        </p>

        <h3>Authentication types</h3>
        <DocsTable
          caption="Credential authentication types"
          rowHeaders
          head={['Type', 'What you provide']}
          rows={[
            ['OAuth2', 'Nothing to paste: you sign in on the provider and LiveContext stores the tokens it returns.'],
            ['API key', 'A single key or token.'],
            ['Bearer token', 'A token sent in the Authorization header.'],
            ['Basic auth', 'A username and password.'],
            ['Custom', 'Several fields defined by the integration (for example host, port, username, password).'],
            ['None', 'Nothing: the endpoints are public.'],
          ]}
        />

        <h2>Credential status and reconnecting</h2>
        <p>
          The <strong>My Credentials</strong> tab shows the state of each credential. You can
          filter it by status.
        </p>
        <DocsTable
          caption="Credential statuses"
          rowHeaders
          head={['Status', 'Meaning', 'What to do']}
          rows={[
            [<strong key="a">Active</strong>, 'The credential works.', 'Nothing.'],
            [<strong key="e">Expiring soon</strong>, 'It still works, but the token is close to expiry.', 'Nothing in most cases: OAuth2 refreshes itself. Reconnect if it stays in this state.'],
            [
              <strong key="r">Reconnect required</strong>,
              'The provider revoked the token or it expired for good, so the stored tokens were removed.',
              <>Click <strong>Reconnect</strong> next to the badge and sign in again. Hover the badge to see the reason.</>,
            ],
            [
              <strong key="x">Connection error</strong>,
              'A configuration problem on the platform side, such as an invalid client secret on the OAuth app.',
              'Reconnecting alone does not help. Contact your administrator or support.',
            ],
          ]}
        />
        <p>
          OAuth2 credentials refresh their tokens in the background, so tools keep working without
          you. API keys, bearer tokens, and passwords never refresh: if you rotate one at the
          provider, update the credential yourself. Most credentials can be renamed with the pencil
          icon next to their name; only the display name changes.
        </p>

        <h2>OAuth connections: Standard and Custom OAuth</h2>
        <p>
          For an OAuth2 integration, the <strong>Configure</strong> dialog offers a{' '}
          <strong>Connection mode</strong> with two choices.
        </p>
        <ul>
          <li>
            <strong>Standard</strong> uses the OAuth app that LiveContext already registered with
            the provider. It is one click, and it can only request the permissions (scopes) that app
            is approved for.
          </li>
          <li>
            <strong>Custom OAuth</strong> uses an OAuth app that you create in the provider&apos;s
            developer console. You enter its <strong>Client ID</strong> and{' '}
            <strong>Client Secret</strong>, and the connection can request every scope the
            integration supports, including scopes the Standard app cannot request. The link{' '}
            <strong>Need a custom OAuth app?</strong> switches to this mode, and{' '}
            <strong>How to get my Client ID &amp; Secret?</strong> explains the setup.
          </li>
        </ul>
        <p>
          A few integrations (Google Classroom, for example) exist only as Custom OAuth. For those,
          the dialog opens directly on the custom form with the notice{' '}
          <strong>This integration requires your own OAuth client</strong>.
        </p>
        <p>
          The OAuth apps you registered are listed under{' '}
          <strong>Your custom OAuth connections</strong> on the credentials page. Deleting one
          disconnects only the accounts that app authorized; the dialog tells you how many first.
          Your Standard connections are not affected.
        </p>
        <Callout variant="info" title="Self-hosted">
          On a self-hosted install, the Standard connection uses the OAuth app your administrator
          saved in <strong>Settings</strong> &gt; <strong>Platform Keys</strong>, and it requests
          every scope the integration supports. The Custom OAuth split therefore rarely matters on a
          self-hosted install. If no app is configured for a service, connecting it fails with{' '}
          <strong>This service is not available yet</strong>.
        </Callout>
        <p>
          Providers that require PKCE (for example Airtable, Dropbox, GitLab, Microsoft Outlook and
          Teams, Twitter/X, and Zoom) get it automatically, so there is nothing to configure. LiveContext
          records the scopes the provider actually granted, not only the ones it asked for, so
          unticking an optional scope on the consent screen is reflected accurately.
        </p>

        <h2>Integrations in the workflow builder</h2>
        <p>
          In the builder, click <strong>Add node</strong>. Third-party apps are grouped under the{' '}
          <strong>Integrations</strong> category (plug icon): pick an integration, then one of its
          tools. The <strong>Application triggers</strong> shortcuts add ready-made starting points
          such as <strong>New Gmail email</strong> or <strong>Slack event</strong>.
        </p>
        <p>
          The step&apos;s inspector shows the credential it will use, with{' '}
          <strong>Select a credential</strong>, <strong>Add new credential</strong>, and{' '}
          <strong>Manage all credentials</strong>. For some services, an administrator also provides
          a shared platform account: the step then offers a <strong>Credential source</strong> of{' '}
          <strong>My credential</strong> or <strong>Platform</strong>. With Platform you need no
          setup of your own, and the step bills a per-call markup that the inspector displays.
        </p>
        <p>Reference a tool&apos;s result downstream like any other node output:</p>
        <DocsTable
          caption="Referencing a tool result"
          head={['Expression', 'What it returns']}
          rows={[
            [<code key="a">{'{{mcp:send_message.output.ts}}'}</code>, 'The id Slack returned for the message.'],
            [<code key="b">{'{{mcp:create_issue.output.url}}'}</code>, 'The URL of the issue you just opened.'],
          ]}
        />
        <p>
          The same reference works in decision conditions, split items, and anywhere else an
          expression is accepted. See <a href="/expressions">Expressions &amp; variables</a>.
        </p>

        <h2>Email, SSH, SFTP, and database nodes</h2>
        <p>
          Five protocols are native workflow nodes rather than catalog integrations. Each reads its
          own credential type, which you create on the same <strong>Available Integrations</strong>{' '}
          tab.
        </p>
        <DocsTable
          caption="Protocol nodes and their credentials"
          rowHeaders
          head={['Node', 'Credential to create', 'What the credential holds']}
          rows={[
            [<strong key="s">Send Email</strong>, 'SMTP Email', 'Host, port, username, password or API key, sender address and name, TLS.'],
            [<strong key="i">Email Inbox</strong>, 'IMAP Email', 'The mailbox server and login used to read and act on messages.'],
            [<strong key="h">SSH</strong>, 'SSH', 'Host, port, username, and a password or a private key.'],
            [<strong key="f">SFTP</strong>, 'SFTP', 'Host, port, username, and a password or a private key. Separate from SSH, so a file-transfer account does not need shell access.'],
            [<strong key="d">Database</strong>, 'Database (SQL)', 'Database type (PostgreSQL or MySQL), host, port, database name, username, password, SSL.'],
          ]}
        />
        <p>
          <strong>Send Email</strong> and <strong>Email Inbox</strong> use your default SMTP or IMAP
          credential automatically. The chat assistant reads and sends
          mail with the same two credentials, so you connect a mailbox once for both.
        </p>
        <p>
          <strong>SSH</strong>, <strong>SFTP</strong>, and <strong>Database</strong> use a
          credential only when you select it on the node. They also accept the connection details
          typed directly into the node, but then the host and the password are stored in the
          workflow itself. Prefer a credential. See <a href="/nodes">Node reference</a> for each
          node&apos;s parameters.
        </p>

        <h2>Register a custom API</h2>
        <p>
          If a service is not in the catalog, register it yourself. Its endpoints become tools you
          can add to workflows and give to agents, exactly like catalog tools.
        </p>
        <Steps>
          <Step n={1} title="Open the form">
            Go to <strong>Settings</strong> &gt; <strong>Custom APIs</strong> and click{' '}
            <strong>Register API</strong>. To let the assistant fill everything in from a short
            description, click <strong>Register with AI</strong> instead.
          </Step>
          <Step n={2} title="Describe the API">
            Enter the <strong>API Name</strong>, a <strong>Description</strong>, the{' '}
            <strong>Base URL</strong>, and a <strong>Category</strong>. Optionally upload an{' '}
            <strong>Icon</strong> (an image under 2 MB). <strong>Advanced Settings</strong> holds the{' '}
            <strong>API Version</strong>, a <strong>Documentation URL</strong>, and rate limits in{' '}
            <strong>Requests/second</strong> and <strong>Requests/day</strong>.
          </Step>
          <Step n={3} title="Choose the authentication">
            Set <strong>Authentication Type</strong> to <strong>None</strong>,{' '}
            <strong>Bearer Token</strong>, <strong>API Key</strong>, <strong>Basic Auth</strong>, or
            OAuth2. For OAuth2, also enter the provider&apos;s <strong>Authorization URL</strong>,{' '}
            <strong>Token URL</strong>, and scopes: the form refuses OAuth2 without both URLs.
          </Step>
          <Step n={4} title="Add the endpoints">
            Click <strong>Add Endpoint</strong> once per operation. Give each a{' '}
            <strong>Tool Name</strong>, a <strong>Path</strong>, a <strong>Method</strong> (GET,
            POST, PUT, PATCH, or DELETE), and a <strong>Description</strong>. Add its{' '}
            <strong>Parameters</strong>, each with a <strong>Location</strong> of{' '}
            <code>query</code>, <code>path</code>, <code>body</code>, or <code>header</code>. Pick an{' '}
            <strong>Execution Mode</strong>: <code>sync</code> (the default), <code>async_poll</code>,{' '}
            <code>upload</code>, or <code>streaming</code>.
          </Step>
          <Step n={5} title="Declare the output">
            Fill in <strong>Output Schema (JSON)</strong>: the fields the endpoint returns, each with a
            key, a type, and a description. It is required. Allowed types are <code>string</code>,{' '}
            <code>number</code>, <code>boolean</code>, <code>datetime</code>, <code>object</code>,{' '}
            <code>array</code>, and <code>fileRef</code>.
          </Step>
          <Step n={6} title="Connect an account">
            Registering defines the API; it does not connect it. If the API needs authentication,
            connect it from <strong>Credentials &amp; Variables</strong> like any other integration.
            For OAuth2, you create your own app at the provider and enter its client id and secret
            when you connect.
          </Step>
        </Steps>
        <DocsTable
          caption="Custom API rules"
          rowHeaders
          head={['Topic', 'Behavior']}
          rows={[
            ['Who sees it', 'In your personal workspace, only you. In an organization workspace, the API belongs to the organization: members can update and delete it.'],
            ['Duplicate names', 'Registering a name that already exists is refused. Edit the existing API instead.'],
            ['Updates change tool ids', 'Saving an edit re-creates the API and all its tools under new ids. Workflow steps that used the old tools must be pointed at the new ones. Your stored credential is kept.'],
            ['Deleting', 'Deleting the API also removes your stored credential for it.'],
            ['Address check', 'The base URL is only format-checked when you register. Requests to private or internal addresses are blocked each time a tool runs.'],
            ['PKCE', 'The form has no PKCE option.'],
          ]}
        />

        <h2>Sending files to a tool</h2>
        <p>
          Some tools upload a file, for example sending a photo through Telegram or uploading a
          video to YouTube. You do not handle the encoding: the integration defines how the file is
          sent, whether as a part of a multipart form or, for Google media uploads, as a metadata
          part plus the file in one <code>multipart/related</code> request.
        </p>
        <p>
          Pass a file reference to the tool&apos;s file parameter, such as the output of a Download
          File node (<code>{'{{core:download_file.output.file}}'}</code>) or a file a user
          uploaded. LiveContext fetches the bytes from your storage and attaches them. See{' '}
          <a href="/files">Files &amp; storage</a>.
        </p>

        <h2>Catalog updates on a self-hosted install</h2>
        <p>
          A self-hosted install starts with the catalog bundled in its image, then checks
          LiveContext Cloud about every 15 minutes for a newer, signed version of the integrations
          catalog and applies it. This does not require a cloud link, and it never touches your
          custom APIs or your stored credentials.
        </p>
        <p>
          Administrators can see the sync state in <strong>Settings</strong> &gt;{' '}
          <strong>Cloud</strong> &gt; <strong>Bundles</strong> &gt;{' '}
          <strong>Integrations catalog</strong>, with the last fetch, the last applied version, and
          a <strong>Sync now</strong> button. See <a href="/self-host">Self-hosting</a>.
        </p>

        <h2>Let external MCP clients use LiveContext</h2>
        <p>
          LiveContext is also an MCP server. An external client such as Claude Code, Cursor, or
          Claude Desktop can connect to it with an API key and use the same tools as the in-app
          assistant, including searching and calling catalog integrations. A key can be limited to
          some of those tools. LiveContext does not connect to external MCP servers as a source of
          tools. See <a href="/mcp-server">MCP server</a>.
        </p>

        <h2>Troubleshooting</h2>
        <h3>A step needs a permission the account does not have</h3>
        <p>
          When a tool needs an OAuth scope your credential was not granted, the step inspector (and
          the approval card in chat) shows <strong>Additional permissions needed for</strong> the
          integration, before anything runs. Click <strong>Reconnect (Standard)</strong> to sign in
          again and grant it. If the provider restricts that scope, reconnecting cannot grant it:
          click <strong>Use a custom OAuth connection</strong> and connect with your own OAuth app.
        </p>
        <h3>A tool that used to work now fails</h3>
        <p>
          Open <strong>Credentials &amp; Variables</strong> and check the status. A{' '}
          <strong>Reconnect required</strong> badge means the provider revoked the token (a password
          change, the app removed from your account, a withdrawn scope): click{' '}
          <strong>Reconnect</strong>. If reconnecting keeps failing within a few hours, the
          provider may not have approved the OAuth app for every permission; contact support. A{' '}
          <strong>Connection error</strong> badge needs an administrator.
        </p>
        <h3>An API key stopped working</h3>
        <p>
          Keys do not refresh. If you rotated or revoked the key at the provider, open the
          credential and enter the new one.
        </p>
        <h3>A custom API step cannot find its tool</h3>
        <p>
          Editing a custom API gives its tools new ids. Re-select the tool in each workflow step
          that used it.
        </p>

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Bot} title="Agents" href="/agents">Choose which tools an agent may call.</Card>
          <Card icon={Workflow} title="Node reference" href="/nodes">Email, SSH, SFTP, Database, HTTP Request, and more.</Card>
          <Card icon={Server} title="MCP server" href="/mcp-server">Connect Claude Code, Cursor, or another MCP client.</Card>
          <Card icon={Cloud} title="Self-hosting" href="/self-host">Run your own install and link it to the cloud.</Card>
          <Card icon={Plug} title="Tables & data" href="/tables">Store what your integrations return.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
