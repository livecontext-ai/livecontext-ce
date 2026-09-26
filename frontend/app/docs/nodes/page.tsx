import type { ReactNode } from 'react';
import { Webhook, GitBranch, Bot, Table2, LayoutPanelLeft, Braces } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Node reference',
  description:
    'Every LiveContext workflow node, grouped as in the builder palette: purpose, key, ports, parameters, outputs, and the gotchas worth knowing.',
  path: '/docs/nodes',
});

/** Inline list of identifiers, each in its own <code>, comma separated. */
function codes(...names: string[]): ReactNode {
  return (
    <>
      {names.map((name, i) => (
        <span key={name}>
          {i > 0 ? ', ' : null}
          <code>{name}</code>
        </span>
      ))}
    </>
  );
}

export default function NodesPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Node reference"
        lead="Every node you can add to a workflow, grouped the way the builder palette groups them: Triggers, Integrations, AI, Flow, and Core (with its Tables section). Each entry gives the node's key, ports, main parameters, and the output fields you can reference."
      />

      <DocsProse>
        <h2>How to read this reference</h2>
        <p>
          A node&apos;s <strong>key</strong> is a prefix plus its normalized name. The prefix
          depends on the node family: <code>trigger:</code>, <code>mcp:</code> (integration
          steps), <code>agent:</code> (AI nodes), <code>core:</code>, <code>table:</code>,{' '}
          <code>interface:</code>, and <code>note:</code>. The name is lowercased, accents are
          transliterated, every other character becomes <code>_</code>, and repeats and edges are
          trimmed. A node you name <em>Fetch Orders</em> is therefore{' '}
          <code>core:fetch_orders</code>, and you read its output with{' '}
          <code>{'{{core:fetch_orders.output.<field>}}'}</code>. See{' '}
          <a href="/expressions">Expressions</a> for the full syntax.
        </p>
        <p>
          <strong>Ports</strong> exist only on branching nodes. When you wire an edge from a port,
          you address it as <code>key:port</code>, for example <code>core:check:if</code> or{' '}
          <code>agent:route:category_0</code>. Numbered ports start at 0.
        </p>
        <p>
          <strong>Outputs</strong> listed here are the fields a node stores after it runs. Only
          these declared fields are kept, so they are the ones you can reference downstream. The
          inspector may show extra run details (such as the resolved parameters) that are not part
          of the output.
        </p>
        <Callout title="Cloud plans">
          On the cloud, a node that your plan does not include shows a plan marker in the palette.
          You can still add it and build around it, but runs that reach it are refused until your
          plan includes it. The self-hosted Community Edition has no such restriction.
        </Callout>

        <h2>Triggers</h2>
        <p>
          A trigger starts a run. A workflow can have several triggers, and each one drives its own
          part of the graph. Configuration details for each trigger are on the{' '}
          <a href="/triggers">Triggers</a> page.
        </p>
        <DocsTable
          caption="Trigger nodes"
          rowHeaders
          head={['Palette name', 'Starts a run when', 'Main outputs']}
          rows={[
            ['Webhook', 'An HTTP request reaches the workflow URL.', codes('payload', 'headers', 'query', 'method', 'triggered_at')],
            ['Manual', 'Someone runs the workflow on demand.', codes('triggered_at', 'triggered_by')],
            ['Tables', 'A row changes in a built-in table.', codes('row', 'previous_row', 'event_type', 'row_id', 'triggered_at')],
            ['Workflows', 'Another workflow completes.', codes('parentWorkflowId', 'parentRunId', 'parentStatus', 'result', 'parentStatistics')],
            ['Error', 'A run of the workflow it watches fails.', codes('parentWorkflowId', 'parentRunId', 'status', 'errorMessage', 'failedSteps', 'completedSteps', 'totalSteps', 'skippedSteps')],
            ['Chat', 'A chat message arrives (optionally matching a rule).', codes('message', 'extracted_message', 'conversation_id', 'attachments', 'matched')],
            ['Scheduler', 'A cron expression or time interval comes due.', codes('triggered_at', 'execution_count', 'next_execution')],
            ['Form', 'A custom form is submitted.', codes('submission_id', 'submitted_at', 'form_data')],
          ]}
        />
        <p>
          A webhook&apos;s request body is under <code>payload</code>, so a body field is{' '}
          <code>{'{{trigger:webhook.output.payload.userId}}'}</code>, not{' '}
          <code>{'{{trigger:webhook.output.userId}}'}</code>.
        </p>

        <h3>Workflows and Error triggers</h3>
        <p>
          Both react to <em>another</em> workflow, which you pick when you add them. The{' '}
          <strong>Workflows</strong> trigger fires when that workflow finishes a cycle without a
          failed step. The <strong>Error</strong> trigger fires when a cycle of that workflow has a
          failed step, and hands you the failure details (<code>errorMessage</code>,{' '}
          <code>failedSteps</code>, and the step counts). A failing error-handling workflow does not set off other error handlers.
        </p>
        <Callout variant="warn">
          The error-handling workflow must have a live run to receive the event; pinning a version
          creates one. An Error trigger in a workflow that has never been pinned or run has nowhere
          to deliver.
        </Callout>

        <h2>Integrations</h2>
        <p>
          The <strong>Integrations</strong> tile opens the app picker (Gmail, Slack, Notion, and
          hundreds more). Choosing an operation adds an <strong>integration step</strong>, keyed{' '}
          <code>mcp:&lt;name&gt;</code>. It calls that one operation with the credential you
          connected. Its parameters and output fields are those of the chosen operation, shown in
          the inspector. See <a href="/integrations">Integrations</a>.
        </p>

        <h2>AI</h2>
        <p>
          AI nodes call a model and use the <code>agent:</code> prefix. See{' '}
          <a href="/agents">Agents</a> for models, tools, and budgets.
        </p>
        <DocsTable
          caption="AI nodes"
          rowHeaders
          head={['Palette name', 'What it does', 'Ports', 'Main outputs']}
          rows={[
            ['Agents', 'Reasons over a prompt and can call tools to produce a result.', 'None', codes('response', 'tokens_used', 'tool_calls', 'iterations_used', 'model', 'provider')],
            ['Guardrail', 'Checks content for PII, toxicity, or keywords, then passes or fails it.', codes('pass', 'fail'), codes('passed', 'violations', 'details', 'sanitized')],
            ['Classify', 'Routes the input to exactly one category by meaning.', codes('category_N'), codes('selected_category', 'selected_category_index', 'confidence', 'reasoning', 'probabilities')],
            [<a key="ba" href="/browser-agent">Browser Agent</a>, 'Drives a browser: navigates, fills forms, extracts structured data.', 'None', codes('final_result', 'extracted_data', 'stop_reason', 'final_url', 'pages_visited', 'screenshots')],
            ['Generate', 'Produces an image, video, audio, voice, or music clip from a prompt.', 'None', codes('file', 'model', 'kind', 'provider', 'billed_quantity', 'billed_unit', 'billed_credits')],
          ]}
        />

        <h3>Generate</h3>
        <p>
          Pick an asset type and a <strong>Model</strong>. The model decides the format produced,
          which parameters are accepted (prompt, duration, aspect ratio, resolution, voice, input
          images, and so on), and what each run costs; the price is shown before you run. The
          result is always a file: <code>output.file</code> is a file reference you can pass whole
          to a later file parameter, and <code>kind</code> says what was produced. The same models
          are available in <a href="/studio">Studio</a>.
        </p>
        <Callout variant="warn">
          Generate fails when no asset comes back, and by then the call may already have been
          charged. Check the price shown for the model before you run it in a loop or a Split.
        </Callout>

        <h2>Flow</h2>
        <p>
          Flow nodes route, repeat, parallelize, and join. For how branches, merges, and parallel
          items behave at run time, see <a href="/workflows">Workflows</a>.
        </p>
        <DocsTable
          caption="Flow nodes"
          rowHeaders
          head={['Palette name', 'What it does', 'Ports', 'Main outputs']}
          rows={[
            ['If / else', 'Takes the first branch whose condition is true (exactly one).', codes('if', 'elseif_N', 'else'), codes('selected_branch', 'selected_branch_index', 'skipped_branches', 'evaluations')],
            ['Switch', 'Takes the first case that matches a value, or the default.', codes('case_N', 'default'), codes('selected_branches', 'selected_case_index', 'skipped_branches', 'evaluations')],
            ['Split', 'Runs the steps after it once per list item, in parallel.', 'None', codes('items', 'item_count', 'split_id', 'spawn_reason', 'terminated')],
            ['Aggregate', 'Collects the per-item results of a Split back into lists.', 'None', codes('aggregated_count')],
            ['While', 'Repeats a group of steps while a condition is true.', codes('body', 'exit'), codes('iteration', 'maxIterations', 'terminated', 'selected_path', 'reason')],
            ['User Approval', 'Pauses until a person approves, rejects, or the timeout passes.', codes('approved', 'rejected', 'timeout'), codes('approval_context', 'selected_port', 'expires_at', 'required_approvals', 'delegated_channel')],
            ['Transform', 'Computes new fields from expressions.', 'None', codes('transformed', 'evaluations')],
            ['Merge', 'Waits for every incoming branch, then continues once.', 'None', codes('merged_branches', 'sources', 'merged_items', 'source_count', 'success_count')],
            ['Fork', 'Runs every branch in parallel.', codes('branch_N'), codes('branch_count', 'branches')],
            ['Sub-Workflow', 'Triggers another workflow and waits for its result.', 'None', codes('result', 'subWorkflowId', 'subRunId', 'success')],
          ]}
        />

        <h3>If / else and Switch</h3>
        <p>
          Both take exactly one path. <strong>If / else</strong> checks its conditions in order (
          <code>if</code>, then <code>elseif_0</code>, <code>elseif_1</code>, and so on) and falls
          back to <code>else</code>. <strong>Switch</strong> compares one value against its cases
          and falls back to <code>default</code>. Every path not taken is skipped. The{' '}
          <code>evaluations</code> output shows how each condition resolved, which is the fastest
          way to see why a branch was chosen.
        </p>
        <p>
          The AI builder can also add an <strong>Option</strong> node (first true choice wins,
          ports <code>choice_N</code>). It is not in the palette, but it runs normally when a plan
          contains it.
        </p>

        <h3>Split</h3>
        <p>
          Give Split a list expression. Every step after it runs once per item, in parallel, and
          inside those steps <code>{'{{core:<split>.output.current_item}}'}</code> and{' '}
          <code>current_index</code> hold the item being processed. Those two fields exist only
          inside the split; they are not stored on the Split node itself.
        </p>
        <ul>
          <li>
            <strong>Max items</strong> defaults to 100. Items beyond the cap are dropped. It
            accepts an expression, which must resolve to a positive whole number or the node fails.
          </li>
          <li>
            <code>spawn_reason</code> is <code>items_spawned</code>, or <code>empty_list</code>{' '}
            when there was nothing to process. <code>terminated</code> is always{' '}
            <code>true</code>: Split finishes as soon as it has handed out the items.
          </li>
        </ul>

        <h3>Aggregate</h3>
        <p>
          Aggregate closes a Split: it waits for every item and returns one list per field you
          configure, plus <code>aggregated_count</code>.
        </p>
        <Callout variant="warn">
          On the Split path, Aggregate turns each collected value into text. Numbers become
          strings, objects and lists become JSON text, and a file reference becomes a string a
          later file parameter cannot use. To hand files or typed objects onward, collect them in a{' '}
          <strong>Code</strong> node instead.
        </Callout>

        <h3>While</h3>
        <p>
          While runs the steps on its <code>body</code> port, then evaluates its condition again.
          The last body step connects back to the node&apos;s <code>iterate</code> input. When the
          condition is false the loop leaves through <code>exit</code>. <code>iteration</code>{' '}
          starts at 0.
        </p>
        <ul>
          <li>
            <strong>Max iterations</strong> defaults to 10 and accepts 1 to 10,000 (or an
            expression that resolves to a positive whole number).
          </li>
          <li>
            <code>reason</code> tells you how it ended: <code>condition_false</code> (normal),{' '}
            <code>iterations_exhausted</code> (no condition, so it ran the set number of times), or{' '}
            <code>max_iterations_reached</code>.
          </li>
        </ul>
        <Callout variant="warn">
          Reaching <strong>Max iterations</strong> while the condition still says continue is a
          failure: the run is marked failed and the <code>exit</code> path is not taken. Size the
          cap generously, or loop without a condition when you really mean &ldquo;repeat N
          times&rdquo;.
        </Callout>

        <h3>User Approval</h3>
        <p>
          The run pauses and the approver sees the request in the app. The node continues on{' '}
          <code>approved</code>, <code>rejected</code>, or <code>timeout</code>. The timeout
          defaults to 24 hours. You can require several approvals and restrict who may decide by
          role.
        </p>
        <ul>
          <li>
            <strong>Approval context</strong>: text mixed with <code>{'{{...}}'}</code>{' '}
            expressions, shown to the approver. The resolved text is also the{' '}
            <code>approval_context</code> output.
          </li>
          <li>
            <strong>Continuation</strong> (only inside a Split, where each item gets its own
            request): <strong>After all items</strong> (the default) starts the next steps once
            every item has been decided. <strong>Per item (immediate)</strong> lets each decided item continue
            immediately; a Merge after it still waits for all items.
          </li>
          <li>
            <strong>Delegate via external channel</strong>: also sends the request to Telegram,
            Slack, Discord, WhatsApp, or Microsoft Teams with approve and reject buttons. Pick a{' '}
            <strong>Destination</strong> like a credential (the workspace default, a connected
            destination, or another chat by hand). You can set a message template, an image (for
            example an interface screenshot), custom button labels, and a list of allowed user IDs.
            The in-app decision keeps working in parallel. See <a href="/channels">Channels</a>.
          </li>
        </ul>

        <h3>Merge and Fork</h3>
        <p>
          <strong>Fork</strong> starts every connected branch at once. <strong>Merge</strong>{' '}
          waits until every incoming branch has finished or been skipped, then continues once. Any
          node with several incoming edges waits the same way, and any node with several outgoing
          edges fans out the same way.
        </p>

        <h3>Transform</h3>
        <p>
          Each field you define is an expression. The results are under{' '}
          <code>transformed</code>, so a field named <code>total</code> is{' '}
          <code>{'{{core:<label>.output.transformed.total}}'}</code>. An object built in a
          Transform reaches later steps as text; when a later parameter needs a real object or
          list, build it in a <strong>Code</strong> node.
        </p>

        <h3>Sub-Workflow</h3>
        <p>
          Sub-Workflow fires a trigger in another workflow (the child), passes it an input, and
          waits for that child run to finish. Parameters: <strong>Workflow ID</strong>,{' '}
          <strong>Input mapping</strong> (one expression that resolves to the object to pass;
          empty forwards this workflow&apos;s trigger data), <strong>Timeout (seconds)</strong>,
          and <strong>Max depth</strong>.
        </p>
        <ul>
          <li>
            <strong>The child needs a live run.</strong> The node never creates one. Pin a version
            of the child (which creates its run); an unpinned child uses its most recently started
            active run, whatever its version.
          </li>
          <li>
            <strong>Outputs are keyed by the child step&apos;s bare name.</strong> A child
            Transform named <em>Step Result</em> is read as{' '}
            <code>{'{{core:call.output.result.step_result.output.transformed.field}}'}</code>,
            without a <code>core:</code> prefix before <code>step_result</code>. With the prefix
            the expression resolves to nothing, silently.
          </li>
          <li>
            <strong>
              <code>success</code> is <code>true</code> whenever the child run finished
            </strong>
            , even if one of its nodes failed. Check the child&apos;s actual output before relying
            on it.
          </li>
          <li>
            <strong>Timeout</strong> defaults to 300 seconds and is capped at 1,500. It only limits
            how long this node waits; the child keeps running. <strong>Max depth</strong> defaults
            to 5 (at most 10), and a workflow cannot call itself in a cycle.
          </li>
        </ul>

        <h2>Core</h2>
        <p>
          Core holds everything else. The tables below follow the palette, grouped by purpose.
          Detail entries for several of these nodes follow the tables.
        </p>

        <p>
          <strong>Run control and replies</strong>
        </p>
        <DocsTable
          caption="Core nodes: run control and replies"
          rowHeaders
          head={['Palette name', 'What it does', 'Main outputs']}
          rows={[
            ['Exit', 'Ends the branch it sits on. Other branches keep running.', codes('exited_at', 'reason', 'status')],
            ['Stop on Error', 'Fails the whole run and cancels every branch.', codes('error_message', 'error_code', 'stopped_at', 'status')],
            ['Wait', 'Pauses for a duration, then continues on its own (see note).', codes('waited_ms', 'status', 'started_at', 'completed_at', 'duration_ms', 'expires_at')],
            ['Respond to Chat', 'Sends one message (a template) back to the chat that started the run.', codes('message', 'sent_at', 'message_id')],
            ['Respond to Webhook', 'Answers the HTTP caller of a Webhook trigger: status, content type, headers, body.', codes('responded', 'statusCode', 'contentType')],
            ['Interface', 'Shows a web page built from workflow data; can pause the run until the user continues.', codes('interface_id', 'screenshot', 'pdf', 'video')],
            ['Note', 'An annotation on the canvas. It never runs.', 'None'],
          ]}
        />

        <p>
          <strong>HTTP, files, and media</strong>
        </p>
        <DocsTable
          caption="Core nodes: HTTP, files, and media"
          rowHeaders
          head={['Palette name', 'What it does', 'Main outputs']}
          rows={[
            ['HTTP Request', 'Calls any URL (see note).', codes('success', 'status', 'statusText', 'data', 'headers', 'error')],
            ['Download File', 'Fetches a file from an http or https URL (up to 50 MB) into storage.', codes('file', 'source_url')],
            ['Public Link', 'Turns a file into a public, time-limited signed URL (see note).', codes('url', 'expires_at', 'ttl_minutes', 'file')],
            ['Media', 'Processes audio and video files (see note).', codes('file', 'duration_seconds', 'size_bytes', 'has_video', 'has_audio', 'video', 'audio')],
            ['Data Input', 'Provides labeled text, file, and image inputs; each is output under its own label.', 'One field per input label'],
            ['Convert to File', 'Writes items to a CSV, XLSX, JSON, or TXT file.', codes('file', 'format', 'row_count', 'success')],
            ['Extract From File', 'Reads a file into items or text (see note).', codes('items', 'format', 'rowCount', 'columns', 'mode', 'total_chunks')],
            ['Compression', 'Compresses or decompresses zip, gzip, or deflate data.', codes('result', 'file', 'operation', 'format', 'success')],
          ]}
        />

        <p>
          <strong>Data</strong>
        </p>
        <DocsTable
          caption="Core nodes: data"
          rowHeaders
          head={['Palette name', 'What it does', 'Main outputs']}
          rows={[
            ['Filter', 'Keeps the items that match conditions (equals, contains, greater than, is empty, and more).', codes('items', 'rejected_items', 'count', 'rejected_count', 'original_count')],
            ['Sort', 'Orders items by a field.', codes('sorted_items', 'count')],
            ['Limit', 'Keeps the first or last N items.', codes('items', 'count', 'original_count')],
            ['Remove Duplicates', 'Drops items that repeat on the chosen fields.', codes('items', 'original_count', 'deduplicated_count', 'removed_count')],
            ['Summarize', 'Count, sum, average, min, max, count distinct, or concatenate, optionally grouped by fields.', codes('groups', 'total_groups', 'total_items', 'aggregation_count')],
            ['Compare Datasets', 'Compares two lists on key fields.', codes('matched', 'onlyInA', 'onlyInB', 'matchedCount', 'totalA', 'totalB')],
            ['Set / Edit Fields', 'Assigns fields with a type (string, number, boolean, JSON).', codes('fields', 'output', 'keep_only_set', 'count')],
            ['HTML Extract', 'Pulls structured data out of HTML with CSS selectors.', codes('items', 'count', 'matched_root', 'errors')],
            ['XML', 'Converts XML to JSON or JSON to XML.', codes('result', 'operation', 'success')],
          ]}
        />

        <p>
          <strong>Code, messaging, and utilities</strong>
        </p>
        <DocsTable
          caption="Core nodes: code, messaging, and utilities"
          rowHeaders
          head={['Palette name', 'What it does', 'Main outputs']}
          rows={[
            ['Code', 'Runs JavaScript, Python, TypeScript, or Bash in a sandbox (see note).', codes('result', 'stdout', 'stderr', 'exitCode', 'success')],
            ['Send Email', 'Sends an email with your SMTP credential.', codes('sent', 'messageId', 'recipients', 'subject', 'success')],
            ['Email Inbox', 'Reads a mailbox over IMAP, or marks read or unread, flags, moves, or deletes a message.', codes('messages', 'count', 'folders', 'action', 'success')],
            ['RSS', 'Fetches and parses an RSS or Atom feed.', codes('items', 'channel', 'itemCount', 'feedFormat', 'success')],
            ['Crypto / JWT', 'Hash, HMAC sign and verify, encrypt and decrypt, create, decode, and verify JWTs, Base64, UUIDs, and random secrets.', codes('result', 'operation')],
            ['Date & Time', 'Parse, format, convert time zones, add, subtract, compute differences, extract parts, or get the current time.', codes('result', 'operation')],
            ['Task', 'Creates, reads, updates, deletes, or lists agent tasks.', codes('task', 'task_id', 'tasks', 'count', 'success')],
          ]}
        />

        <p>
          <strong>Remote systems</strong>
        </p>
        <DocsTable
          caption="Core nodes: remote systems"
          rowHeaders
          head={['Palette name', 'What it does', 'Main outputs']}
          rows={[
            ['SSH', 'Runs a command on a remote server.', codes('success', 'exit_code', 'stdout', 'stderr', 'duration_ms')],
            ['SFTP', 'Uploads, downloads (up to 50 MB), lists, deletes, renames files, or creates folders.', codes('success', 'operation', 'remote_path', 'files', 'file', 'new_path')],
            ['Database', 'Runs select, insert, update, delete, or raw SQL on PostgreSQL, MySQL, or MSSQL (up to 10,000 rows).', codes('success', 'rows', 'columns', 'row_count', 'affected_rows')],
          ]}
        />

        <h3>Exit and Stop on Error</h3>
        <p>
          <strong>Exit</strong> ends only its own branch; the run can still succeed. Its{' '}
          <code>reason</code> defaults to &ldquo;Branch exited&rdquo; and <code>status</code> is{' '}
          <code>exited</code>. <strong>Stop on Error</strong> fails the whole run with your error
          message and code.
        </p>

        <h3>Wait</h3>
        <p>
          In the canvas you enter the duration in milliseconds, up to 10 minutes. A wait of 3,000
          ms or less runs inline. A longer wait pauses the run and resumes it on a timer.
        </p>

        <h3>HTTP Request</h3>
        <p>
          Methods: <code>GET</code>, <code>POST</code>, <code>PUT</code>, <code>PATCH</code>,{' '}
          <code>DELETE</code>, <code>HEAD</code>, <code>OPTIONS</code>. Authentication: none,
          basic, bearer, API key, or a custom header. An optional timeout is available.
        </p>
        <Callout variant="warn">
          A non-2xx response does not fail the node. On a 404 or 502 the node still completes, with{' '}
          <code>output.success</code> set to <code>false</code>, <code>output.status</code> set to
          the code, and <code>output.data</code> holding the error body. The next steps run as
          usual. Only transport errors (DNS, connection, timeout) fail the node. Branch on{' '}
          <code>{'{{core:<label>.output.success}}'}</code> or <code>.status</code> when an error
          must stop the chain.
        </Callout>

        <h3>Download File, Public Link, and file references</h3>
        <p>
          <strong>Download File</strong> follows redirects (each hop is checked again) and returns{' '}
          <code>file</code>, a file reference with <code>path</code>, <code>name</code>,{' '}
          <code>mimeType</code>, and <code>size</code>. Pass the whole reference to a file
          parameter with <code>{'{{core:<label>.output.file}}'}</code>. See{' '}
          <a href="/files">Files &amp; storage</a>.
        </p>
        <p>
          <strong>Public Link</strong> signs a file from an earlier node into a public URL that
          anyone can open until it expires, for APIs that pull media from a URL (Instagram,
          TikTok) instead of accepting an upload. <strong>Link lifetime</strong> is 5 to 10,080
          minutes (7 days), 240 by default. <strong>Disposition</strong> is inline (shown in the
          browser) or attachment (download prompt). A link cannot be revoked before it expires.
        </p>

        <h3>Media</h3>
        <p>
          Operations: <strong>Probe</strong> (read duration, streams, codecs),{' '}
          <strong>Mux audio</strong> (one audio track onto one video), <strong>Mix</strong> (up to
          8 audio tracks, optionally onto a video), <strong>Extract audio</strong>,{' '}
          <strong>Concat</strong> (up to 8 videos back to back, with cut or crossfade),{' '}
          <strong>Frame</strong> (one still image, the middle by default), <strong>Overlay</strong>{' '}
          (burn an image onto a video), and <strong>Burn captions</strong>.
        </p>
        <ul>
          <li>
            Every file parameter must be one whole expression that resolves to a file reference,
            such as <code>{'{{core:download.output.file}}'}</code>.
          </li>
          <li>
            The Concat clip list is fixed in the node (one entry per clip); it cannot come from a
            single list expression.
          </li>
          <li>The node fails when the media renderer is not available on your installation.</li>
        </ul>

        <h3>Convert to File and Extract From File</h3>
        <p>
          <strong>Convert to File</strong> writes <code>csv</code>, <code>xlsx</code>,{' '}
          <code>json</code>, or <code>txt</code>. <strong>Extract From File</strong> parses{' '}
          <code>csv</code>, <code>xlsx</code>, and <code>json</code> into <code>items</code>, or
          extracts the text of a <code>pdf</code>, <code>html</code>, <code>docx</code>,{' '}
          <code>txt</code>, <code>csv</code>, or <code>json</code> file, optionally split into
          chunks (recursive or by separator, with a chunk size and overlap). For XML, use the{' '}
          <strong>XML</strong> node.
        </p>

        <h3>Code</h3>
        <p>
          Upstream results arrive in <code>$input</code> (JavaScript and TypeScript),{' '}
          <code>_input</code> (Python), or <code>INPUT</code> (Bash), keyed by each earlier
          node&apos;s normalized name: a file from <em>Download Demo Video</em> is{' '}
          <code>$input.download_demo_video.file</code>, not <code>$input.file</code>. Return data
          by assigning <code>$output</code> (or <code>_output</code> in Python). The timeout
          defaults to 10 seconds, up to 120.
        </p>
        <p>
          What you return is stored under <code>result</code>, so downstream you read{' '}
          <code>{'{{core:<label>.output.result.<field>}}'}</code>. Values keep their types, which
          makes Code the right place to build objects, lists, or file references for later steps.
        </p>
        <CodeBlock language="text" title="Reading a Code node's output">{`{{core:normalize.output.result.total}}   the "total" field of the returned object
{{core:normalize.output.stdout}}         anything the script printed`}</CodeBlock>

        <h2>Tables</h2>
        <p>
          The <strong>Tables</strong> section of Core reads and writes the built-in tables. These
          nodes use the <code>table:</code> prefix. See <a href="/tables">Tables &amp; data</a> for
          columns and query options.
        </p>
        <DocsTable
          caption="Table nodes"
          rowHeaders
          head={['Palette name', 'What it does', 'Main outputs']}
          rows={[
            ['Create Row', 'Inserts one or more rows.', codes('row_id', 'created_at', 'inserted_count', 'inserted_values', 'warnings')],
            ['Create Column', 'Adds columns to a table.', codes('createdColumns')],
            ['Get Row', 'Returns matching rows as one output.', codes('rows', 'row_count', 'has_more', 'offset')],
            ['Update Row', 'Updates the rows that match a condition.', codes('updated_count', 'rows_affected', 'updated_at', 'warnings')],
            ['Delete Row', 'Deletes the rows that match a condition (a condition is required).', codes('deleted_count', 'rows_affected', 'deleted_at')],
            ['Find Rows', 'Queries rows into a list.', codes('items', 'item_count', 'total_before_limit', 'max_items', 'has_more', 'exit_reason')],
          ]}
        />

        <h3>Find Rows</h3>
        <p>
          Find Rows returns its matches as one list in <code>items</code>. It does not run the
          following steps once per row. To process each row, add a <strong>Split</strong> on{' '}
          <code>{'{{table:<label>.output.items}}'}</code>. It keeps 100 rows by default. You can
          set a higher limit, but one read returns at most 500 rows, so a limit above 500 behaves
          like 500. With the default limit, <code>has_more</code> tells you whether rows were left
          out; with a limit you set, compare <code>item_count</code> to that limit instead.{' '}
          <code>exit_reason</code> is <code>items_found</code> or <code>empty_result</code>.
        </p>
        <Callout>
          Conditions use the bare column name (<code>email</code>, not <code>data.email</code>),
          and comparisons are textual: <code>&gt;</code> and <code>&lt;</code> compare
          alphabetically, so <code>&apos;100&apos;</code> sorts before <code>&apos;9&apos;</code>.
          To delete every row, use the condition <code>id IS NOT NULL</code>.
        </Callout>

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={GitBranch} title="Workflows" href="/workflows">
            Edges, branching, parallel items.
          </Card>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            Configure every entry point.
          </Card>
          <Card icon={Braces} title="Expressions" href="/expressions">
            Reference outputs and use functions.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Configure the AI nodes.
          </Card>
          <Card icon={Table2} title="Tables & data" href="/tables">
            Columns and query semantics.
          </Card>
          <Card icon={LayoutPanelLeft} title="Interfaces" href="/interfaces">
            Pages, signals, and data binding.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
