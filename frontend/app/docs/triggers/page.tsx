import { Workflow, Bot, GitBranch } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Triggers',
  description:
    'How LiveContext runs start: the webhook, schedule, chat, form, manual, table, and workflow-chain triggers - what fires each, the outputs they expose, and why pinning a version matters.',
  path: '/docs/triggers',
});

export default function TriggersPage() {
  return (
    <>
      <DocsHero
        eyebrow="Build"
        title="Triggers"
        lead="A trigger is what starts a run. A workflow can have several, and each runs its own graph. This page covers every trigger type, the data it hands your workflow, and the one setting people most often miss: pinning."
      />

      <DocsProse>
        <h2>The trigger types</h2>
        <DocsTable
          head={['Trigger', 'Fires when...', 'Useful outputs']}
          rows={[
            ['Webhook', 'an HTTP request hits the workflow URL.', <code key="o">payload · headers · query</code>],
            ['Schedule', 'a recurring time is reached (cron + timezone).', <code key="o">execution_count · triggered_at</code>],
            ['Chat', 'a user sends a message to the chat endpoint.', <code key="o">message · conversation_id</code>],
            ['Form', 'someone submits the form.', <code key="o">form_data · {`<field>`} · submitted_at</code>],
            ['Manual', 'you click Run.', <code key="o">triggered_at · triggered_by</code>],
            ['Table', 'a row matching a filter changes - one run per row.', <code key="o">{`<columns>`} · row_index · total_rows</code>],
            ['Workflow', 'another workflow finishes (success / failure / cancelled).', <code key="o">parent_status · parent_outputs</code>],
          ]}
        />
        <p>Reference any of them the usual way, by the trigger&apos;s label:</p>
        <CodeBlock language="text">{`{{trigger:webhook.output.payload}}            → the JSON body a webhook received
{{trigger:contact_form.output.email}}        → one field of a form submission
{{trigger:new_orders.output.total_amount}}   → a column from the changed table row
{{trigger:on_failure.output.parent_outputs}} → the upstream workflow's outputs`}</CodeBlock>

        <h2>Pinning: the must-know</h2>
        <p>
          Production triggers fire against a <strong>pinned</strong> version of your workflow. Until
          you pin a version, the workflow is still in draft and its webhook/form/chat/schedule
          triggers will <strong>not</strong> fire - incoming calls are refused. Pinning a version also
          arms its triggers (assigns the webhook URL, the cron schedule, the form/chat endpoints).
        </p>
        <Callout variant="warn">
          If a trigger &ldquo;stopped working&rdquo;, the first thing to check is whether the workflow
          is still pinned. Unpinning suspends every trigger (it does <strong>not</strong> delete
          them); re-pinning arms them again. A draft workflow simply never fires.
        </Callout>
        <p>
          After a run ends abnormally (cancelled, timed out, failed), LiveContext automatically
          prepares a fresh run so your schedule and webhooks keep firing on the next event - you
          don&apos;t have to restart anything. (A run that finishes cleanly as <em>completed</em>{' '}
          isn&apos;t re-armed; it&apos;s considered done on purpose.)
        </p>

        <h2>Per-trigger notes</h2>
        <h3>Webhook</h3>
        <p>
          Each webhook trigger gets a stable URL that survives unpin/redeploy. Send an{' '}
          <code>Idempotency-Key</code> header and duplicate deliveries within 24 hours return the
          cached response instead of firing twice - handy when an external system retries. For
          guarantees beyond 24 hours, make the workflow itself idempotent (for example, check a table
          before acting).
        </p>
        <h3>Schedule</h3>
        <p>
          Configure a cron expression and a timezone. The run exposes a 1-based{' '}
          <code>execution_count</code> across all fires and the <code>triggered_at</code> timestamp.
        </p>
        <h3>Manual</h3>
        <p>
          Every click of Run creates a new run (clicks don&apos;t accumulate). The output{' '}
          <code>triggered_by</code> is the display name of whoever ran it.
        </p>
        <h3>Table</h3>
        <p>
          A table trigger fans out <strong>one run per matching row</strong>. Each run sees the row&apos;s
          columns plus its position - <code>row_index</code> (0-based) and <code>total_rows</code>.
        </p>
        <h3>Chat</h3>
        <p>
          Chat sessions are <em>sticky</em> by default: a conversation that&apos;s already running keeps
          using the version that was pinned when it started, even if you re-pin in the middle - so
          users don&apos;t get a mid-conversation jump. The next new conversation picks up the new
          version.
        </p>
        <h3>Workflow chain</h3>
        <p>
          A workflow trigger lets one workflow start another when it finishes. The child can react to
          success, failure, or cancellation, and receives the parent&apos;s status and outputs - a
          clean way to build error handlers and pipelines.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Workflow} title="Workflows" href="/workflows">How runs execute once a trigger fires.</Card>
          <Card icon={GitBranch} title="Node reference" href="/nodes">All node types at a glance.</Card>
          <Card icon={Bot} title="Agents" href="/agents">Put AI in the loop, scoped and budgeted.</Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
