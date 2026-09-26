import { Bot, Store, Server } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Skills',
  description:
    'Skills are reusable instruction packages for agents: folders, assignment (up to 10 per agent), default-active skills, scopes, marketplace publishing, and cloud skill bundles.',
  path: '/docs/skills',
});

export default function SkillsPage() {
  return (
    <>
      <DocsHero
        eyebrow="AI"
        title="Skills"
        lead="A skill is a reusable, named set of instructions for agents. Its short description is always in the agent's system prompt, and its full instructions load only when the agent needs them. This page covers creating, organizing, assigning, sharing, and publishing skills."
      />

      <DocsProse>
        <h2>What a skill is</h2>
        <p>
          A skill has a <strong>Name</strong>, a <strong>Description</strong>, and{' '}
          <strong>Instructions</strong> written in markdown. All three are required. The split keeps
          agent prompts small:
        </p>
        <DocsTable
          caption="How the parts of a skill are loaded"
          rowHeaders
          head={['Field', 'When it loads', 'Purpose']}
          rows={[
            [
              'Description',
              "Always, in the agent's system prompt.",
              'A short summary (up to 300 characters in the editor) so the agent knows the skill exists and when to use it.',
            ],
            [
              'Instructions',
              'On demand, when the agent activates the skill.',
              'The full markdown playbook, with no length limit.',
            ],
          ]}
        />
        <p>
          The agent sees each available skill as its name and description, with a hint to load it.
          Activating a skill means the agent calls <code>skill(action=&apos;get&apos;, skill_id=...)</code>,
          whose result carries the full instructions. Help text sometimes calls this step
          &quot;discover_skill&quot;; that is only a label, not a separate tool. Names are limited to
          255 characters.
        </p>
        <Callout variant="tip" title="Skills and memory">
          Skills hold procedures (how to do something). Facts an agent should remember (who, what,
          preferences) belong in long-term memory instead, which you manage in the{' '}
          <strong>Memory</strong> tab of the Agents page. See <a href="/agents">Agents</a>.
        </Callout>

        <h2>Create and edit skills</h2>
        <p>
          Skills live in the <strong>Skills</strong> tab of the Agents page, next to the{' '}
          <strong>Agents</strong>, <strong>Memory</strong>, <strong>Fleet</strong>,{' '}
          <strong>Metrics</strong>, and <strong>Settings</strong> tabs.
        </p>
        <Steps>
          <Step n={1} title="Open the editor">
            In the <strong>Skills</strong> tab, select <strong>Create Skill</strong>.
          </Step>
          <Step n={2} title="Fill in the three fields">
            Enter a <strong>Name</strong>, a <strong>Description</strong>, and the{' '}
            <strong>Instructions</strong>.
          </Step>
          <Step n={3} title="Save">
            Select <strong>Save</strong>. The skill appears in the tree, where you can move it into a
            folder.
          </Step>
        </Steps>
        <p>
          Administrators also see a <strong>Make available to everyone (global)</strong> checkbox in
          the editor (see <a href="#scopes">Scopes</a>).
        </p>

        <h3>Built-in default skills</h3>
        <p>
          Every account starts with a set of built-in skills, marked <strong>Default</strong> in the
          tree. They are added to your list automatically and are active in new chats. You can edit
          them, and <strong>Reset to default</strong> in the editor restores the original content. A
          default skill cannot be deleted.
        </p>

        <h3>Manage skills from an agent</h3>
        <p>An agent can manage skills through the <code>skill</code> tool:</p>
        <DocsTable
          caption="Actions of the skill tool"
          rowHeaders
          head={['Action group', 'Actions']}
          rows={[
            ['Skills', 'create, get, list, update, delete'],
            ['Assignment', 'assign'],
            ['Folders', 'create_folder, list_folders, rename_folder, move_folder, delete_folder'],
            ['Marketplace', 'publish, unpublish'],
            ['Help', 'help'],
          ]}
        />
        <p>
          <code>list</code> returns 25 results per page by default and accepts a{' '}
          <code>query</code> that filters on name and description. Resetting a default skill and
          turning <strong>Default-active in new chats</strong> on or off are done in the app, not
          through the tool.
        </p>

        <h2>Organize skills in folders</h2>
        <p>
          Folders can nest inside other folders with no depth limit. Drag and drop skills and folders
          in the tree. A folder cannot be moved into itself or into
          one of its own subfolders.
        </p>
        <Callout variant="warn" title="Deleting a folder">
          Before you delete a folder, move the skills and subfolders you want to keep out of it. The
          confirmation dialog says skills move to the root, but today deleting a folder removes only
          the folder: the skills and subfolders that were inside it disappear from the Skills tree.
          The skills are not deleted, but you can no longer reach them from the tree, and agents may
          stop seeing them in their list of skills.
        </Callout>
        <p>
          Global skills cannot be moved into a personal or team folder. An administrator can also mark
          a folder as global; this does not change the skills inside it.
        </p>

        <h2>Assign skills to agents</h2>
        <p>
          Select skills in the agent editor, or let an agent assign them. The{' '}
          <code>skill(action=&apos;assign&apos;)</code> action is <strong>additive</strong>: it adds
          skills to an agent and skips the ones already assigned. By contrast, passing{' '}
          <code>skill_ids</code> when an agent is created or updated <strong>replaces</strong> the
          whole set.
        </p>
        <Callout variant="warn">
          An agent can have at most <strong>10 skills</strong>. Both ways of assigning enforce the
          limit.
        </Callout>
        <p>
          You can assign a skill only if it is visible in your workspace, or if it is a global skill.
        </p>

        <h2>Default-active skills in new chats</h2>
        <p>
          A skill marked <strong>Default-active in new chats</strong> (in the skill&apos;s menu in the
          tree) is included automatically in every new general chat for everyone who can see it. You
          can change this on your own skills; on a global skill only an administrator can.
        </p>
        <p>
          Each person can also switch a skill on or off just for themselves with the toggle on its
          row, without changing it for anyone else. Your own choice wins; if you have not made one,
          the skill&apos;s default applies.
        </p>

        <h2 id="scopes">Scopes</h2>
        <DocsTable
          caption="Skill scopes"
          rowHeaders
          head={['Scope', 'Who sees it', 'Who can edit']}
          rows={[
            ['Personal', 'You.', 'You.'],
            ['Team workspace', 'Everyone in the organization workspace.', 'Members with write access. Viewers are read-only.'],
            ['Global', 'Every account on the installation.', 'Administrators only.'],
          ]}
        />
        <p>
          A non-administrator who opens a global skill sees a read-only editor with a notice
          explaining why. In a team workspace, a skill can also be restricted for a specific
          member, in which case it does not appear in that member&apos;s skill list.
        </p>

        <h2>Publish a skill to the marketplace</h2>
        <p>
          Publishing creates a marketplace listing. Every listing needs a title and a landing page (an
          interface people see before they add the skill). See{' '}
          <a href="/marketplace">Marketplace</a> for listings and reviews.
        </p>
        <DocsTable
          caption="Skill listing visibility"
          rowHeaders
          head={['Visibility', 'Who sees it', 'Goes live']}
          rows={[
            ['Private', 'Only you.', 'Immediately.'],
            ['Unlisted', 'Anyone with the direct link.', 'After platform review.'],
            ['Public', 'Everyone, in the marketplace.', 'After platform review.'],
          ]}
        />
        <ul>
          <li>
            Publishing from the <strong>Skills</strong> tab always creates a <strong>Public</strong>{' '}
            listing, which waits for review.
          </li>
          <li>
            An agent publishing with the <code>skill</code> tool can choose the visibility; if it
            passes none, the listing is <strong>Private</strong>.
          </li>
          <li>
            A publish request sent through the REST API without a visibility is{' '}
            <strong>Public</strong>.
          </li>
        </ul>
        <Callout title="Free listings only">
          New listings must be free. A publish with a per-use credit price above 0 is refused unless
          paid templates are enabled on the installation, which they are not by default.
        </Callout>
        <p>
          Adding a published skill copies its name, description, icon, and instructions into your own
          account. Unpublishing stops new people from adding it; existing copies are kept. The Skills
          tab shows each skill&apos;s publication state (published, <strong>Pending Review</strong>, or
          rejected with a reason), with <strong>Share</strong> and <strong>Unshare</strong> actions.
        </p>

        <h2>Global skills on the Community Edition</h2>
        <Callout title="Requires a cloud link">
          A self-hosted install receives the cloud&apos;s global skills only while it is linked to the
          cloud. An install that was never linked has no cloud global skills: nothing is built into a
          release.
        </Callout>
        <p>
          When the install is linked, it downloads a signed bundle of the cloud&apos;s global skills at
          startup and then about every 15 minutes. The signature is verified before anything is
          applied.
        </p>
        <Steps>
          <Step n={1} title="Skills arrive as global, read-only skills">
            Each bundled skill becomes a global skill and keeps the cloud&apos;s default-active setting,
            so it joins new chats the same way. Anyone can switch it off for themselves.
          </Step>
          <Step n={2} title="The cloud owns their content">
            Bundled skills cannot be edited or deleted locally, even by an administrator.
          </Step>
          <Step n={3} title="Updates are safe">
            A new bundle updates skills in place. A skill removed from the bundle is deactivated, not
            deleted. An empty bundle is refused, and a bundle you already have changes nothing.
          </Step>
        </Steps>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Skills troubleshooting"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              <>
                The editor says <em>This is a global skill managed by administrators. You cannot edit it.</em>, or an
                agent gets <code>Only admins can modify global skills</code>
              </>,
              'Global skills can be changed or deleted by administrators only.',
              'Ask an administrator to make the change, or create your own skill with the instructions you need.',
            ],
            [
              <>
                Assigning fails with <code>Cannot assign: agent already has 8 skills, adding 3 would exceed limit of 10</code>
              </>,
              'An agent can have at most 10 skills, and assign adds to the skills it already has.',
              <>
                Remove skills from the agent first, or pass the complete list (10 at most) as <code>skill_ids</code> when
                you update the agent, which replaces the whole set.
              </>,
            ],
            [
              <>
                Deleting fails with <code>Cannot delete default skill</code>
              </>,
              'Built-in default skills cannot be deleted.',
              <>
                Use <strong>Reset to default</strong> to restore its content, or switch it off for yourself with the
                toggle on its row.
              </>,
            ],
            [
              <>
                On a self-hosted install, a skill says <code>This global skill is provided by the cloud and is read-only on this install</code>
              </>,
              'The skill came from the cloud skill bundle, and the cloud owns its content.',
              'Switch it off for yourself if you do not want it in your new chats. You cannot edit or delete it locally.',
            ],
            [
              <>
                Publishing fails with <code>interfaceId is required to publish a SKILL (landing page)</code>
              </>,
              'Every skill listing needs a landing page.',
              'Choose an interface as the landing page when you publish.',
            ],
            [
              <>
                Publishing fails with <code>Paid templates are coming soon. All new publications must be free</code>
              </>,
              'A per-use credit price above 0 was set, and paid templates are not enabled on the installation.',
              'Publish the skill for free (a price of 0).',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Bot} title="Agents" href="/agents">
            Assign skills to agents and manage long-term memory.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Listings, visibility, and review.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Link a Community Edition install to the cloud.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
