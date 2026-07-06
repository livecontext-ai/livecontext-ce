import { Workflow, Bot, GitBranch, Table2 } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Triggers',
  description:
    'The eight ways a LiveContext run starts: webhook, schedule, manual, chat, form, table (row change), workflow chain, and error - the parameters each accepts, the outputs it exposes, and how pinning, epochs, and reusable triggers work.',
  path: '/docs/triggers',
});

export default function TriggersPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Triggers"
        lead="A trigger is what starts a run. A workflow can carry several, each firing its own graph (its own per-trigger epoch state). This page covers all eight trigger types, exactly what data each hands your workflow, and the pinning model that governs when they're allowed to fire."
      />

      <DocsProse>
        <h2>The eight trigger types</h2>
        <p>
          Every trigger uses the variable prefix <code>trigger</code>, so any output is available
          downstream as <code>{'{{trigger:<label>.output.<field>}}'}</code>. A trigger node has no
          inputs, it&apos;s the entry point of the graph.
        </p>
        <DocsTable
          head={['Trigger', 'Fires when...', 'Key outputs', 'Reusable']}
          rows={[
            [
              'Webhook',
              'an HTTP request hits the workflow URL.',
              <code key="o">payload · headers · query · method</code>,
              'Yes',
            ],
            [
              'Schedule',
              'a recurring time is reached (cron + timezone).',
              <code key="o">execution_count · next_execution</code>,
              'Yes',
            ],
            [
              'Manual',
              'someone clicks Run.',
              <code key="o">triggered_at · triggered_by</code>,
              'Yes',
            ],
            [
              'Chat',
              'a message arrives on the chat endpoint (optionally matching a filter).',
              <code key="o">message · extracted_message · matched</code>,
              'Yes',
            ],
            [
              'Form',
              'someone submits the form.',
              <code key="o">form_data · &lt;field&gt; · submission_id</code>,
              'No',
            ],
            [
              'Table',
              'a row is created, updated, or deleted (internally the Datasource trigger).',
              <code key="o">row · previous_row · event_type</code>,
              'Yes',
            ],
            [
              'Workflow',
              'another workflow COMPLETES successfully.',
              <code key="o">parentStatus · result</code>,
              'No',
            ],
            [
              'Error',
              'another workflow FAILS or ends PARTIAL_SUCCESS.',
              <code key="o">status · errorMessage · failedSteps</code>,
              'No',
            ],
          ]}
        />
        <p>Reference any of them by the trigger&apos;s label:</p>
        <CodeBlock language="text">{`{{trigger:webhook.output.payload}}              → the JSON body a webhook received
{{trigger:contact_form.output.email}}          → one field of a form submission
{{trigger:new_orders.output.row.status}}       → a column from the changed table row
{{trigger:on_failure.output.errorMessage}}     → why the upstream workflow failed`}</CodeBlock>
        <p>
          When adding a trigger through the builder, the accepted <code>type</code> values are{' '}
          <code>manual, chat, webhook, schedule, table, datasource, workflow, form, error</code> (
          <code>table</code> is just an alias, it&apos;s normalized to <code>datasource</code>{' '}
          internally). Trigger labels must be unique within a workflow, and a trigger label can&apos;t
          collide with a non-trigger node&apos;s label after normalization.
        </p>

        <h2>External vs internal dispatch</h2>
        <p>
          Triggers split into two dispatch families. <strong>External</strong> triggers are fired
          server-side by workflow lookup and are pin-gated: <code>webhook, schedule, workflow, error,
          datasource</code>. <strong>Internal</strong> triggers are fired by the frontend with an
          explicit run id and always work in the editor: <code>manual, chat, form</code>.
        </p>

        <h2>Webhook</h2>
        <p>
          A webhook trigger listens on <code>/webhook/{'{token}'}</code>. Accepted methods are{' '}
          <code>GET, POST, PUT, PATCH, DELETE</code> (default <code>POST</code>); a method mismatch
          returns <code>405</code>.
        </p>
        <DocsTable
          head={['Auth type', 'How it works']}
          rows={[
            ['none', 'No verification, anyone with the URL can call it.'],
            ['basic', 'HTTP Basic authentication (username + password).'],
            [
              'header',
              'A custom header name and value you choose, the "API key" style of auth.',
            ],
            [
              'jwt',
              'Bearer JWT verified with an HMAC secret (HS256 by default, or HS384 / HS512).',
            ],
          ]}
        />
        <p>An unrecognized auth type is treated as no-auth. Outputs:</p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['payload', 'Parsed JSON body (or raw). For GET requests the query params become the payload.'],
            ['headers', 'All request headers.'],
            ['query', 'Query string params (alias queryParams).'],
            ['method', 'The HTTP method used.'],
            ['triggered_at', 'ISO timestamp (alias triggeredAt).'],
            [
              'triggered_by',
              'Display name of the workflow owner, empty when the request is unauthenticated.',
            ],
          ]}
        />
        <p>
          For non-GET methods the body is used as the payload, with query params as a fallback. Two
          internal metadata fields, <code>_webhookMethod</code> and <code>_webhookTimestamp</code>, are
          also added to the payload.
        </p>
        <Callout variant="info">
          Add <code>?sync=true</code> to the URL to defer the HTTP response until a &ldquo;Respond to
          Webhook&rdquo; node resolves it, up to 60 seconds, after which the call falls back to a plain{' '}
          <code>202 Accepted</code>. A plain <code>GET</code> carrying{' '}
          <code>hub.mode=subscribe&amp;hub.challenge=...</code> is also recognized as a platform
          verification handshake (Meta WhatsApp / Facebook / Instagram) and echoes the challenge back
          as plain text.
        </Callout>
        <p>
          When a webhook trigger is built by an agent, a standalone webhook row is created immediately
          so the URL works before the workflow is even saved. Its token is stored on that row and
          reused on every re-pin, so the URL doesn&apos;t rotate under you as you iterate.
        </p>
        <p>
          The full HTTP surface (routes, headers, and the &ldquo;Respond to Webhook&rdquo; node) is
          covered in the <a href="/rest-api">REST API &amp; webhooks</a> reference.
        </p>

        <h2>Schedule</h2>
        <DocsTable
          head={['Parameter', 'Default', 'Notes']}
          rows={[
            ['schedule', '0 * * * * (hourly)', 'Standard 5-field cron: minute hour day-of-month month day-of-week.'],
            ['timezone', 'UTC', 'Any IANA zone, e.g. America/New_York.'],
            ['enabled', 'true', 'Set false to keep the trigger defined but idle.'],
            ['maxExecutions', 'unlimited', 'Optional cap on the number of fires.'],
          ]}
        />
        <Callout variant="warn">
          Only standard 5-field cron is accepted, minimum frequency is every minute (
          <code>* * * * *</code>). Interval shorthand like <code>30s</code>, <code>5m</code>,{' '}
          <code>1h</code>, <code>1d</code>, or <code>1w</code> is explicitly rejected, use cron
          instead. A <code>*/N</code> step whose N exceeds the field&apos;s range (minute 59, hour 23,
          day-of-month 31, month 12, day-of-week 7) is also rejected at build time, since it would
          otherwise silently collapse and the schedule would never fire, use{' '}
          <code>0 */2 * * *</code> for &ldquo;every 2 hours&rdquo;, not <code>*/120</code> in the
          minute field.
        </Callout>
        <p>
          Outputs: <code>triggered_at</code>, <code>execution_count</code> (1-based, alias{' '}
          <code>executionCount</code>), <code>next_execution</code> (alias{' '}
          <code>nextExecution</code>/<code>nextScheduled</code>), and <code>triggered_by</code> (the
          workflow owner&apos;s display name, since a schedule fires autonomously but still carries the
          owner&apos;s identity).
        </p>

        <h2>Manual</h2>
        <p>
          No parameters. Every click of Run creates a brand-new run, clicks never accumulate onto an
          existing one. Outputs: <code>triggered_at</code> and <code>triggered_by</code> (alias{' '}
          <code>user</code>), which is the <strong>display name</strong> of whoever ran it, never the
          raw tenant id (empty string if unknown). Custom <code>data_inputs</code> passed at execution
          time are flattened to root-level dynamic fields.
        </p>

        <h2>Chat</h2>
        <p>
          Chat triggers fire on incoming conversation messages. No parameters are required to fire on
          every message; outputs are:
        </p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['message', 'The raw message text.'],
            ['extracted_message', 'Message with the matched prefix/suffix trimmed (alias extractedMessage).'],
            ['conversation_id', 'Alias conversationId.'],
            ['attachments', 'Array of canonical file references.'],
            ['matched', 'Boolean, whether the optional chatMatch filter matched.'],
            ['match_type / match_value', 'Which rule matched and against what value (aliases matchType/matchValue).'],
            ['triggered_at / triggered_by', 'ISO timestamp and the display name of the sender (never the raw tenant id).'],
            ['trigger_id / item_id / item_index / data / count', 'Standard trigger bookkeeping fields.'],
          ]}
        />
        <h3>ChatMatchConfig: gating which messages fire the run</h3>
        <p>
          An optional <code>chatMatch</code> block filters which messages actually fire the trigger.
        </p>
        <DocsTable
          head={['Match type', 'Fires when...', 'Needs a value?']}
          rows={[
            ['ANY', 'every message (the default).', 'No'],
            ['STARTS_WITH', 'the message starts with value.', 'Yes'],
            ['ENDS_WITH', 'the message ends with value.', 'Yes'],
            ['CONTAINS', 'the message contains value anywhere.', 'Yes'],
            ['EQUALS', 'the message equals value exactly.', 'Yes'],
            ['REGEX', 'value, compiled as a Java Pattern, finds a match anywhere in the message.', 'Yes'],
          ]}
        />
        <p>
          Options: <code>caseSensitive</code> (default <code>false</code>),{' '}
          <code>trimPrefix</code> (default <code>true</code>, applies to STARTS_WITH),{' '}
          <code>trimSuffix</code> (default <code>true</code>, applies to ENDS_WITH). When trimming is
          on, <code>extracted_message</code> drops the matched prefix/suffix so downstream nodes see
          the message without the command token; other match types leave the message unchanged.{' '}
          <code>REGEX</code> matches a substring (Java&apos;s <code>find()</code>), not the whole
          string.
        </p>
        <Callout variant="warn">
          The camelCase aliases accepted for <code>chatMatch.type</code> are only{' '}
          <code>startsWith</code> and <code>endsWith</code> (matching is case-insensitive on the type
          string itself). A typo like <code>startWith</code> (missing the &ldquo;s&rdquo;) is{' '}
          <strong>not</strong> recognized and silently falls through to <code>ANY</code>, firing on
          every message instead of filtering.
        </Callout>
        <p>
          Chat triggers built by an agent auto-create a standalone chat endpoint, so the URL is ready
          immediately. Chat always fires the workflow&apos;s <strong>current pinned version</strong>{' '}
          at the moment a message arrives, if you re-pin mid-conversation, the very next message in
          that same conversation already runs the new version.
        </p>

        <h2>Form</h2>
        <DocsTable
          head={['Field type', 'Notes']}
          rows={[
            [
              'text, email, textarea, select, checkbox, number, date, datetime, time, file, phone, url, multiselect, checkboxGroup',
              'Core set.',
            ],
            [
              'password, radio, tel, hidden',
              'Also accepted by the builder.',
            ],
            [
              'Aliases',
              'string/str → text · int/integer → number · bool/boolean → checkbox · phone → tel.',
            ],
          ]}
        />
        <p>
          <code>select</code>, <code>multiselect</code>, <code>radio</code>, and{' '}
          <code>checkboxGroup</code> require an <code>options</code> list (either plain strings or{' '}
          <code>{'{label, value}'}</code> pairs, both accepted).
        </p>
        <p>
          Outputs: <code>submission_id</code>, <code>submitted_at</code> (alias{' '}
          <code>submittedAt</code>), <code>form_data</code> (all fields as one object, alias{' '}
          <code>formData</code>), <code>triggered_at</code>, <code>triggered_by</code>,{' '}
          <code>trigger_id</code>, <code>item_id</code>, <code>item_index</code>, plus one dynamic
          output per field named after that field&apos;s <code>name</code>.
        </p>

        <h2>Table (row changes)</h2>
        <p>
          The UI calls this the &ldquo;Table&rdquo; trigger; internally it&apos;s the event-driven
          Datasource trigger. <strong>One row-level event fires one run.</strong>
        </p>
        <p>
          Config: <code>table_id</code>/<code>datasource_id</code> (required),{' '}
          <code>event_types</code> to pick which changes fire (<code>row_created</code>,{' '}
          <code>row_updated</code>, <code>row_deleted</code>, omit it to subscribe to all three), and
          an optional <code>filter</code> ({'{column, operator, value}'}) so the run only fires on
          matching rows.
        </p>
        <DocsTable
          head={['Filter operators']}
          rows={[
            ['=, ==, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null'],
          ]}
        />
        <p>
          <code>is_null</code>/<code>is_not_null</code> take no value, every other operator requires
          one (<code>=</code> and <code>==</code> are equivalent).
        </p>
        <p>Outputs:</p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['row', 'Current row state for row_created/row_updated, last-known state for row_deleted.'],
            ['previous_row', 'Pre-change row, populated ONLY for row_updated, null otherwise: the before/after pair.'],
            ['event_type', 'row_created · row_updated · row_deleted.'],
            ['row_id', 'The affected row’s primary key.'],
            ['datasource_id', 'Which table.'],
            ['triggered_at', 'ISO-8601 timestamp, right after the change committed.'],
            ['triggered_by', 'Alias triggeredBy, default empty string.'],
            ['trigger_id / item_id / item_index', 'Standard trigger bookkeeping fields.'],
          ]}
        />
        <Callout variant="warn">
          Dynamic row columns are also flattened to the top level so{' '}
          <code>{'{{trigger.<column>}}'}</code> works, but a column that happens to share a name with a
          reserved field (<code>row</code>, <code>previous_row</code>, <code>event_type</code>,{' '}
          <code>row_id</code>, <code>datasource_id</code>, <code>triggered_at</code>,{' '}
          <code>triggered_by</code>) never overwrites it, the structured value always wins. The safe,
          collision-proof path is always{' '}
          <code>{'{{trigger:<label>.output.row.<column>}}'}</code>.
        </Callout>
        <p>
          When fired via <code>workflow(action=&apos;execute&apos;)</code> without a real row event
          (a batch-scan run), the trigger instead emits <code>data</code> (an array of{' '}
          <code>{'{id, data: {<columns>}}'}</code> rows, like <code>find_rows</code>) and{' '}
          <code>count</code>. Chain a <a href="/workflows">Split</a> over <code>output.data</code> to
          process every row.
        </p>

        <h2>Workflow (chaining)</h2>
        <p>
          Fires when a parent workflow <strong>completes successfully</strong>. Config is a single{' '}
          <code>workflow_id</code> (the parent&apos;s UUID, required).
        </p>
        <Callout variant="warn">
          The dispatch hard-gates on the parent&apos;s status being exactly <code>COMPLETED</code>,
          it does <strong>not</strong> branch on failure or cancellation. To react to a failed or
          partially-succeeded parent, use the separate <strong>Error</strong> trigger below.
        </Callout>
        <p>Outputs:</p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['triggered_at / triggered_by', 'Standard timestamp and identity fields.'],
            ['parentWorkflowId', 'Alias parent_workflow_id.'],
            ['parentRunId', 'Alias parent_run_id.'],
            ['parentStatus', 'Alias parent_status, always COMPLETED here.'],
            ['result', "The parent's outputs as an object, also flattened to the root level."],
            ['parentStatistics', 'Alias parent_statistics.'],
            ['trigger_id / item_id / item_index', 'Standard trigger bookkeeping fields.'],
          ]}
        />
        <p>
          There is no <code>parent_outputs</code> field, the parent&apos;s outputs live under{' '}
          <code>result</code> and are also flattened to the root, e.g.{' '}
          <code>{'{{trigger:on_done.output.result}}'}</code> or{' '}
          <code>{'{{trigger:on_done.output.<parent field>}}'}</code>.
        </p>

        <h2>Error</h2>
        <p>
          A system-only trigger that fires when a parent workflow <strong>fails</strong> (parent status{' '}
          <code>FAILED</code> or <code>PARTIAL_SUCCESS</code>). Config is <code>parent_workflow_id</code>.
          Anti-loop protection means a failing error handler cannot itself trigger other error
          handlers.
        </p>
        <Callout variant="warn">
          The Error trigger uses the accumulation pattern: it reuses the parent&apos;s latest
          non-terminal run (typically <code>WAITING_TRIGGER</code>) and never creates its own. After
          adding one, run <code>workflow(action=&apos;execute&apos;)</code> once to bootstrap a seed
          run (it returns <code>BOOTSTRAPPED</code>), otherwise the dispatcher has no active run to
          accumulate into and silently drops the parent&apos;s failures.
        </Callout>
        <p>Outputs:</p>
        <DocsTable
          head={['Field', 'Notes']}
          rows={[
            ['parentWorkflowId / parentRunId', 'Which parent run failed.'],
            ['status', 'FAILED or PARTIAL_SUCCESS.'],
            ['errorMessage', 'What went wrong.'],
            ['triggeredAt', 'ISO timestamp.'],
            ['failedSteps / completedSteps / totalSteps / skippedSteps', 'Per-run step counts.'],
            ['triggered_by', 'Standard identity field.'],
          ]}
        />

        <h2>Multiple triggers, epochs &amp; reusable triggers</h2>
        <p>
          A workflow can carry several triggers, each running its own graph. The run view exposes
          per-trigger DAG state as{' '}
          <code>{'dags: { <trigger_id>: { current_epoch, fire_count, current_spawn } }'}</code>.
        </p>
        <p>
          <strong>Reusable</strong> triggers (webhook, manual, chat, datasource, schedule) can fire many
          times against the same run: each fire increments the epoch counter and accumulates a new
          epoch onto the same live (typically <code>WAITING_TRIGGER</code>) run rather than starting a
          fresh one. Workflow and Error triggers are not in that reusable set.
        </p>

        <h2>Pinning: the must-know</h2>
        <p>
          Every save records a new plan version. External/production triggers (webhook, schedule,
          workflow, error, datasource) fire <strong>only</strong> the <strong>pinned</strong> version,
          with no pin they&apos;re refused (webhooks error, schedules skip); editor runs are unaffected.
          Pinning is explicit, there&apos;s no auto-pin, and the version must have been run at least
          once so a <code>WAITING_TRIGGER</code> run exists to accumulate into.
        </p>
        <Callout variant="info">
          Re-pinning re-syncs all webhook/schedule triggers to the new plan immediately.
          Unpinning suspends every trigger rather than deleting it, re-pinning arms them again. If a
          trigger &ldquo;stopped working&rdquo;, check whether the workflow is still pinned first.
        </Callout>
        <p>
          After a run ends abnormally (cancelled, failed, timed out), a fresh run is auto-prepared so
          schedules and webhooks keep firing on the next event, you don&apos;t have to restart anything.
          A run that finishes cleanly as <em>completed</em> is not re-armed, it&apos;s done on purpose.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">How runs execute once a trigger fires.</Card>
          <Card icon={Table2} title="Tables &amp; data" href="/tables">Row events, filters, and CRUD nodes.</Card>
          <Card icon={GitBranch} title="Node reference" href="/nodes">All node types at a glance.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Put AI in the loop, scoped and budgeted.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
