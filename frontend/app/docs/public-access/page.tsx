import { Webhook, CalendarClock, Code2, Store } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Public access & sharing',
  description:
    'Manage every public entry point in one place: webhook, chat, form, and schedule endpoints, call logs, token regeneration, usage limits, and shared links.',
  path: '/docs/public-access',
});

export default function PublicAccessPage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Public access & sharing"
        lead="Webhooks, public chats, hosted forms, schedules, and shared links are all ways for the outside world to reach your work. The Public Access settings page lists them in one place. This page explains what each tab shows, how to rotate or revoke an entry point, and what must be true for it to answer."
      />

      <DocsProse>
        <h2>Overview</h2>
        <p>
          Open <strong>Settings</strong> and choose <strong>Public Access</strong> (&ldquo;Manage
          triggers, endpoints, and shared links across your resources&rdquo;). The page has six tabs:
        </p>
        <DocsTable
          caption="Public Access tabs"
          rowHeaders
          head={['Tab', 'What it lists', 'Actions']}
          rows={[
            ['Webhooks', 'Every webhook endpoint, with its URL, HTTP method, and authentication type.', 'View Logs, Regenerate Token, Delete'],
            ['Chats', 'Public chat endpoints (Endpoints) and the links you shared for them (Shared Links).', 'Share, Regenerate Token, Delete'],
            ['Forms', 'Hosted form endpoints (Endpoints) and the links you shared for them (Shared Links).', 'Share, Regenerate Token, Delete'],
            ['Schedules', 'Every schedule, with its cron expression and timezone, its execution count, and its next run.', 'Enable or Disable, Delete'],
            ['Conversations', 'Chat conversations you shared.', 'Delete'],
            ['Applications', 'Applications you shared.', 'Delete'],
          ]}
        />
        <p>
          Every card shows whether the entry point is <strong>Active</strong> or{' '}
          <strong>Inactive</strong>, the workflow it belongs to (or <strong>Not linked</strong>), an{' '}
          <strong>App</strong> badge when it belongs to an application you installed, and its
          creation date. Click the workflow name to open it. The URL on each card has a copy button.
        </p>
        <Callout variant="info">
          Webhook, chat, form, and schedule endpoints are <strong>created from triggers</strong>:
          add the trigger to a workflow and its endpoint appears here. This page is where you
          inspect, rotate, pause, and remove them. See <a href="/triggers">Triggers</a>.
        </Callout>

        <h2>Usage limits</h2>
        <p>
          Each tab starts with a usage gauge (&ldquo;3 / 10 used&rdquo;). On the cloud, the limit
          depends on your plan; creating an endpoint or a link past it is refused. The self-hosted
          Community Edition has no limit.
        </p>
        <DocsTable
          caption="Limits per plan"
          rowHeaders
          head={['Plan', 'Webhooks, chats, forms, schedules (each)', 'Shared links (all kinds together)']}
          rows={[
            ['Free', '3', '5'],
            ['Starter', '10', '20'],
            ['Pro', '50', '50'],
            ['Team', '100', '100'],
            ['Enterprise', '100', '200'],
            ['Self-hosted Community Edition', 'Unlimited', 'Unlimited'],
          ]}
        />
        <p>
          The shared-link quota is global: the Chats, Forms, Conversations, and Applications tabs all
          draw from the same allowance. When it is full, sharing shows{' '}
          <strong>Shared link limit reached</strong> with a link back to this page so you can delete
          unused links.
        </p>

        <h2>Webhooks</h2>
        <p>
          Each webhook card shows the full URL, the HTTP method it accepts, its authentication type
          when it is not public (<strong>Basic Auth</strong>, <strong>Header Auth</strong>, or{' '}
          <strong>JWT Auth</strong>), and a <strong>cURL</strong> button with a ready-to-run example.
        </p>
        <h3>Read the call history</h3>
        <p>
          Choose <strong>View Logs</strong> to open the <strong>Call History</strong>: ten calls per
          page, each with its outcome, how many workflows it triggered, and when it arrived. The
          outcome is <code>triggered</code> (at least one workflow started),{' '}
          <code>no_active_workflow</code> (the call was accepted but no workflow was live to run), or{' '}
          <code>inactive</code> (the webhook is switched off). A call that is refused before it is
          accepted (wrong credentials, wrong HTTP method) gets its error response and is{' '}
          <strong>not</strong> recorded here, so an empty history can also mean the caller is being
          refused.
        </p>
        <h3>Regenerate a token</h3>
        <Steps>
          <Step n={1} title="Open the card menu">
            On the webhook, chat, or form card, open the actions menu.
          </Step>
          <Step n={2} title="Choose Regenerate Token">
            Confirm the prompt: &ldquo;This will invalidate the current URL.&rdquo;
          </Step>
          <Step n={3} title="Update your callers">
            Copy the new URL from the card and update every system that called the old one. The old
            URL stops working at once (a webhook answers <code>404</code>).
          </Step>
        </Steps>
        <p>
          Regenerate a token whenever a URL may have leaked. It is the only way to change a
          webhook&apos;s URL: saving or re-pinning the workflow keeps the same token.
        </p>

        <h2>Public chats and forms</h2>
        <p>
          A chat trigger gets a public chat endpoint, and a form trigger gets a hosted form page.
          Both are reachable without a login by anyone who has the URL. A chat card shows a{' '}
          <strong>Memory</strong> badge when conversation memory is on, which shows the conversation
          history to visitors.
        </p>
        <p>
          Each tab has two views: <strong>Endpoints</strong> (the endpoints themselves) and{' '}
          <strong>Shared Links</strong> (links you created with <strong>Share</strong>). A shared
          link is a separate public URL of the form <code>{'{base}/s/{token}'}</code> that you can
          disable, regenerate, or delete without touching the endpoint. The API behind a public chat
          is described in <a href="/rest-api">REST API &amp; webhooks</a>.
        </p>

        <h2>Schedules</h2>
        <p>
          The Schedules tab lists every schedule with its cron expression and timezone, how many
          times it ran, and when it runs next. Choose <strong>Disable</strong> to stop a schedule
          without deleting it, and <strong>Enable</strong> to arm it again. To move one run, run it
          early, or see schedules on a calendar, use the <a href="/agenda">Agenda</a>.
        </p>

        <h2>Share links</h2>
        <p>
          You create a share link from the resource itself: <strong>Share conversation</strong> in
          the chat sidebar, <strong>Share</strong> on a chat or form endpoint, or the share action on
          a published application in your Applications library.
        </p>
        <Steps>
          <Step n={1} title="Confirm public sharing">
            The first time, LiveContext asks you to confirm <strong>Share publicly</strong>:
            &ldquo;Anyone with the link will be able to access this resource.&rdquo;
          </Step>
          <Step n={2} title="Copy the link">
            Use <strong>Copy link</strong>. The dialog also shows <strong>Total accesses</strong> and{' '}
            <strong>Shared since</strong>.
          </Step>
          <Step n={3} title="Control it later">
            From the same dialog, <strong>Disable sharing</strong> (and <strong>Enable sharing</strong>{' '}
            again) or <strong>Regenerate link</strong> to invalidate the old URL.
          </Step>
        </Steps>
        <p>
          Deleting a link from Public Access revokes it for good: &ldquo;Anyone with the link will no
          longer be able to access it.&rdquo; Each card counts its views. The notification bell also
          has a <strong>Shared</strong> tab that lists your active links.
        </p>

        <h2>What requires a pinned version</h2>
        <p>
          An endpoint can exist and still not answer. Entry points that come from outside run the
          workflow&apos;s <strong>pinned production version</strong> only.
        </p>
        <DocsTable
          caption="What each entry point needs to answer"
          rowHeaders
          head={['Entry point', 'Needs', 'Without it']}
          rows={[
            ['Webhook URL', 'A pinned version with a live production run', <>Answers <code key="c">409</code> (not active).</>],
            ['Schedule', 'A pinned version', 'The schedule is not armed and does not fire.'],
            ['Public chat URL, hosted form', 'A pinned version', 'Messages and submissions start nothing.'],
            ['Share link', 'An active link', 'A disabled, regenerated, or deleted link no longer resolves.'],
          ]}
        />
        <p>
          To pin, open the workflow&apos;s version history and choose{' '}
          <strong>Set as production</strong>. See <a href="/triggers">Triggers</a> for the full
          pinning rules.
        </p>

        <h2>Security notes</h2>
        <ul>
          <li>
            <strong>A URL is a credential.</strong> Anyone who has a public webhook, chat, form, or
            share URL can use it. Keep them out of public repositories, and regenerate the token if
            one leaks.
          </li>
          <li>
            <strong>Protect webhooks that change data</strong> with Basic, Header, or JWT
            authentication. Authentication fails closed: a caller without valid credentials gets{' '}
            <code>401</code>.
          </li>
          <li>
            <strong>A share link is scoped to one resource.</strong> It gives access to that
            conversation, chat, form, or application, and nothing else in your workspace.
          </li>
          <li>
            <strong>Public traffic is rate limited.</strong> Over the limit, callers get{' '}
            <code>429</code> with a <code>Retry-After</code> header. The limits are listed in{' '}
            <a href="/rest-api">REST API &amp; webhooks</a>.
          </li>
          <li>
            <strong>Deleting an endpoint breaks its workflow.</strong> When the endpoint is linked,
            the confirmation names the workflow: &ldquo;Deleting it will break that workflow.&rdquo;
            Disable a schedule instead of deleting it when you only want a pause.
          </li>
        </ul>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common public access problems"
          rowHeaders
          head={['Symptom', 'What to check']}
          rows={[
            ['A caller gets 404 on a webhook', 'The token was regenerated or the webhook deleted. Copy the current URL from the card.'],
            ['A caller gets 409 on a webhook', 'The workflow has no pinned version, or its production run has ended.'],
            [
              'The caller gets 401 and nothing appears in View Logs',
              'The caller does not send the credentials the webhook is configured with (refused calls are never logged). Compare its request with the cURL example.',
            ],
            ['An endpoint is missing from its tab', 'Endpoints come from triggers. Add the trigger to a workflow.'],
            ['Sharing is refused', 'The shared-link quota is full. Delete unused links or upgrade your plan.'],
            ['A schedule shows Inactive', 'It was disabled. Choose Enable. If its workflow’s run was cancelled, reactivate that run from the run panel.'],
            ['A schedule is Active but never fires', 'The workflow has no pinned version, or the run was paused or cancelled (both suspend schedules). Check it in the Agenda.'],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={2}>
          <Card icon={Webhook} title="Triggers" href="/triggers">
            How each trigger fires and what pinning controls.
          </Card>
          <Card icon={CalendarClock} title="Agenda" href="/agenda">
            Scheduled runs on a calendar: move, run early, suspend.
          </Card>
          <Card icon={Code2} title="REST API & webhooks" href="/rest-api">
            The HTTP surface, rate limits, and share-token details.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Publish an application so others can install it.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
