import { Bell, Columns3, Settings } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Tour of the workspace',
  description:
    'A map of the LiveContext app after you sign in: the sidebar, the header, the side panel and its tabs, projects, folders, settings, shortcuts, and small screens.',
  path: '/docs/workspace',
});

export default function WorkspaceTourPage() {
  return (
    <>
      <DocsHero
        eyebrow="Get started"
        title="Tour of the workspace"
        lead="Where everything lives once you are signed in. Use this page as a map: each area gets a short description and a link to the page that covers it in depth."
      />

      <DocsProse>
        <h2>The layout at a glance</h2>
        <p>The app has four permanent regions:</p>
        <ul>
          <li>
            <strong>The sidebar</strong> on the left: new chat, the marketplace, the pages you chose
            to show, your projects, and your chat history. The user menu sits at its bottom.
          </li>
          <li>
            <strong>The header</strong> across the top: the page you are on, search, the
            notification bell, and the buttons that open the side panel.
          </li>
          <li>
            <strong>The main area</strong>: the page you opened (a chat, a workflow canvas, a table,
            and so on).
          </li>
          <li>
            <strong>The side panel</strong>: a second, tabbed work area that opens next to the main
            area, so you can keep a chat, a table, or a run open while you work on something else.
          </li>
        </ul>

        <h2>Home: chat and studio</h2>
        <p>
          The home page is a new conversation with the assistant (<strong>New chat</strong> in the
          sidebar). From its composer, a <strong>Chat</strong> / <strong>Studio</strong> switch moves
          you between talking to the assistant and generating media in the studio. See{' '}
          <a href="/chat">Chat</a> and <a href="/studio">Studio</a>.
        </p>
        <Callout title="Role required">
          The studio switch is not offered to a workspace <strong>Viewer</strong>: a generation
          writes a file into the workspace and spends credits.
        </Callout>

        <h2>The sidebar</h2>
        <p>
          <strong>Marketplace</strong> always sits at the top of the navigation. Below it come the
          pages you keep in the sidebar:
        </p>
        <DocsTable
          caption="Pages you can show in the sidebar"
          rowHeaders
          head={['Page', 'What you find there', 'Learn more']}
          rows={[
            ['Board', 'Tasks, plus kanban views of your workflows and applications.', <a key="l" href="/board">Tasks &amp; board</a>],
            ['Agenda', 'A calendar of every scheduled and armed automation.', <a key="l" href="/agenda">Agenda</a>],
            ['Agents', 'Your agents, their skills, memory, fleet and metrics.', <a key="l" href="/agents">Agents</a>],
            ['Applications', 'Apps you published or installed from the marketplace.', <a key="l" href="/interfaces">Interfaces &amp; apps</a>],
            ['Workflows', 'Every workflow in the workspace, and the builder.', <a key="l" href="/workflows">Workflows</a>],
            ['Interfaces', 'The web pages your workflows and apps display.', <a key="l" href="/interfaces">Interfaces &amp; apps</a>],
            ['Tables', 'Built-in data tables.', <a key="l" href="/tables">Tables &amp; data</a>],
            ['Files', 'Uploaded and generated files, in folders.', <a key="l" href="/files">Files &amp; storage</a>],
          ]}
        />

        <h3>Choose which pages appear</h3>
        <p>
          <strong>Board</strong>, <strong>Applications</strong>, and <strong>Interfaces</strong> start
          hidden. They are not gone: open the sidebar&apos;s <strong>More</strong> menu to reach them,
          or open <strong>Customize</strong> and tick them under{' '}
          <strong>Pages in the sidebar</strong>. The sidebar always keeps at least four pages;{' '}
          <strong>Reset to defaults</strong> restores the original set. Your choice is remembered in
          this browser.
        </p>
        <p>
          In the same panel, <strong>Quick open</strong> picks the page that the home page shortcut
          and the <kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>O</kbd> keys open. It is{' '}
          <strong>Agenda</strong> until you change it.
        </p>

        <h3>Chats and direct messages</h3>
        <p>
          The lower part of the sidebar lists your conversations. Filter them with{' '}
          <strong>All</strong>, <strong>Agents</strong>, <strong>Workflows</strong>, or{' '}
          <strong>Studio</strong>, or use <strong>Search chats</strong>. Each conversation&apos;s menu
          can share it, clear its messages, delete it, or jump to the workflow or agent it belongs
          to.
        </p>
        <p>
          The <strong>Show direct messages</strong> toggle switches the same list to{' '}
          <strong>Messages</strong>: one-to-one threads with other people. Start one with{' '}
          <strong>New message</strong>, and filter between <strong>Workspace teammates</strong> and{' '}
          <strong>Other conversations</strong>. Deleting a thread removes it from your inbox only; a
          new message brings it back.
        </p>

        <h3>Projects</h3>
        <p>
          A project groups related resources. Create one with <strong>New project</strong> in the{' '}
          <strong>Projects</strong> section of the sidebar: first its name, description, colour and
          icon, then (optionally) the workflows, interfaces, agents and files to assign. A project
          page has one tab per resource type (<strong>Workflows</strong>, <strong>Interfaces</strong>,{' '}
          <strong>Agents</strong>, <strong>Tables</strong>, <strong>Applications</strong>,{' '}
          <strong>Files</strong>) plus <strong>Members</strong>. Deleting a project unassigns its
          resources; it does not delete them.
        </p>

        <h3>Folders and favorites</h3>
        <p>
          The <strong>Workflows</strong>, <strong>Agents</strong>, <strong>Tables</strong>,{' '}
          <strong>Interfaces</strong> and <strong>Applications</strong> lists can be organized into
          folders (<strong>New folder</strong>, <strong>Move to folder</strong>, or drag and drop).
          Items in those lists can also be starred with <strong>Add to favorites</strong>. The{' '}
          <strong>Files</strong> page has its own folders; see <a href="/files">Files &amp; storage</a>.
        </p>

        <h3>The user menu</h3>
        <p>Click your avatar at the bottom of the sidebar to open it:</p>
        <ul>
          <li>
            <strong>Settings</strong>, and <strong>Refer &amp; earn</strong>. On a self-hosted
            install it also has <strong>Cost</strong>, which opens usage and quota.
          </li>
          <li>
            The active workspace: switch to another one, or <strong>Invite teammates</strong>, and{' '}
            <strong>Create workspace</strong>. See <a href="/organizations">Organizations &amp; roles</a>{' '}
            for who can do what.
          </li>
          <li>Language and theme (<strong>Light mode</strong>, <strong>Dark mode</strong>, <strong>Auto</strong>).</li>
          <li><strong>Sign out</strong>.</li>
        </ul>

        <h2>The header</h2>
        <ul>
          <li>
            <strong>Search</strong> finds chats, workflows, agents and settings pages. Press{' '}
            <kbd>Ctrl</kbd> + <kbd>K</kbd> (<kbd>Cmd</kbd> + <kbd>K</kbd> on a Mac) to jump to it, then
            use the arrow keys and <kbd>Enter</kbd>.
          </li>
          <li>
            <strong>The notification bell</strong> holds your inbox, armed triggers, recent edits and
            shared links. See <a href="/notifications">Notifications</a>.
          </li>
          <li>
            <strong>Open panel on the right</strong> and <strong>Open panel at the bottom</strong>{' '}
            open the side panel in that position.
          </li>
        </ul>

        <h2>The side panel and its tabs</h2>
        <p>
          The side panel holds tabs, like a browser. <strong>Add tab</strong> opens a picker with the
          agenda, applications, interfaces, tables, workflows, workflow logs, agents, conversations,
          projects and files, with a search across all of them. Pages also open their own tabs there,
          for example a workflow&apos;s run history or a node&apos;s settings.
        </p>
        <DocsTable
          caption="Side panel controls"
          head={['Control', 'What it does']}
          rows={[
            [<strong key="c">Detach panel</strong>, 'Turns the panel into a floating window you can move and resize. Dock panel back returns it to its dock.'],
            [<strong key="c">Full screen</strong>, 'Fills the window with the panel. Exit full screen, or press Escape, to return.'],
            [<strong key="c">Move panel</strong>, 'On a detached panel, drag the title bar or use the arrow keys (Shift resizes from the bottom-right corner, Ctrl + Shift from the top-left, Alt moves by 1 pixel).'],
          ]}
        />
        <p>
          Where the panel opens by default is a preference: <strong>Default side panel position</strong>{' '}
          (<strong>Right</strong> or <strong>Bottom</strong>) and <strong>Bottom panel style</strong>{' '}
          (<strong>Full width (under the sidebar)</strong> or <strong>Content width</strong>), in{' '}
          <strong>Settings</strong> &gt; <strong>Overview</strong> &gt; <strong>Preferences</strong>.
        </p>

        <h2>Keyboard shortcuts</h2>
        <DocsTable
          caption="Keyboard shortcuts"
          head={['Keys', 'Action']}
          rows={[
            ['Ctrl + K (Cmd + K on a Mac)', 'Focus the search bar in the header.'],
            ['Ctrl + Shift + O (Shift + Cmd + O on a Mac)', 'Open your Quick open page (Agenda by default).'],
            ['Escape', 'Leave full screen in the side panel.'],
          ]}
        />

        <h2>Settings</h2>
        <p>
          <strong>Settings</strong> (from the user menu) groups everything about your account,
          workspace and keys. The main sections, and where they are documented:
        </p>
        <DocsTable
          caption="Settings sections and their documentation"
          rowHeaders
          head={['Section', 'Covers', 'Learn more']}
          rows={[
            ['Overview', 'Profile, security, trophies, preferences, notifications, account deletion.', <a key="l" href="/account">Account &amp; preferences</a>],
            ['Organization', 'Members, roles, workspaces, SSO, audit log.', <a key="l" href="/organizations">Organizations &amp; roles</a>],
            ['Pricing, Billing, Quota & Usage', 'Plans, invoices, credits and usage history.', <a key="l" href="/billing">Plans &amp; billing</a>],
            ['Storage', 'How much file storage you use.', <a key="l" href="/files">Files &amp; storage</a>],
            ['Public Access', 'Webhook, chat and form endpoints, schedules, shared links.', <a key="l" href="/public-access">Public access</a>],
            ['Channels', 'Chat apps where approvals and alerts can reach you.', <a key="l" href="/channels">Channels</a>],
            ['Credentials & Variables, Custom APIs', 'Connected accounts, secrets and your own APIs.', <a key="l" href="/integrations">Integrations</a>],
            ['MCP Server', 'Keys for connecting external MCP clients.', <a key="l" href="/mcp-server">MCP server</a>],
            ['AI Providers', 'Model provider keys.', <a key="l" href="/models">Models &amp; providers</a>],
          ]}
        />
        <Callout title="Edition and role differences">
          <strong>Billing</strong> is not shown on a self-hosted install. On a self-hosted install,{' '}
          <strong>AI Providers</strong> is for platform admins only. Platform admins also see
          administration sections that other users do not.
        </Callout>

        <h2>Phones and small screens</h2>
        <p>
          LiveContext runs in the browser; there is no app to install. Below tablet width the layout
          adapts: the sidebar opens over the page (<strong>Close sidebar</strong> hides it again), the
          header search becomes a button, and the side panel opens as an overlay over the page
          instead of beside it (tap outside it to close). The detached,
          bottom and full-screen panel modes are desktop only.
        </p>

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={Columns3} title="Tasks & board" href="/board">
            Track work for people and agents.
          </Card>
          <Card icon={Bell} title="Notifications" href="/notifications">
            What the bell shows and what reaches you by email or chat.
          </Card>
          <Card icon={Settings} title="Account & preferences" href="/account">
            Profile, preferences and notification settings.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
