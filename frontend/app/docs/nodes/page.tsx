import { Webhook, GitBranch, Bot, Table2, Plug, LayoutPanelLeft } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Node reference',
  description:
    'The full catalog of LiveContext workflow nodes: triggers, integration/MCP steps, AI nodes, control flow, data & transform, files & formats, communication, remote & database, utilities, table CRUD, and the interface node - with every port and gotcha.',
  path: '/docs/nodes',
});

export default function NodesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Node reference"
        lead="Every workflow is assembled from roughly sixty node types. They are grouped below by what they do; branching nodes also list the ports you connect from. For how nodes fit together (edges, data flow, parallelism), see Workflows."
      />

      <DocsProse>
        <h2>Node basics</h2>
        <p>
          Every node has a type prefix that marks its category: <code>trigger:</code> (entry
          points), <code>mcp:</code> (tool/API operations), <code>agent:</code> (Agent, Guardrail,
          Classify), <code>core:</code> (control flow, utilities, data), <code>table:</code> (row
          CRUD), <code>interface:</code> (web pages), and <code>note:</code> (canvas annotations).
          A node&apos;s key is <code>prefix:label</code>, where the label is your node name run
          through a fixed normalization: lowercase, transliterate accents, turn every
          non-alphanumeric character into <code>_</code>, collapse repeats, trim the ends. So a
          node named <em>&ldquo;My Webhook&rdquo;</em> is referenced as{' '}
          <code>{'{{trigger:my_webhook.output.field}}'}</code>.
        </p>
        <p>
          <strong>Ports</strong> exist on branching nodes. The <code>core:</code>-family ones
          (Decision, Switch, Option, Loop, Fork, User Approval) are addressed as{' '}
          <code>core:label:port</code> when wiring an edge; the branching AI nodes (Classify,
          Guardrail) use the <code>agent:</code> prefix instead, for example{' '}
          <code>agent:route:category_0</code> or <code>agent:check:pass</code>. Every executable
          node also emits a
          handful of internal metadata fields alongside its declared outputs -{' '}
          <code>node_type</code>, <code>item_index</code>, <code>item_id</code>, and{' '}
          <code>resolved_params</code> - which is why you sometimes see extra keys in a node&apos;s
          raw output beyond the ones documented here.
        </p>

        <h2>Triggers</h2>
        <p>A trigger starts a run. A workflow needs at least one; each runs its own graph.</p>
        <DocsTable
          head={['Trigger', 'Starts a run when...']}
          rows={[
            ['Webhook', 'an HTTP request arrives at the workflow URL.'],
            ['Schedule', 'a recurring time is reached (cron).'],
            ['Manual', 'someone runs it on demand.'],
            ['Tables', 'a row changes in a built-in table.'],
            ['Chat', 'a user sends a chat message.'],
            ['Form', 'a form is submitted.'],
            ['Error', 'another node in the same workflow errors.'],
          ]}
        />
        <p>
          There is no separate &ldquo;Workflow&rdquo; trigger tile in the palette; one workflow
          starts another via the <strong>Sub-Workflow</strong> node (see Remote &amp; database
          below), and the child reacts to the parent&apos;s completion the way the{' '}
          <a href="/triggers">Triggers</a> page describes.
        </p>

        <h2>Integration / MCP step nodes</h2>
        <p>
          These call out to connected tools. An <strong>integration step</strong> calls one
          operation of a connected catalog integration, exposed as an <code>mcp:</code> tool - this
          is the node the builder creates when you pick an API action (see{' '}
          <a href="/integrations">Integrations</a>).
        </p>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['MCP Interface', 'Connects to an MCP server.'],
            ['MCP Tool', 'Uses a tool exposed by a connected MCP server.'],
            ['MCP Resource', 'Accesses a resource exposed by a connected MCP server.'],
            ['Integration step', 'Calls one operation of a connected catalog integration.'],
          ]}
        />

        <h2>AI nodes</h2>
        <p><code>agent:</code> nodes call a model. See <a href="/agents">Agents</a> for models, tools, and budgets.</p>
        <DocsTable
          head={['Node', 'What it does', 'Ports']}
          rows={[
            ['Agent', 'Reasons over a prompt and (optionally) calls tools to produce a result.', '-'],
            ['Classify', 'Routes input into exactly one category by meaning; returns the category plus a confidence score.', <code key="p">category_N</code>],
            ['Guardrail', 'Checks content against rules (PII, toxicity, keyword lists) and passes or fails it.', <code key="p">pass · fail</code>],
            ['Browser Agent', 'An LLM-driven browser: navigates, fills forms, and extracts structured data.', '-'],
          ]}
        />
        <Callout variant="info">
          An agent&apos;s tool list controls access precisely: leave it empty to allow{' '}
          <strong>all</strong> tools, set it to an explicit empty selection to allow{' '}
          <strong>none</strong>, or list specific tools to allow exactly those. Tools are called by
          the agent at run time - they don&apos;t add edges to the graph.
        </Callout>

        <h2>Control flow</h2>
        <p>
          <code>core:</code> nodes route, repeat, parallelize, and join. Branching ones expose
          ports; see <a href="/workflows">Workflows</a> for the full branching/parallelism
          semantics.
        </p>
        <DocsTable
          head={['Node', 'What it does', 'Ports']}
          rows={[
            ['Decision', 'Takes the first branch whose boolean condition is true (exactly one).', <code key="p">if · elseif_N · else</code>],
            ['Switch', 'Runs the first case that matches a value (exactly one).', <code key="p">case_N · default</code>],
            ['Option', 'Takes the first choice whose expression matches (exactly one).', <code key="p">choice_N</code>],
            ['While / Loop', 'Repeats its body while a condition holds, then exits.', <code key="p">body · exit</code>],
            ['Fork', 'Runs all branches in parallel - different tasks.', <code key="p">branch_N</code>],
            ['Split', 'Fans a list into N parallel item contexts - same task per item.', '-'],
            ['Merge', 'Waits for all predecessors (AND only), then continues.', '-'],
            ['Aggregate', "Collects a Split's items back into a single list.", '-'],
            ['Transform', 'Computes new fields from expressions.', '-'],
            ['Wait', 'Pauses for a duration, then resumes on its own.', '-'],
            ['User Approval', 'Pauses until a user approves, rejects, or the timeout fires.', <code key="p">approved · rejected · timeout</code>],
            ['Stop on Error', 'Stops the entire run with an error - every branch is cancelled.', '-'],
            ['Exit', 'Ends the current branch only; other branches keep running.', '-'],
          ]}
        />
        <Callout variant="info">
          <strong>Option</strong> is fully implemented in the engine, first-matching-choice with{' '}
          <code>choice_N</code> ports, but it is currently hidden from the drag palette. It is
          still reachable: an agent building your workflow (or a plan import) can add a node of
          type <code>option</code> directly, and it will wire and run normally.
        </Callout>
        <p>
          The <strong>While / Loop</strong> node&apos;s loop-back edge targets the{' '}
          <code>:iterate</code> port on the same node. Its safety limit,{' '}
          <code>max_iterations</code>, defaults to 10 and can be set from 1 to 10000.
        </p>
        <p>
          <strong>User Approval</strong> supports multi-level approval (require more than one
          approver) and a configurable timeout that defaults to 24 hours when unset. Its declared
          outputs are <code>approver_roles</code>, <code>required_approvals</code>,{' '}
          <code>expires_at</code>, <code>selected_port</code>, <code>approval_context</code>, and{' '}
          <code>delegated_channel</code>.
        </p>
        <Callout variant="info">
          A User Approval node can also <strong>delegate the decision to Telegram</strong>: enable
          the &quot;Delegate via external channel&quot; section, pick your Telegram bot credential
          and a chat id, and the pending approval is sent as a message with inline Approve/Reject
          buttons. Tapping a button resolves the approval exactly like an in-app decision (the
          message is then edited with the verdict and its buttons removed), and in-app resolution
          keeps working in parallel. The message body defaults to the resolved{' '}
          <code>contextTemplate</code>; an optional allow-list restricts which Telegram users may
          decide. When delegation is configured the channel name is emitted as the{' '}
          <code>delegated_channel</code> output.
        </Callout>
        <Callout variant="info">
          You can give a User Approval node a <code>contextTemplate</code> (literal text mixed with{' '}
          <code>{'{{...}}'}</code> expressions), rendered when the run pauses and shown to the
          approver alongside the pending request. The resolved text is also emitted as the{' '}
          <code>approval_context</code> node output, so a downstream step can read exactly what the
          approver saw via <code>{'{{core:<label>.output.approval_context}}'}</code>. This feature
          is live on the current build; if your instance predates it, the field simply resolves
          empty.
        </Callout>
        <p>
          <strong>Stop on Error</strong> is distinct from <strong>Exit</strong>: Exit ends only the
          branch it sits on (other branches continue), while Stop on Error fails the whole run.
        </p>

        <h2>Data &amp; transform</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Transform', 'Computes or reshapes new fields from expressions.'],
            ['Set / Edit Fields', 'Assigns or transforms fields on the input data.'],
            ['Filter', 'Keeps only the items matching a condition.'],
            ['Sort', 'Orders items by a field.'],
            ['Limit', 'Caps the number of items passed through.'],
            ['Remove Duplicates', 'Drops duplicate items.'],
            ['Summarize', 'Applies aggregation functions - sum, average, count, and more.'],
            ['Compare Datasets', 'Compares two datasets and reports the differences.'],
            ['HTML Extract', 'Parses HTML with CSS selectors into structured data.'],
            ['Data Input', 'Provides multiple labeled text, file, and image inputs to a run.'],
          ]}
        />
        <p>
          <strong>Data Input</strong> is not a single value: you define several labeled inputs, and
          each is output under its own label key, for example{' '}
          <code>{'{{core:my_input.output.prompt}}'}</code> for a text field and{' '}
          <code>{'{{core:my_input.output.document}}'}</code> for an uploaded file.
        </p>

        <h2>Files &amp; formats</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Download File', 'Fetches a file from a URL into storage and returns a file reference for later steps or interfaces.'],
            ['Convert to File', 'Converts data into a file - CSV, JSON, XML, and more.'],
            ['Extract From File', 'Extracts structured data out of a file - CSV, JSON, XML, and more.'],
            ['Compression', 'Compresses or decompresses files (gzip, zip).'],
            ['XML', 'Parses or builds XML data.'],
          ]}
        />

        <h2>Communication</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Send Email', 'Sends an email over SMTP.'],
            ['Email Inbox', 'Reads a mailbox over IMAP and acts on its messages.'],
            ['Respond to Chat (Response)', 'Sends a message back to the chat.'],
            ['Respond to Webhook', 'Sends a response back to the HTTP caller that triggered the run.'],
            ['HTTP Request', 'Calls any URL (GET/POST/PUT/PATCH/DELETE) with optional auth; returns status, data, and headers.'],
          ]}
        />
        <Callout variant="warn">
          An HTTP Request to a URL that answers <code>404</code> or <code>500</code> still
          completes - <code>success</code> only means no transport error occurred. Check the
          returned status code in a Decision to handle API errors explicitly.
        </Callout>
        <p>
          <strong>Respond to Chat</strong> takes a single message template (an expression is
          resolved server-side). Its output is just <code>message</code> and <code>sent_at</code>;
          there is no separate markdown/HTML format switch to configure.
        </p>
        <p>
          <strong>Respond to Webhook</strong> is a different node from Respond to Chat: it replies
          to the original HTTP caller, not to a chat conversation, and its outputs are{' '}
          <code>responded</code>, <code>statusCode</code> (defaults to <code>200</code>), and{' '}
          <code>contentType</code> (defaults to <code>application/json</code>).
        </p>

        <h2>Remote &amp; database</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['SSH', 'Executes commands on a remote server over SSH.'],
            ['SFTP', 'Uploads, downloads, lists, or deletes files on a remote server over SFTP.'],
            ['Database', 'Executes SQL queries against PostgreSQL, MySQL, or MSSQL.'],
            ['Sub-Workflow', 'Executes another workflow as a sub-workflow.'],
          ]}
        />

        <h2>Utility</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Code', 'Runs custom code in a sandbox - JavaScript, Python, TypeScript, or Bash.'],
            ['RSS', 'Fetches and parses RSS/Atom feeds.'],
            ['Crypto / JWT', 'Signs, verifies, encodes, or decodes JWT tokens.'],
            ['Date & Time', 'Formats, parses, and manipulates dates and times.'],
            ['Task', 'Creates, reads, updates, deletes, or lists agent tasks.'],
            ['Note', 'A non-executing annotation on the canvas - documentation only.'],
          ]}
        />
        <p>
          <strong>Code</strong> defaults to JavaScript and its outputs are{' '}
          <code>result</code>, <code>stdout</code>, <code>stderr</code>, <code>exitCode</code>,{' '}
          <code>language</code>, <code>executionTime</code>, and <code>success</code>.
        </p>
        <Callout variant="warn">
          A Code node&apos;s output gets wrapped under an extra <code>result</code> key by the
          engine, so a downstream reference is{' '}
          <code>{'{{core:<label>.output.result.<field>}}'}</code>, not{' '}
          <code>{'{{core:<label>.output.<field>}}'}</code>. Feeding a code node&apos;s output into an
          interface&apos;s <code>variable_mapping</code> is the classic place this bites: map past the
          wrapper (<code>{'{{core:normalize.output.result}}'}</code>), not the bare output.
        </Callout>
        <CodeBlock language="text">{`{{core:normalize.output.result.total}}   → the "total" field of a Code node's returned object
{{core:normalize.output.stdout}}         → anything the script printed to stdout`}</CodeBlock>

        <h2>Table CRUD nodes</h2>
        <p>
          All under Core &rarr; Tables in the palette; they read and write the built-in
          spreadsheets. See <a href="/tables">Tables &amp; data</a> for the full column and query
          model.
        </p>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Find Rows', 'Queries rows by a condition, then processes each match in parallel - like Split.'],
            ['Get / Read Rows', 'Returns matching rows as a single output.'],
            ['Create Row', 'Inserts a new row.'],
            ['Create Column', 'Adds a column to a table.'],
            ['Update Row', 'Updates the rows matching a condition.'],
            ['Delete Row', 'Deletes the rows matching a condition.'],
          ]}
        />
        <Callout variant="info">
          <code>where</code>, <code>set</code>, and <code>similarity.column</code> take the{' '}
          <strong>bare</strong> column name (a stray <code>data.</code> prefix is stripped
          automatically). Comparisons are <strong>textual</strong>: <code>=</code>,{' '}
          <code>!=</code>, <code>IN</code>, <code>IS NULL</code>, and <code>LIKE</code> behave as
          expected, but <code>&gt;</code>, <code>&lt;</code>, <code>&gt;=</code>, and{' '}
          <code>&lt;=</code> compare lexicographically, not numerically - a column value of{' '}
          <code>&apos;100&apos;</code> is considered less than <code>&apos;9&apos;</code>. See{' '}
          <a href="/tables">Tables &amp; data</a> for the workaround.
        </Callout>

        <h2>Interface node</h2>
        <DocsTable
          head={['Node', 'What it does']}
          rows={[
            ['Interface', 'Renders an interactive web page (in an iframe) that displays workflow data and collects user input. Can block the run or just display.'],
          ]}
        />
        <p>
          See <a href="/interfaces">Interfaces &amp; apps</a> for signals, the{' '}
          <code>{'{{variable|default}}'}</code> templating syntax, and{' '}
          <code>window.__RESOLVED_DATA__</code>.
        </p>

        <h2>Palette categories</h2>
        <p>The builder groups the palette into five top-level categories:</p>
        <DocsTable
          head={['Category', 'Contains']}
          rows={[
            ['Triggers', 'Every entry point (Webhook, Schedule, Manual, Tables, Chat, Form, Error).'],
            ['MCPs', 'MCP Interface, MCP Tool, MCP Resource, and connected integration steps.'],
            ['AI', 'Agent, Classify, Guardrail, Browser Agent.'],
            ['Flow', 'Control-flow nodes: Decision, Switch, Loop, Fork, Split, Merge, Exit, Wait, User Approval.'],
            ['Core', 'Everything else: data and transform nodes, files and formats, utilities (Code, Crypto/JWT, RSS, Date & Time), remote nodes (SSH, SFTP, Database), the Interface node, and (as a Tables subcategory) the CRUD nodes.'],
          ]}
        />

        <h2>Go deeper</h2>
        <CardGrid cols={3}>
          <Card icon={Webhook} title="Triggers" href="/triggers">Entry points in detail.</Card>
          <Card icon={GitBranch} title="Workflows" href="/workflows">Ports, branching, parallelism.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Configure the AI nodes.</Card>
          <Card icon={Table2} title="Tables &amp; data" href="/tables">CRUD and query semantics.</Card>
          <Card icon={Plug} title="Integrations" href="/integrations">Connect tools and APIs.</Card>
          <Card icon={LayoutPanelLeft} title="Interfaces" href="/interfaces">Build interactive pages.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
