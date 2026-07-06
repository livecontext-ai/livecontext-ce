import { Store, Workflow, Bot, Table2, LayoutPanelLeft, ShieldCheck } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Marketplace',
  description:
    'Publish and acquire workflows, agents, tables, interfaces, and skills on the LiveContext marketplace: visibility modes, showcase runs, moderation, credential stripping, receipts and free re-acquisition, ratings and reviews, private share links, and the remote marketplace for self-hosted CE.',
  path: '/docs/marketplace',
});

export default function MarketplacePage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Marketplace"
        lead="Publish what you build, fork what others share. A publication isn't a screenshot, it's the whole working stack. Acquiring one gives you your own independent copy: the workflow, its agents, its pages, its tables, even its files."
      />

      <DocsProse>
        <h2>Five publication types</h2>
        <p>
          You can publish a <strong>workflow</strong>, an <strong>agent</strong>, a <strong>table</strong>, an{' '}
          <strong>interface</strong>, or a <strong>skill</strong>. A workflow publication carries a{' '}
          <code>workflowId</code>, an agent publication carries an <code>agentConfigId</code>, and
          table/interface/skill publications carry a generic resource id. Agents and standalone
          resources have their own publish/unpublish actions, separate from the workflow publish
          flow.
        </p>
        <p>
          Publishing a workflow captures a self-contained snapshot of the plan{' '}
          <strong>and everything it uses</strong>: interface HTML/CSS/JS templates and variables,
          datasource schema and config, agent config and skills, any DataInput files it references,
          and referenced sub-workflows (recursively). Nothing about the acquirer&apos;s experience
          depends on your live workflow still existing or being reachable.
        </p>
        <Callout variant="warn">
          A workflow that has already been turned into an <strong>application</strong> (acquired
          from another publication) cannot itself be published. Only original <code>WORKFLOW</code>
          -type workflows can be published; publish the source workflow instead, or build a new one.
        </Callout>
        <p>
          Re-publishing updates the existing publication in place: it re-snapshots from the
          workflow&apos;s current state, so the published snapshot is always fresh. Re-publishing
          cannot change who owns the publication (a personal publication can&apos;t become an
          org publication or vice versa), and it&apos;s blocked outright while a previous submission
          is still pending review.
        </p>

        <h2>Visibility &amp; the showcase requirement</h2>
        <p>Every publication has one of three visibility modes:</p>
        <DocsTable
          head={['Visibility', 'Who can see and acquire it', 'Needs a showcase run?']}
          rows={[
            ['Public', 'Listed on the marketplace for everyone to browse and acquire.', 'Yes'],
            ['Unlisted', 'Not listed, but reachable and acquirable by anyone with the direct link.', 'Yes'],
            [
              'Private',
              'Not listed and not acquirable by anyone but you (org members, for an org-owned publication).',
              'No',
            ],
          ]}
        />
        <p>
          Public is the default for a workflow or app publication (standalone skill and interface
          publications default to Private). A <strong>showcase run</strong> is a frozen preview captured at
          publish time, so visitors can watch the real thing without ever touching your live
          workflow or the orchestrator. Public and Unlisted publications require one; Private does
          not, since there&apos;s no marketplace listing to preview.
        </p>
        <Callout variant="info">
          The showcase run must be a <strong>completed automatic run</strong>: a paused,
          step-by-step run can&apos;t be used, and the run&apos;s final status has to be completed
          or partially successful, a failed, cancelled, or timed-out run is rejected. If the
          publication&apos;s display mode is Interface or Application, it additionally needs a
          showcase interface selected, since there&apos;s nothing to preview otherwise.
        </Callout>

        <h2>Review &amp; moderation</h2>
        <p>
          A publication moves through four statuses: <code>ACTIVE</code>, <code>INACTIVE</code>,{' '}
          <code>PENDING_REVIEW</code>, and <code>REJECTED</code>. For a workflow or app publication,
          what happens at publish time depends on visibility:
        </p>
        <DocsTable
          head={['Visibility', 'Status right after publish (or update)']}
          rows={[
            ['Private', 'ACTIVE immediately, no review needed.'],
            ['Public / Unlisted', 'PENDING_REVIEW, on every publish AND every subsequent update.'],
          ]}
        />
        <Callout variant="info">
          Standalone <a href="/skills">skill</a> and <a href="/interfaces">interface</a> publications
          review only Public listings: their Private and Unlisted publications activate immediately,
          and they default to Private.
        </Callout>
        <p>
          An admin reviewer either approves a pending submission (status moves to{' '}
          <code>ACTIVE</code>, any prior rejection reason is cleared) or rejects it (status moves to{' '}
          <code>REJECTED</code> with a stored reason), and only pending submissions can be acted on.
          To help the reviewer judge what actually changed, moderation shows a side-by-side
          comparison of the frozen snapshot against the live source, plus a completeness manifest
          that flags any agent, datasource, interface, or sub-workflow the plan declares but that
          didn&apos;t make it into the snapshot. Admins work from a moderation queue with pending,
          active, and rejected counts.
        </p>

        <h2>Acquiring (forking): an independent clone</h2>
        <p>
          Acquiring a publication clones the entire stack into your workspace with fresh ids and
          every internal reference rewritten to point at <em>your</em> new resources: sub-workflows,
          interfaces, datasources, agents and their skills, and any DataInput files. The result is a
          normal item in your workspace that you can run, edit, and even re-publish as your own.
          Every cloned resource is tagged back to the source publication for traceability.
        </p>
        <DocsTable
          head={['Guard', 'What happens']}
          rows={[
            ['Your own publication', 'You cannot acquire something you published yourself.'],
            [
              'Already acquired',
              'While you still hold an active clone from a publication, acquiring it again is blocked; delete your clone first, or just keep using it.',
            ],
            ['Private, not yours', 'A non-owner cannot acquire a Private publication at all.'],
            ['Not active', 'Only ACTIVE publications (approved, or Private) can be acquired.'],
          ]}
        />
        <Callout variant="warn">
          Credentials are <strong>stripped</strong> at both publish and acquire time: HTTP
          authentication (bearer tokens, API keys, passwords) and any linked email-sending
          credential are removed from the cloned plan. Your secrets never travel with a
          publication or a clone, connect your own before integration and email steps will run.
        </Callout>
        <p>
          A published agent&apos;s tool access is also tightened on acquisition: its mode is forced
          to a custom, explicit list, and its tables/interfaces/agents are intersected with what the
          plan actually contains, so a forked agent can&apos;t reach beyond what you shipped in it.
          The acquired root workflow is created as an application, exactly one application per
          (organization, publication) is allowed, while any cloned sub-workflows become ordinary
          workflows in your workspace.
        </p>

        <h2>Receipts &amp; free re-acquisition</h2>
        <p>
          Every acquisition writes a receipt (tenant, publication, credits paid, timestamp,
          organization scope) and receipts are never deleted. Publishing an update{' '}
          <strong>does not</strong> push to people who already acquired it, every clone is
          independent. To pick up a newer version, acquire again.
        </p>
        <Callout variant="info">
          Because a receipt was kept the first time, re-acquiring later, even after you deleted
          your clone, is <strong>free</strong>: you&apos;re not re-billed and the acquisition doesn&apos;t
          count against your plan limit. A re-acquisition always clones from the publication&apos;s{' '}
          <strong>current</strong> snapshot, so you get the latest version, not the one you first
          acquired. Your purchase history and currently acquired publications are both browsable.
        </Callout>

        <h2>The showcase run preview is frozen</h2>
        <p>
          Publishing captures a frozen showcase snapshot: the run state, its aggregated steps, epoch
          signals and timestamps, and pre-rendered templates and items for every interface involved.
          The marketplace preview reads entirely from that snapshot, it never calls the orchestrator
          or touches your live workflow. The showcase run itself is cloned on the publisher&apos;s
          side (including independent file copies) and is never transferred to acquirers, and you can
          pin a single canonical epoch as the one shown by default; without a pin the preview falls
          back to a multi-epoch view.
        </p>
        <p>
          For anonymous visitors, any file reference inside the snapshot is rewritten to a
          short-lived, signed URL minted under your (the publisher&apos;s) account, never the
          visitor&apos;s, valid for up to 4 hours. Only the data actually rendered in the preview is
          rewritten this way, not internal trigger data or run-state internals, and a failed rewrite
          just leaves a broken image rather than breaking the whole preview.
        </p>

        <h2>Display modes: Workflow vs Interface / Application</h2>
        <p>
          Display mode controls how a publication <strong>presents</strong> on the marketplace, it
          doesn&apos;t change the publish or acquire mechanics underneath.
        </p>
        <DocsTable
          head={['Display mode', 'Presented as']}
          rows={[
            ['Workflow (default)', 'A blueprint/template: visitors preview the automation itself.'],
            [
              'Interface',
              'An interactive UI experience backed by the workflow; requires a showcase interface.',
            ],
            [
              'Application',
              'Same as Interface, an interactive app the visitor can use; requires a showcase interface.',
            ],
            ['Agent', 'An agent publication.'],
            ['Table', 'A table publication.'],
            ['Skill', 'A skill publication.'],
            [
              'Landing',
              'Not a real publication type, only a curated highlight bucket used on the public landing page.',
            ],
          ]}
        />

        <h2>Version history</h2>
        <p>
          Once a publication has been acquired at least once, its snapshot history is retained: each
          version number, the full plan snapshot at that point, an optional label, and when it was
          created. A never-acquired publication keeps no history, since there&apos;s nothing yet to
          look back at. You can browse a lightweight version list, or pull the full snapshot for any
          specific version.
        </p>

        <h2>Ratings, reviews, replies &amp; favorites</h2>
        <p>
          A review carries an optional rating from 1 to 5 and/or an optional comment (up to 2000
          characters), independently: you can leave just a rating, just a comment, or both, and
          update either one on its own. There&apos;s one top-level review per person per publication
          (submitting again updates your existing review), and you can&apos;t review your own
          publication. Clearing your comment while keeping your rating (or the reverse) is
          supported; if nothing&apos;s left, the review is removed entirely.
        </p>
        <p>
          Replies live in the same review thread: a reply has no rating, can&apos;t be empty, and
          can&apos;t itself be replied to, one level of nesting only. The publication&apos;s average
          rating and review count are recomputed from top-level reviews that carry a rating; the
          separate comment count only considers reviews with actual text.
        </p>
        <p>
          <strong>Favorites</strong> are personal: one entry per user per publication, driving your
          own favorites view, distinct from any admin-curated highlights on the marketplace home.
        </p>

        <h2>Pre-publish image screening</h2>
        <p>
          Before a workflow with an interface publishes, screening scans its HTML/CSS/JS templates
          and the resolved showcase data for every referenced media resource (images, video, audio,
          download links, CSS backgrounds), deduplicated by URL.
        </p>
        <Callout variant="info">
          Screening only ever produces a <strong>warning</strong>, never a hard block: you can
          always proceed, and the decision (whether you attested you have rights to the media, or
          just acknowledged the warning) is logged. Auto-blocking publishing would shift copyright
          liability from you to the platform, so the choice, and the responsibility, stays with the
          publisher.
        </Callout>

        <h2>Private share links</h2>
        <p>
          Separate from marketplace publications, you can mint a private share link for a running
          chat, form, conversation, or application, each with its own title and description, an
          active/inactive flag, and an access counter. Two optional controls are supported on a
          link: an expiry timestamp (an expired link resolves to not-found) and password protection
          (the public resolver only ever sees a &ldquo;this link needs a password&rdquo; flag, never
          the password itself). Share links resolve publicly without any login.
        </p>
        <p>
          The number of share links you can hold is plan-limited (globally, across all resource
          types), with a self-hosted Community Edition able to disable the limit entirely.
        </p>
        <DocsTable
          head={['Plan', 'Max shared links']}
          rows={[
            ['Free', '5'],
            ['Starter', '20'],
            ['Pro', '50'],
            ['Team / Pay-as-you-go', '100'],
            ['Enterprise', '200'],
            ['Self-hosted (CE)', 'Unlimited'],
          ]}
        />

        <h2>Paid publications (gated off by default)</h2>
        <p>
          Charging credits per acquisition exists in the data model, but it&apos;s{' '}
          <strong>disabled platform-wide by default</strong>. While disabled, publishing or updating
          a publication with a non-zero price is rejected outright, existing paid publications from
          before the gate keep working, but no new ones can be created until the feature ships.
        </p>
        <Callout variant="warn">
          Independently of that gate, a <strong>Public</strong> publication must always be free.
          Charging for a publication (once the feature is enabled) requires Private or Unlisted
          visibility.
        </Callout>

        <h2>Remote cloud marketplace for CE</h2>
        <p>
          A self-hosted <a href="/self-host">Community Edition</a> instance can link to a cloud
          account and, from that link, browse and acquire from the shared cloud marketplace,
          exactly as a cloud user would. Linking uses OAuth against the cloud identity provider, and
          a heartbeat keeps the cloud side aware of which CE version is connected. CE can also run
          its own local marketplace for internal automations independently of any cloud link.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={LayoutPanelLeft} title="Interfaces & apps" href="/interfaces">
            Package a workflow as a shareable, interactive app.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Build the thing you&apos;ll publish.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Publish an agent, scoped to just the tools it needs.
          </Card>
          <Card icon={Table2} title="Tables & data" href="/tables">
            Publish a table alongside the workflows that use it.
          </Card>
          <Card icon={ShieldCheck} title="Self-hosting" href="/self-host">
            Run your own instance, optionally linked to the cloud marketplace.
          </Card>
          <Card icon={Store} title="Getting started" href="/getting-started">
            New to LiveContext? Start here.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
