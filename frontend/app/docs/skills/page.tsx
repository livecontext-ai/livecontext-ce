import { Bot, Store, Workflow, FolderTree } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Skills',
  description:
    'Skills in LiveContext: reusable instruction packages for agents, nestable folders, additive assignment up to 10 per agent, default-active with per-user override, global vs personal/org skills, marketplace publishing, and cloud-to-CE signed skill bundles.',
  path: '/docs/skills',
});

export default function SkillsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Skills"
        lead="A skill is a reusable, named instruction package for agents: a short description that's always injected into the agent's system prompt, plus full instructions that load only when the agent actually activates it. Create them in the Skills tab, organize them in nestable folders, and assign the same skill to as many agents as you like."
      />

      <DocsProse>
        <h2>What a skill is</h2>
        <p>
          A skill has a <code>name</code>, a <code>description</code>, and full{' '}
          <code>instructions</code> written in markdown, all required. It's a split-load design
          that keeps agent prompts small:
        </p>
        <DocsTable
          head={['Field', 'When it loads', 'Purpose']}
          rows={[
            [
              'description',
              'Always, injected into the agent’s system prompt.',
              'A short summary (up to 300 characters in the UI) so the agent knows the skill exists and when to reach for it.',
            ],
            [
              'instructions',
              'Only on demand, when the agent activates the skill.',
              'The full markdown playbook: no length cap in the editor.',
            ],
          ]}
        />
        <p>
          Activation is the agent calling <code>skill(action=&apos;get&apos;, skill_id=...)</code>,
          whose result carries the full <code>instructions</code>. You may see the label
          &quot;discover_skill&quot; in help text or tooltips describing this activation step,
          but it's descriptive only: there's no separately callable{' '}
          <code>discover_skill</code> tool, just <code>skill(action=&apos;get&apos;)</code>.
        </p>
        <p>
          A skill can also carry an <code>icon</code> (a free-form string, up to 100 characters),
          but it's set only through the API or the <code>skill</code> MCP tool, since the
          create/edit modal doesn't render an icon field.
        </p>

        <h2>Creating &amp; editing (Skills tab)</h2>
        <p>
          Skills live in the <strong>Skills</strong> tab, alongside Agents and Metrics. The
          create/edit modal renders three fields, all required to save: <strong>Name</strong>,{' '}
          <strong>Description</strong> (with a tooltip reminding you it's injected into the
          system prompt), and <strong>Instructions</strong> (markdown).
        </p>
        <p>An agent can drive the same lifecycle through the <code>skill</code> MCP tool:</p>
        <DocsTable
          head={['Action group', 'Actions']}
          rows={[
            ['CRUD', 'create, get, list, update, delete'],
            ['Assignment', 'assign'],
            ['Folders', 'create_folder, list_folders, rename_folder, move_folder, delete_folder'],
            ['Marketplace', 'publish, unpublish'],
            ['Discovery', 'help'],
          ]}
        />
        <p>
          <code>list</code> defaults to 25 results per page (<code>limit</code>), starting at
          offset 0.
        </p>
        <Callout variant="info">
          A built-in &quot;default&quot; skill (one with a non-null <code>default_key</code>)
          can't be deleted, only reset to its original content. A regular skill you created can be
          deleted outright.
        </Callout>

        <h2>Nestable folders</h2>
        <p>
          Skills are organized into folders, and folders can nest inside other folders with no
          maximum depth. A skill moves into a folder at creation (<code>folder_id</code>) or
          later via <code>update</code> (pass <code>&apos;root&apos;</code> to move it back out);
          the UI also supports drag-and-drop, for both skills and whole folders.
        </p>
        <p>
          Deleting a folder never deletes the skills inside it: they move to the root instead,
          and any subfolders cascade-delete with it. Moving a folder rejects two cases: moving it
          into itself, or moving it into one of its own descendants (which would create a cycle).
        </p>
        <Callout variant="warn">
          Global skills live outside any tenant's folder hierarchy and can't be moved into a
          tenant folder. A folder itself can also be marked global by an admin, and a global
          folder can hold a mix of personal and global skills, no cascade of globalness implied.
        </Callout>

        <h2>Assigning skills to agents</h2>
        <p>
          Assignment is <strong>additive</strong>: <code>skill(action=&apos;assign&apos;)</code>{' '}
          adds skills to an agent without touching its existing set, silently skipping any that
          are already assigned. Skills can also be attached at agent-creation time via{' '}
          <code>skill_ids</code>.
        </p>
        <Callout variant="warn">
          There's a hard cap of <strong>10 skills per agent</strong>, configurable via{' '}
          <code>skill.max-per-agent</code> (default 10). Both the additive assign path and the
          replace path (<code>PUT /api/agents/{'{agentId}'}/skills</code>, which overwrites the
          whole set instead of adding to it) enforce the cap.
        </Callout>
        <p>
          A skill can only be assigned if it's visible in the caller's workspace, or if it's an
          admin-managed global skill; anything else is rejected.
        </p>

        <h2>Default-active in new chats, with a per-user override</h2>
        <p>
          Each skill has an <code>is_default_active</code> flag. When true, the skill is
          automatically included in every new general-chat conversation for everyone who can see
          it. The owner can flip this freely on a personal skill; on a global skill, the admin
          gate applies.
        </p>
        <p>
          On top of the default, each person can set a <strong>per-user override</strong>: turn a
          shared or global default-active skill on or off just for themselves, without changing
          it for teammates. The resolution rule at chat time is simple: your override wins if you
          have one, otherwise the skill's own default applies. Clearing your override falls back
          to that default.
        </p>

        <h2>Global vs personal/org skills</h2>
        <p>
          A skill's visibility is one of three scopes:
        </p>
        <DocsTable
          head={['Scope', 'Who sees it', 'Who can edit']}
          rows={[
            ['Personal', 'Just you, in your own tenant.', 'You.'],
            [
              'Org workspace',
              'Everyone in that organization workspace (skill carries an organization_id).',
              'Members with write access; VIEWER role is read-only.',
            ],
            [
              'Global (is_global=true)',
              'Every tenant, everywhere.',
              'Admins only: creating, editing, deleting, or toggling globalness on a global skill is admin-gated.',
            ],
          ]}
        />
        <p>
          A non-admin who opens a global skill gets a read-only editor with a banner explaining
          why. Org-level access control also supports per-member deny restrictions: a restricted
          skill simply reads back as not-found for that member.
        </p>

        <h2>Publishing a skill to the marketplace</h2>
        <p>
          Publishing registers the skill as a marketplace listing. Three things are required:{' '}
          <code>skill_id</code>, a <code>title</code>, and an <code>interface_id</code>, the
          landing page shown to people before they acquire it.
        </p>
        <DocsTable
          head={['Visibility', 'Who sees it', 'Goes live']}
          rows={[
            ['Private (default)', 'Only you.', 'Immediately.'],
            ['Unlisted', 'Anyone with the direct link.', 'Immediately.'],
            ['Public', 'Listed on the marketplace.', 'After platform review (starts PENDING_REVIEW).'],
          ]}
        />
        <p>
          An optional <code>credits_per_use</code> (default 0) sets a per-use credit price; the
          publish call simply forwards that number to the marketplace side, it doesn't itself
          charge credits.
        </p>
        <p>
          Acquiring a published skill clones a fully self-contained copy, name, description,
          icon, and instructions, into the acquirer's own tenant, tagged with the source
          publication. Skills carry no file assets or other referenced resources, so there's
          nothing else to bring along. Unpublishing marks the listing inactive: existing
          acquirers keep the copy they already cloned, only new acquisitions are blocked.
        </p>
        <Callout variant="info">
          The Skills tab shows each skill's publish state, published, pending review, or rejected
          with a reason, alongside Publish and Unshare actions.
        </Callout>

        <h2>Cloud-to-CE signed skill bundles</h2>
        <p>
          Admin-managed global skills are distributed from the cloud to self-hosted Community
          Edition installs as a signed, versioned bundle (Ed25519-signed), so a fresh CE install
          gets the cloud's curated set of globals instead of starting empty.
        </p>
        <Steps>
          <Step n={1} title="Bundle applies as global, read-only rows">
            Applied rows are marked <code>is_global=true</code> and carry the cloud's{' '}
            <code>is_default_active</code> value exactly, so they auto-activate in new chats just
            like the cloud original. Each row is keyed by <code>source_bundle_key</code>, the
            cloud skill's UUID.
          </Step>
          <Step n={2} title="Local edits are blocked, not silently overwritten">
            Any skill with a non-null <code>source_bundle_key</code> can't be edited or deleted
            locally, even by an admin, because the cloud owns that content and a future re-sync
            would clobber a local change anyway. A CE user can still hide it for themselves with
            the per-user override.
          </Step>
          <Step n={3} title="Re-sync is safe by design">
            Re-syncing upserts by key (so a stable row id preserves any per-user overrides on it),
            soft-removes rows dropped from the latest bundle (<code>is_global=false</code>,{' '}
            <code>is_active=false</code>, never a hard delete), refuses to apply an empty payload
            so the whole global set is never wiped by accident, and is a no-op if the install is
            already on the latest version.
          </Step>
        </Steps>
        <Callout variant="warn">
          This distribution is a benefit of cloud-linking: the download endpoints require an
          active cloud link, and an unlinked CE install simply skips the sync (recorded as{' '}
          <code>NOT_LINKED</code>) rather than failing. Only the signing-key endpoint is public.
          Sync runs on a schedule and once at startup.
        </Callout>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents">
            Attach skills via skill_ids and the skill tool module.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Publishing, visibility, and how forking works for any resource.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            Where agent nodes carrying skills sit in a graph.
          </Card>
          <Card icon={FolderTree} title="Self-hosting" href="/self-host">
            How Community Edition installs link to cloud for bundles.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
