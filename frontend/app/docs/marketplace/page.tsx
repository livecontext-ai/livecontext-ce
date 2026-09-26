import { Workflow, Bot, Table2, LayoutPanelLeft, ShieldCheck, Share2 } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Marketplace',
  description:
    'Share and install workflows, agents, tables, interfaces, and skills: visibility and review, showcase runs, editable copies, re-installs, reviews, and self-hosted access.',
  path: '/docs/marketplace',
});

export default function MarketplacePage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Marketplace"
        lead="Share what you build and install what others share. A publication carries the whole working stack (workflow, agents, pages, tables, files), and installing it gives you a ready-to-run application in your own workspace."
      />

      <DocsProse>
        <h2>Overview</h2>
        <p>
          You can publish five kinds of things: a <strong>workflow</strong> (shown as an
          application), an <strong>agent</strong>, a <strong>table</strong>, an{' '}
          <strong>interface</strong>, or a <strong>skill</strong>. Open <strong>Marketplace</strong>{' '}
          from the sidebar. It has three tabs:
        </p>
        <DocsTable
          caption="Marketplace tabs"
          rowHeaders
          head={['Tab', 'What it lists']}
          rows={[
            [<strong key="e">Explore</strong>, 'Everything shared with the community, with search, categories, type filters, and sorting.'],
            [<strong key="s">My Shared</strong>, 'What you published, with its review status.'],
            [<strong key="p">My Purchases</strong>, 'Everything you installed, including apps you later deleted, with a Re-install button.'],
          ]}
        />
        <p>
          The marketplace is also public: each publication has its own page, and the whole catalog
          can be browsed without signing in. Installing requires an account.
        </p>

        <h2>Browse the marketplace</h2>
        <h3>Sorting and filters</h3>
        <p>
          On <strong>Explore</strong>, <strong>Sort by</strong> offers <strong>Most liked</strong>{' '}
          (the default), <strong>Best rated</strong>, <strong>Newest</strong>, and{' '}
          <strong>Most installed</strong>. You can narrow the list by category, by type, by rating
          (<strong>4 stars and up</strong>, <strong>3 stars and up</strong>), by publish date, and by
          price. <strong>Reset filters</strong> clears them.
        </p>
        <p>
          <strong>Most liked</strong> is a popularity ranking. It weighs favorites most, then
          installs, then the total weight of ratings (the average multiplied by the number of
          reviews), so one lone five-star review cannot outrank a well-reviewed app. Publications
          with no activity yet are shown newest first.
        </p>
        <h3>Studio shelf and highlights</h3>
        <p>
          The <strong>Studio shelf</strong> gathers applications that produce an image, a video, or
          a sound. The publisher opts in when sharing; the app keeps its category as well. When you
          open the marketplace from <a href="/studio">Studio</a>, a <strong>Studio only</strong> chip
          shows the shelf is active; remove it to see the whole marketplace.
        </p>
        <p>
          <strong>Highlights</strong> are publications picked by the LiveContext team for the
          Highlights row on the home screen and on the public landing pages. They are curated on
          LiveContext Cloud only.
        </p>
        <h3>Publishers and verified accounts</h3>
        <p>
          Every card and publication page shows the publisher&apos;s name, and publishers have a
          profile page with their bio and <strong>Published apps</strong>. On LiveContext Cloud, a blue
          check marks a <strong>Verified account</strong>: platform administrators, and accounts
          the LiveContext team verified. The check is not shown for an account whose profile is
          private. Self-hosted installs show no verified badges.
        </p>

        <h2>Install an application</h2>
        <Steps>
          <Step n={1} title="Open the publication">
            Click a card to see its preview, description, reviews, and what it contains. The
            preview replays a real run the publisher captured; it never runs anything.
          </Step>
          <Step n={2} title="Click Install">
            In the install dialog, you can tick <strong>Create an editable copy</strong> if you
            also want a workflow you can change (see below). It is unticked by default.
          </Step>
          <Step n={3} title="Connect your services">
            Credentials never travel with a publication. If the app uses integrations or email, a
            banner such as <strong>Connect 2 services to start</strong> lists them. Connect them now, or click{' '}
            <strong>Skip for now</strong> and do it later. Steps with no quick connect flow (HTTP
            authentication, JWT keys, webhook secrets) are listed for you to fill in the builder.
          </Step>
        </Steps>
        <h3>What you get</h3>
        <p>
          Installing creates an <strong>application</strong> in your workspace: a run-only copy of
          the whole stack with its own interfaces, tables (with any rows the publication includes),
          agents, skills, sub-workflows, and files. It no longer depends on the publisher: if they
          change or delete their workflow, your app keeps working. An application cannot be edited
          or published again.
        </p>
        <p>
          To modify it, use <strong>Create an editable copy</strong>, either in the install dialog
          or later from the app&apos;s <strong>Application settings</strong> menu (the cog). The
          copy is an ordinary workflow in your workflow list, with its own copies of every
          resource; the installed application keeps running untouched. Asking again opens the copy
          you already have instead of making another. The copy counts against your workflow limit
          and costs no credits.
        </p>
        <p>
          A published agent is tightened on install: it can only reach the tables, interfaces, and
          agents that the publication actually contains.
        </p>
        <h3>Example values and resetting the data</h3>
        <p>
          In an installed app, the <strong>Load the example values</strong> button fills the forms
          with the publisher&apos;s example inputs; nothing is sent until you submit.{' '}
          <strong>Reset the data</strong> restores the app&apos;s tables to the rows it came with,
          after a confirmation. Rows you added or edited are replaced. Your workflow and its runs are
          left untouched. Reset is not available for an app a self-hosted install took from the cloud
          marketplace.
        </p>

        <h3>Install rules and re-installing</h3>
        <DocsTable
          caption="When an install is refused"
          rowHeaders
          head={['Situation', 'What happens']}
          rows={[
            ['Your own publication', 'You cannot install something you or your workspace published.'],
            ['Already installed', 'A workspace holds one application per publication. Delete it first to install it again.'],
            ['Private publication', 'Nobody can install it fresh.'],
            ['Pending review, rejected, or unshared', 'It cannot be installed fresh.'],
          ]}
        />
        <p>
          Every install writes a receipt for your workspace, and receipts are kept. Re-installing
          from <strong>My Purchases</strong> is free and does not count against your plan&apos;s app
          limit. A re-install always takes the publication&apos;s current version, and it still
          works if the publisher later made the publication private or unshared it, unless it was
          rejected. Updates are never pushed to existing installs: to get a newer version, delete
          your app and re-install it.
        </p>

        <h2>Self-hosted only and plan-gated apps</h2>
        <p>Some publications use features that are not available everywhere.</p>
        <DocsTable
          caption="Special publications on LiveContext Cloud"
          rowHeaders
          head={['Publication uses', 'What you see on LiveContext Cloud', 'On a self-hosted install']}
          rows={[
            [
              'A local CLI agent (Claude Code, Codex, Gemini CLI, Mistral Vibe)',
              <>A <strong>CE exclusive</strong> badge. Installing shows <strong>Self-hosted install required</strong>: the app cannot run on the cloud at any plan.</>,
              'Installs normally.',
            ],
            [
              'Vector search (embeddings)',
              <>Installs from the plan that includes vector search. Below it, you see <strong>A higher plan is needed</strong> with a <strong>See plans</strong> button.</>,
              'Installs normally.',
            ],
          ]}
        />

        <h2>Share a workflow</h2>
        <Callout variant="info" title="Before you begin">
          The workflow needs at least one interface (it becomes the page people open) and a
          completed automatic run of the version you share. A workflow containing a workflow
          trigger cannot be shared.
        </Callout>
        <Steps>
          <Step n={1} title="Open the Share dialog">
            In the workflow, click the globe <strong>Share</strong> button. The{' '}
            <strong>Share Workflow</strong> dialog opens.
          </Step>
          <Step n={2} title="Information">
            Enter a <strong>Title</strong> and a <strong>Description</strong>, and pick the{' '}
            <strong>Version</strong> to share.
          </Step>
          <Step n={3} title="Showcase">
            Pick the <strong>Showcase Run</strong> whose results visitors will see, and the
            interface shown as the preview. Optionally pin one <strong>Showcase epoch</strong> as
            the default view; otherwise visitors can browse every captured epoch.
          </Step>
          <Step n={4} title="Visibility">
            Choose <strong>Private</strong> or <strong>Public</strong>. For Public, also pick a{' '}
            <strong>Category</strong> and, for a media-producing app, turn on{' '}
            <strong>Show on the Studio shelf</strong>. The recap under{' '}
            <strong>Included in shared workflow</strong> lists what will be copied.
          </Step>
          <Step n={5} title="Review the media and share">
            Click <strong>Share</strong>. If the app displays images, video, or audio, a screening
            step lists them and asks you to confirm you have the rights (see below).
          </Step>
        </Steps>
        <p>
          A Private share is live at once in your applications. A Public share waits for review;
          its card shows <strong>Pending Review</strong> in <strong>My Shared</strong> until an
          administrator approves it. To change it later, open the dialog again and click{' '}
          <strong>Update</strong>. <strong>Unshare</strong> removes it from the marketplace (you
          type &ldquo;unpublish&rdquo; to confirm); people who installed it keep their copy.
        </p>
        <p>
          Publishing freezes a snapshot of the workflow and everything it uses: interfaces, tables, agents and
          their skills, referenced files, and sub-workflows.
          Credentials are stripped, both when you publish and when someone installs. Updating
          re-takes the snapshot from the workflow&apos;s current state. On LiveContext Cloud, the
          number of publications you can create depends on your plan; updates do not count.
        </p>

        <h3>Visibility and review</h3>
        <DocsTable
          caption="Visibility modes"
          rowHeaders
          head={['Visibility', 'Who can see it', 'Review', 'Showcase run']}
          rows={[
            ['Public', 'Listed on the marketplace for everyone.', 'Yes, on every publish and every update.', 'Required'],
            ['Unlisted', 'Not listed. Anyone with the link can open and install it.', 'Yes, on every publish and every update.', 'Required'],
            ['Private', 'Not listed and not installable. Visible to you (to your organization, for an organization publication).', 'No: active at once.', 'Optional'],
          ]}
        />
        <p>
          The <strong>Share Workflow</strong> dialog offers Private and Public. Unlisted is
          available when you ask the chat assistant to publish; the assistant uses Private unless
          you say otherwise. The same review rule applies to every publication type: only Private
          skips review.
        </p>
        <p>
          A reviewer compares the frozen snapshot with the source and checks that nothing the
          workflow uses is missing, then approves or rejects. A rejected publication shows{' '}
          <strong>Rejected</strong>, with <strong>View rejection reason</strong>. You cannot
          update a publication while its previous submission is still pending, and an update
          cannot move it between your personal workspace and an organization.
        </p>

        <h3>Showcase run requirements</h3>
        <p>The showcase run must be an automatic run (not step by step) whose status is one of:</p>
        <ul>
          <li><strong>Completed</strong> or <strong>Partial success</strong>.</li>
          <li>
            Waiting for its trigger, for a workflow with a reusable trigger (webhook,
            manual, chat, schedule) that finished at least one cycle.
          </li>
        </ul>
        <p>
          Failed, cancelled, and timed-out runs are refused. The preview is a frozen copy kept on
          your side: visitors never touch your live workflow, and the run is never handed to people
          who install the app. Files in the preview are served through links signed under your
          account and valid for up to 4 hours; a file that cannot be served shows as a broken image.
        </p>

        <h3>Media screening</h3>
        <p>
          Before an app with an interface is shared, screening lists every image, video, audio
          file, download link, and CSS background it displays, and reminds you to check you have
          the rights to each. For each item you can keep it, <strong>Replace with AI</strong> (a
          generated image, billed in credits), or <strong>Upload</strong> your own. Then choose{' '}
          <strong>Publish with attestation</strong> or <strong>Publish anyway</strong>. Screening
          never blocks publishing, and your decision is logged. You remain responsible for the
          content you publish.
        </p>

        <h2>Publish an agent, table, interface, or skill</h2>
        <p>
          These have their own <strong>Publish</strong> action, with a title, a description, a
          category, and, for an agent, table, or skill, a <strong>Landing page</strong>: the
          interface visitors see on the listing. The visibility and review rules above apply. An
          agent that has &ldquo;All&rdquo; access to a resource type cannot be published: switch it
          to an explicit selection first, so the publication cannot carry every workflow, table,
          and interface in your account. A publication that exceeds the size limit (or a table with
          too many rows) is refused with the figures.
        </p>

        <h2>Reviews, favorites, and reports</h2>
        <p>
          A review has an optional rating from 1 to 5 and an optional comment of up to 2,000
          characters. You leave one review per publication (submitting again updates it), and you
          cannot review your own. Replies go one level deep and cannot be empty. The average
          rating counts only reviews that carry a rating.
        </p>
        <p>
          <strong>Favorites</strong> are personal bookmarks, and they also feed the{' '}
          <strong>Most liked</strong> ranking. To flag content that infringes your rights or the
          terms, use the <strong>Report</strong> tab on the publication.
        </p>

        <h2>Your public profile</h2>
        <p>
          Your name always appears on what you publish. Your profile page is set in{' '}
          <strong>Settings</strong> &gt; <strong>Overview</strong> &gt;{' '}
          <strong>Public profile</strong>: a <strong>Bio</strong>, a <strong>Handle</strong> (your
          profile lives at @handle, changeable once every 7 days), and a{' '}
          <strong>Profile visibility</strong>:
        </p>
        <ul>
          <li><strong>Public</strong>: listed by search engines.</li>
          <li><strong>Unlisted</strong>: reachable by anyone with the link.</li>
          <li><strong>Private</strong>: no profile page.</li>
        </ul>

        <h2>Paid publications</h2>
        <p>
          Charging credits for an install is not available yet. Publications are free: the dialog
          says <strong>Paid templates are coming soon</strong>, and a non-zero price is refused.
          Once it is available, a Public publication will still have to be free; a price will need
          Private or Unlisted visibility.
        </p>

        <h2>The marketplace on a self-hosted install</h2>
        <p>
          A self-hosted <a href="/self-host">Community Edition</a> install shows the community
          marketplace once it is linked to a LiveContext Cloud account. Until then, the page shows{' '}
          <strong>Connect your cloud account</strong> with a <strong>Connect to cloud</strong>{' '}
          button, and no publications. After linking, you browse and install from the same
          catalog as cloud users, and apps marked <strong>CE exclusive</strong> install normally.
        </p>

        <h2>Private share links</h2>
        <p>
          Separate from the marketplace, you can share a chat, a form, a conversation, or an
          application through a private link, with an optional expiry date and password. The number
          of share links you can hold depends on your plan; self-hosted installs have no limit. See{' '}
          <a href="/public-access">Public access &amp; sharing</a>.
        </p>
        <DocsTable
          caption="Share link limits by plan"
          rowHeaders
          head={['Plan', 'Maximum share links']}
          rows={[
            ['Free', '5'],
            ['Starter', '20'],
            ['Pro', '50'],
            ['Team', '100'],
            ['Enterprise', '200'],
            ['Self-hosted (CE)', 'Unlimited'],
          ]}
        />

        <h2>Troubleshooting</h2>
        <h3>The Next button stays disabled in the Share dialog</h3>
        <p>
          The chosen version needs a completed automatic run and at least one interface, and it
          must not contain a <strong>Workflows</strong> trigger. The dialog shows a message when the version has no
          interface or contains a <strong>Workflows</strong> trigger.
          Step-by-step runs appear greyed out in the run list and cannot be used as a showcase.
        </p>
        <h3>My publication is not on the marketplace</h3>
        <p>
          Public and Unlisted publications wait for review after every publish and every update.
          Check <strong>My Shared</strong> for <strong>Pending Review</strong> or{' '}
          <strong>Rejected</strong>.
        </p>
        <h3>I cannot install an app I installed before</h3>
        <p>
          Your workspace still has it. Open it from your applications, or delete it and use{' '}
          <strong>Re-install</strong> in <strong>My Purchases</strong> to get the latest version.
        </p>
        <h3>An installed app fails on its first run</h3>
        <p>
          Its integrations are not connected yet. Connect the services it asks for; see{' '}
          <a href="/integrations">Integrations</a>.
        </p>

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={LayoutPanelLeft} title="Interfaces & apps" href="/interfaces">
            Build the pages your application shows.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Build and version the workflow you will share.
          </Card>
          <Card icon={Bot} title="Agents" href="/agents">
            Publish an agent with just the tools it needs.
          </Card>
          <Card icon={Table2} title="Tables & data" href="/tables">
            Publish a table on its own.
          </Card>
          <Card icon={Share2} title="Public access & sharing" href="/public-access">
            Share links, public forms, and endpoints.
          </Card>
          <Card icon={ShieldCheck} title="Self-hosting" href="/self-host">
            Run your own install and link it to the cloud.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
