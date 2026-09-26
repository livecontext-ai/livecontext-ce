import { Workflow, Globe, CalendarClock, PlayCircle } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Triggers',
  description:
    'The eight ways a LiveContext run starts, what each trigger hands your workflow, and how pinning a production version decides which triggers are allowed to fire.',
  path: '/docs/triggers',
});

export default function TriggersPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Triggers"
        lead="A trigger is what starts a run. This page covers the eight trigger types, the data each one hands your workflow, which version of your workflow a trigger fires, and the limits and safeguards around them. Read it when you wire an entry point or when a trigger does not fire."
      />

      <DocsProse>
        <h2>The eight trigger types</h2>
        <p>
          A workflow can carry several triggers. Each one starts its own graph and keeps its own
          history of fires. Every trigger output is available downstream as{' '}
          <code>{'{{trigger:<label>.output.<field>}}'}</code>, where <code>&lt;label&gt;</code> is
          the normalized trigger label. A trigger node has no inputs: it is the entry point of the
          graph.
        </p>
        <DocsTable
          caption="Trigger types, what fires them, and their main outputs"
          rowHeaders
          head={['Trigger', 'Fires when', 'Key outputs']}
          rows={[
            ['Webhook', 'an HTTP request reaches the webhook URL.', <code key="o">payload, headers, query, method</code>],
            ['Scheduler', 'a cron time is reached, in the timezone you pick.', <code key="o">execution_count, next_execution</code>],
            ['Manual', 'someone runs the workflow by hand.', <code key="o">triggered_at, triggered_by</code>],
            ['Chat', 'a message arrives, optionally only when it matches a filter.', <code key="o">message, extracted_message, matched</code>],
            ['Form', 'someone submits the form.', <code key="o">form_data, submission_id, one field per input</code>],
            ['Tables', 'a row is created, updated, or deleted in a table.', <code key="o">row, previous_row, event_type</code>],
            ['Workflows', 'another workflow finishes a cycle without a failed step.', <code key="o">parentStatus, result</code>],
            ['Error', 'a cycle of another workflow has a failed step.', <code key="o">status, errorMessage, failedSteps</code>],
          ]}
        />
        <p>Reference any of them by the trigger&apos;s label:</p>
        <CodeBlock language="text">{`{{trigger:webhook.output.payload}}              → the JSON body a webhook received
{{trigger:contact_form.output.email}}          → one field of a form submission
{{trigger:new_orders.output.row.status}}       → a column from the changed table row
{{trigger:on_failure.output.errorMessage}}     → why the upstream workflow failed`}</CodeBlock>
        <p>
          When an agent adds a trigger through the builder, the accepted <code>type</code> values
          are <code>manual</code>, <code>chat</code>, <code>webhook</code>, <code>schedule</code>,{' '}
          <code>table</code>, <code>datasource</code>, <code>workflow</code>, <code>form</code>, and{' '}
          <code>error</code>. <code>table</code> is an alias of <code>datasource</code>. Trigger
          labels must be unique within a workflow, and a trigger label cannot collide with another
          node&apos;s label after normalization.
        </p>
        <Callout variant="info">
          There is no email, IMAP, or polling trigger. To react to mail or to poll an API, run a
          <strong>Scheduler</strong> trigger and read the source with a node. See the{' '}
          <a href="/nodes">Node reference</a>.
        </Callout>

        <h2>Epochs: one run, many fires</h2>
        <p>
          Every trigger type is <strong>reusable</strong>. A fire does not start a new run: it opens
          a new <strong>epoch</strong> on the workflow&apos;s live run, which then rests in{' '}
          <code>WAITING_TRIGGER</code> until the next fire. Each epoch keeps its own results, so you
          can browse every fire of a schedule or every call of a webhook on the same run. Epochs
          are numbered per trigger. See <a href="/runs">Runs &amp; execution</a> for how to read
          them.
        </p>

        <h2>Which version a trigger fires</h2>
        <p>
          Every save records a new plan version. <strong>Pinning</strong> a version, labelled{' '}
          <strong>Set as production</strong> in the version history, tells LiveContext which
          version the outside world talks to. There is no automatic pin.
        </p>
        <DocsTable
          caption="Which version each kind of fire runs, and what happens without a pin"
          rowHeaders
          head={['Fire', 'Version it runs', 'Without a pinned version']}
          rows={[
            [
              'Run from the editor (Manual, or testing a chat or form in the builder)',
              'The version open in the editor',
              'Works. The fire reuses the live run of that version when there is one, and opens a new epoch on it.',
            ],
            [
              'Webhook, Scheduler, Tables, Workflows',
              'The pinned version',
              <>Refused. A webhook answers <code key="c">409</code> (<code key="s">not_active</code>), a schedule is not armed, a table event is skipped.</>,
            ],
            [
              'The public chat or form URL',
              'The pinned version, read when each message or submission arrives',
              'Refused. The message or submission does not start anything.',
            ],
            [
              'Error',
              'The handler workflow’s newest live run (of the pinned version, when one is pinned)',
              'Works. The pin is optional for an error handler, it only needs a live run.',
            ],
          ]}
        />
        <h3>Pin a version</h3>
        <Steps>
          <Step n={1} title="Open the version history">
            Open the workflow in the builder and open its version history.
          </Step>
          <Step n={2} title="Choose Set as production">
            Pick the version and choose <strong>Set as production</strong>. If you have unsaved
            changes, <strong>Save &amp; set as production</strong> saves them as a new version first.
          </Step>
          <Step n={3} title="Check the triggers">
            The Webhook, Scheduler, Tables, Chat, and Form triggers are re-synced to the pinned plan
            straight away; a Workflows trigger reads the pinned version each time it fires. Open <a href="/public-access">Public access</a> or the{' '}
            <a href="/agenda">Agenda</a> to see them armed.
          </Step>
        </Steps>
        <p>
          You can pin a version that has <strong>never run</strong>. If no run exists at that
          version, pinning creates the production run itself. Nothing executes and no credits are
          used; the run simply waits for its first fire. A plan with no trigger is pinned without a
          run.
        </p>
        <p>
          <strong>Remove from production</strong> clears the pin and suspends the workflow&apos;s
          production triggers rather than deleting them. Pinning again arms them again. Moving
          production to another version switches every future execution to it; a chat conversation
          in progress runs the new version from its very next message.
        </p>
        <h3>When the production run ends</h3>
        <p>
          If the production run ends as failed, cancelled, or timed out, LiveContext points
          production at the newest other live run of the pinned version (or, failing that, a
          completed one). It never creates a new run for you. When no such run exists, production
          triggers skip until you pin again (which provisions a run) or reactivate the run from the
          run panel.
        </p>
        <Callout variant="warn">
          <strong>Cancelling</strong> a run also suspends the workflow&apos;s schedules. They come
          back when you reactivate the run. <strong>Stop</strong>, by contrast, only ends the
          current epoch and keeps the triggers armed. See{' '}
          <a href="/runs">Runs &amp; execution</a>.
        </Callout>

        <h2>Webhook</h2>
        <p>
          A webhook trigger listens on a URL of the form <code>{'{base}/webhook/{token}'}</code>.
          Accepted methods are <code>GET</code>, <code>POST</code>, <code>PUT</code>,{' '}
          <code>PATCH</code>, and <code>DELETE</code>; a webhook accepts exactly one, and the default
          is <code>POST</code>.
        </p>
        <DocsTable
          caption="Webhook authentication types"
          rowHeaders
          head={['Auth type', 'How it works']}
          rows={[
            ['none', 'No verification: anyone with the URL can call it.'],
            ['basic', 'HTTP Basic authentication with a username and password.'],
            ['header', 'A header name and value you choose (API-key style).'],
            ['jwt', 'A bearer JWT verified with an HMAC secret: HS256 by default, or HS384 or HS512.'],
          ]}
        />
        <p>
          Authentication fails closed: a request that does not pass the configured check, or a
          webhook configured with an auth type the platform does not recognize, gets{' '}
          <code>401</code>.
        </p>
        <DocsTable
          caption="Webhook trigger outputs"
          rowHeaders
          head={['Field', 'Notes']}
          rows={[
            ['payload', 'The JSON body as an object. Query parameters are merged in for any key the body does not already have. For a GET request, the query parameters are the payload.'],
            ['headers', 'All request headers.'],
            ['query', 'The query string parameters (alias queryParams).'],
            ['method', 'The HTTP method used.'],
            ['triggered_at', 'ISO timestamp (alias triggeredAt).'],
            ['triggered_by', 'Display name of the workflow owner, empty when the request is unauthenticated.'],
          ]}
        />
        <p>
          Two metadata fields, <code>_webhookMethod</code> and <code>_webhookTimestamp</code>, are
          added to the payload. The <code>sync</code> query parameter is removed from it.
        </p>
        <Callout variant="info" title="Waiting for a reply">
          Add <code>?sync=true</code> to the URL to hold the HTTP response until a{' '}
          <strong>Respond to Webhook</strong> node answers it, for up to 60 seconds. After that the
          caller gets a plain <code>202 Accepted</code>. A <code>GET</code> carrying{' '}
          <code>hub.mode=subscribe&amp;hub.challenge=...</code> is treated as a Meta verification
          handshake (WhatsApp, Facebook, Instagram) and echoes the challenge back as plain text.
        </Callout>
        <DocsTable
          caption="HTTP status codes a webhook caller can receive"
          rowHeaders
          head={['Status', 'Meaning']}
          rows={[
            ['202', 'Accepted: the fire was queued.'],
            ['200', 'Completed: a synchronous call finished and returns the response.'],
            ['401', 'Authentication failed.'],
            ['402', 'The workspace is out of credits.'],
            ['404', 'No webhook exists for this token (for example after the token was regenerated).'],
            ['405', 'The request used a different HTTP method than the webhook accepts.'],
            ['409', 'Not active: the workflow has no pinned version, its production run has ended (for example it was cancelled), or the webhook is inactive.'],
            ['429', 'Rate limited. Retry after the delay in the Retry-After header.'],
          ]}
        />
        <p>
          When an agent builds a webhook trigger, the webhook endpoint is created at once, so the
          URL works before the workflow is even saved. Its token is kept across re-pins, so the URL
          does not change as you iterate. It only changes when you regenerate the token from{' '}
          <a href="/public-access">Public access</a>, where you can also read the call history. The
          full HTTP surface, including the Respond to Webhook node, is in{' '}
          <a href="/rest-api">REST API &amp; webhooks</a>.
        </p>

        <h2>Scheduler</h2>
        <DocsTable
          caption="Scheduler trigger parameters"
          rowHeaders
          head={['Parameter', 'Default', 'Notes']}
          rows={[
            ['schedule', '0 * * * * (hourly)', 'Standard 5-field cron: minute, hour, day of month, month, day of week.'],
            ['timezone', 'UTC', 'Any IANA zone, for example America/New_York.'],
            ['enabled', 'true', 'Set false to keep the trigger defined but idle.'],
            ['maxExecutions', 'unlimited', 'Optional cap on the number of fires (alias max_executions).'],
          ]}
        />
        <Callout variant="warn">
          Only 5-field cron is accepted, and the shortest interval is every minute (
          <code>* * * * *</code>). Interval shorthand such as <code>30s</code>, <code>5m</code>,{' '}
          <code>1h</code>, <code>1d</code>, or <code>1w</code> is rejected. A <code>*/N</code> step
          larger than its field allows (minute 59, hour 23, day of month 31, month 12, day of week
          7) is rejected too, because it would never fire. For &ldquo;every 2 hours&rdquo;, write{' '}
          <code>0 */2 * * *</code>, not <code>*/120</code> in the minute field.
        </Callout>
        <p>
          Outputs: <code>triggered_at</code>, <code>execution_count</code> (starts at 1, alias{' '}
          <code>executionCount</code>), <code>next_execution</code> (aliases{' '}
          <code>nextExecution</code> and <code>nextScheduled</code>), and <code>triggered_by</code>{' '}
          (the workflow owner&apos;s display name). A schedule is armed only while the workflow has
          a pinned version. To see upcoming fires, move one, or run one early, use the{' '}
          <a href="/agenda">Agenda</a>.
        </p>

        <h2>Manual</h2>
        <p>
          No parameters. Running the workflow from the editor fires it. The fire reuses the live run
          of the same version when one exists and opens a new epoch on it; a new run is created only
          for a new version or when no live run exists. Outputs: <code>triggered_at</code> and{' '}
          <code>triggered_by</code> (alias <code>user</code>), the <strong>display name</strong> of
          whoever ran it (an empty string if unknown). Extra <code>data_inputs</code> passed when an
          agent executes the workflow are added as top-level fields.
        </p>

        <h2>Chat</h2>
        <p>
          A chat trigger fires on incoming messages. With no filter it fires on every message.
        </p>
        <DocsTable
          caption="Chat trigger outputs"
          rowHeaders
          head={['Field', 'Notes']}
          rows={[
            ['message', 'The raw message text.'],
            ['extracted_message', 'The message with the matched prefix or suffix trimmed (alias extractedMessage).'],
            ['conversation_id', 'Alias conversationId.'],
            ['attachments', 'An array of file references.'],
            ['matched', 'Boolean: whether the optional chatMatch filter matched.'],
            ['match_type, match_value', 'Which rule matched and against what value (aliases matchType, matchValue).'],
            ['triggered_at, triggered_by', 'ISO timestamp and the display name of the sender.'],
          ]}
        />
        <h3>Filter which messages fire the run</h3>
        <p>An optional <code>chatMatch</code> block decides which messages fire the trigger.</p>
        <DocsTable
          caption="chatMatch match types"
          rowHeaders
          head={['Match type', 'Fires when', 'Needs a value?']}
          rows={[
            ['ANY', 'every message (the default).', 'No'],
            ['STARTS_WITH', 'the message starts with the value.', 'Yes'],
            ['ENDS_WITH', 'the message ends with the value.', 'Yes'],
            ['CONTAINS', 'the message contains the value anywhere.', 'Yes'],
            ['EQUALS', 'the message equals the value exactly.', 'Yes'],
            ['REGEX', 'the value, as a regular expression, matches anywhere in the message.', 'Yes'],
          ]}
        />
        <p>
          Options: <code>caseSensitive</code> (default <code>false</code>), <code>trimPrefix</code>{' '}
          (default <code>true</code>, for STARTS_WITH) and <code>trimSuffix</code> (default{' '}
          <code>true</code>, for ENDS_WITH). With trimming on, <code>extracted_message</code> drops
          the matched command token. <code>REGEX</code> matches a substring, not the whole string.
        </p>
        <Callout variant="warn">
          The only camelCase aliases for <code>chatMatch.type</code> are <code>startsWith</code> and{' '}
          <code>endsWith</code>. A typo such as <code>startWith</code> is not recognized and falls
          back to <code>ANY</code>, so the trigger fires on every message instead of filtering.
        </Callout>
        <p>
          A chat trigger built by an agent creates its public chat endpoint at once. You manage the
          endpoint and its share links in <a href="/public-access">Public access</a>.
        </p>

        <h2>Form</h2>
        <p>The form builder offers 17 field types:</p>
        <DocsTable
          caption="Form field types"
          rowHeaders
          head={['Group', 'Types']}
          rows={[
            ['Text', <code key="t">text, email, password, textarea, url, tel, hidden</code>],
            ['Numbers and dates', <code key="t">number, date, datetime, time</code>],
            ['Choices', <code key="t">select, multiselect, checkbox, checkboxGroup, radio</code>],
            ['Files', <code key="t">file</code>],
            ['Accepted aliases', <>string and str become <code key="a">text</code>, int and integer become <code key="b">number</code>, bool and boolean become <code key="c">checkbox</code>, phone becomes <code key="d">tel</code></>],
          ]}
        />
        <p>
          <code>select</code>, <code>multiselect</code>, <code>radio</code>, and{' '}
          <code>checkboxGroup</code> need an <code>options</code> list, either plain strings or{' '}
          <code>{'{label, value}'}</code> pairs.
        </p>
        <p>
          Outputs: <code>submission_id</code>, <code>submitted_at</code> (alias{' '}
          <code>submittedAt</code>), <code>form_data</code> (every field in one object, alias{' '}
          <code>formData</code>), <code>triggered_at</code>, <code>triggered_by</code>, plus one
          output per field, named after the field&apos;s <code>name</code>. The hosted form page and
          its share links are managed in <a href="/public-access">Public access</a>.
        </p>

        <h2>Tables (row changes)</h2>
        <p>
          The <strong>Tables</strong> trigger fires on changes to one of your tables.{' '}
          <strong>One row-level event fires once.</strong>
        </p>
        <p>
          Configuration: <code>table_id</code> (or <code>datasource_id</code>, required),{' '}
          <code>event_types</code> to choose which changes fire (<code>row_created</code>,{' '}
          <code>row_updated</code>, <code>row_deleted</code>; omit it for all three), and an
          optional <code>filter</code> (<code>{'{column, operator, value}'}</code>) so only
          matching rows fire.
        </p>
        <p>
          Filter operators: <code>=</code> (or <code>==</code>, <code>eq</code>), <code>!=</code>{' '}
          (or <code>neq</code>), <code>&gt;</code> (<code>gt</code>), <code>&gt;=</code>{' '}
          (<code>gte</code>), <code>&lt;</code> (<code>lt</code>), <code>&lt;=</code>{' '}
          (<code>lte</code>), <code>in</code>, <code>not_in</code>, <code>contains</code>,{' '}
          <code>starts_with</code>, <code>ends_with</code>, <code>is_null</code>, and{' '}
          <code>is_not_null</code>. The last two take no value; every other operator needs one.
        </p>
        <DocsTable
          caption="Tables trigger outputs"
          rowHeaders
          head={['Field', 'Notes']}
          rows={[
            ['row', 'The row after the change, or its last known state for row_deleted.'],
            ['previous_row', 'The row before the change. Filled only for row_updated, null otherwise.'],
            ['event_type', 'row_created, row_updated, or row_deleted.'],
            ['row_id', 'The primary key of the affected row.'],
            ['datasource_id', 'Which table.'],
            ['triggered_at', 'ISO timestamp, right after the change was saved.'],
            ['triggered_by', 'Alias triggeredBy, empty by default.'],
          ]}
        />
        <Callout variant="warn">
          Row columns are also copied to the top level, but a column named like a reserved field (
          <code>row</code>, <code>previous_row</code>, <code>event_type</code>, <code>row_id</code>,{' '}
          <code>datasource_id</code>, <code>triggered_at</code>, <code>triggered_by</code>) never
          overwrites it. The collision-proof path is always{' '}
          <code>{'{{trigger:<label>.output.row.<column>}}'}</code>.
        </Callout>
        <p>
          When an agent executes the workflow without a real row event (a batch scan), the trigger
          emits <code>data</code> (an array of <code>{'{id, data}'}</code> rows) and{' '}
          <code>count</code> instead. Put a Split over <code>output.data</code> to process every row.
          See <a href="/workflows">Workflows</a>.
        </p>

        <h2>Workflows (chaining)</h2>
        <p>
          Starts this workflow when a parent workflow finishes. The only setting is{' '}
          <code>workflow_id</code>, the parent&apos;s id (required).
        </p>
        <ul>
          <li>
            For a parent with a reusable trigger (the usual case), the chain fires after{' '}
            <strong>every cycle of the parent that had no failed step</strong>.
          </li>
          <li>
            For a single-shot parent run, it fires when that run ends <code>COMPLETED</code>.
          </li>
        </ul>
        <p>
          It never fires on a failure. To react to one, use the <strong>Error</strong> trigger below.
        </p>
        <DocsTable
          caption="Workflows trigger outputs"
          rowHeaders
          head={['Field', 'Notes']}
          rows={[
            ['triggered_at, triggered_by', 'Timestamp and identity.'],
            ['parentWorkflowId', 'Alias parent_workflow_id.'],
            ['parentRunId', 'Alias parent_run_id.'],
            ['parentStatus', 'Alias parent_status.'],
            ['result', 'The parent’s outputs as an object. They are also copied to the top level.'],
            ['parentStatistics', 'Alias parent_statistics.'],
          ]}
        />
        <p>
          Read the parent&apos;s outputs with <code>{'{{trigger:on_done.output.result}}'}</code> or
          directly with <code>{'{{trigger:on_done.output.<parent field>}}'}</code>. There is no{' '}
          <code>parent_outputs</code> field.
        </p>

        <h2>Error</h2>
        <p>
          Starts an error-handler workflow when a parent workflow has a step fail. The only setting
          is <code>parent_workflow_id</code>.
        </p>
        <ul>
          <li>
            For a parent with a reusable trigger, it fires on <strong>any cycle with a failed
            step</strong>, whatever the run&apos;s own status.
          </li>
          <li>
            For a single-shot parent run, it fires when that run ends <code>FAILED</code> (or with
            the older <code>PARTIAL_SUCCESS</code> status).
          </li>
        </ul>
        <Callout variant="warn" title="The handler needs a live run">
          The Error trigger never creates a run: it opens an epoch on the handler workflow&apos;s
          newest live run. Pin the handler, which provisions a run for you, or execute it once. With
          no live run, the parent&apos;s failures are dropped. Anti-loop protection stops a failing
          error handler from firing other error handlers.
        </Callout>
        <DocsTable
          caption="Error trigger outputs"
          rowHeaders
          head={['Field', 'Notes']}
          rows={[
            ['parentWorkflowId, parentRunId', 'Which parent workflow and run failed.'],
            ['status', 'The parent run’s status when the failure was reported.'],
            ['errorMessage', 'What went wrong.'],
            ['triggered_at', 'ISO timestamp (alias triggeredAt).'],
            ['failedSteps, completedSteps, totalSteps, skippedSteps', 'Step counts, present when the parent recorded them.'],
            ['triggered_by', 'Identity field.'],
          ]}
        />

        <h2>Limits and safeguards</h2>
        <h3>Endpoint limits per plan</h3>
        <p>
          On the cloud, the number of webhooks, schedules, chat endpoints, and form endpoints you can
          hold depends on your plan. Each kind has its own quota, shown as a gauge on its tab in{' '}
          <a href="/public-access">Public access</a>. Creating one past the limit is refused.
        </p>
        <DocsTable
          caption="Maximum endpoints of each kind, per plan"
          rowHeaders
          head={['Plan', 'Per kind']}
          rows={[
            ['Free', '3'],
            ['Starter', '10'],
            ['Pro', '50'],
            ['Team, Enterprise', '100'],
            ['Self-hosted Community Edition', 'Unlimited'],
          ]}
        />
        <h3>Chains and error handlers</h3>
        <ul>
          <li>
            A <strong>Workflows</strong> or <strong>Error</strong> trigger is skipped when the target workflow already has 5 runs
            executing at once.
          </li>
          <li>Chains and error handlers never fire across workspaces.</li>
        </ul>
        <h3>Credits and spending caps</h3>
        <p>
          When the workspace is out of credits, a fire is not silently dropped: the trigger node
          fails with the error code <code>CREDIT_EXHAUSTED</code> and every downstream node is
          skipped. The run stays reusable, so the next fire after a top-up works with no action from
          you. A webhook caller may receive <code>402</code>.
        </p>
        <p>
          A workflow or application can also carry a <strong>Cost budget</strong> (set in the{' '}
          <strong>Advanced</strong> section when you create or edit it), which{' '}
          <strong>Resets</strong> every month, every week, or never. It counts agent spend on every
          run except a test fire from the builder. Once the period&apos;s spend reaches it, no new
          epoch opens until the allowance starts again. See <a href="/billing">Plans &amp; billing</a>.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common trigger problems and what to check"
          rowHeaders
          head={['Symptom', 'What to check']}
          rows={[
            ['A webhook answers 409', 'Set a version as production. If one is pinned, the production run has ended: reactivate it from the run panel, or pin again.'],
            ['A webhook answers 404', 'The token was regenerated or the webhook deleted. Copy the current URL from Public access.'],
            ['A webhook answers 401', 'The caller does not send the configured Basic, header, or JWT credentials.'],
            ['A schedule never fires', 'The workflow is not pinned, the schedule is suspended (for example after the run was cancelled), or it reached maxExecutions. Check it in the Agenda.'],
            ['The public chat or form does nothing', 'The workflow has no pinned version.'],
            ['Every run fails at the trigger', 'Look for CREDIT_EXHAUSTED on the trigger node, or a spending cap reached on the run.'],
            ['An error handler never runs', 'The handler has no live run. Pin it or execute it once.'],
            ['A chat trigger fires on every message', 'Check the chatMatch type spelling: an unknown type falls back to ANY.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={PlayCircle} title="Runs & execution" href="/runs">
            What happens after a trigger fires: epochs, statuses, run controls.
          </Card>
          <Card icon={Globe} title="Public access & sharing" href="/public-access">
            Manage webhook, chat, form, and schedule endpoints and share links.
          </Card>
          <Card icon={CalendarClock} title="Agenda" href="/agenda">
            See every scheduled fire, move one, or run it early.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            How a run executes once its trigger fires.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
