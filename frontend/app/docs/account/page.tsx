import { Bell, CreditCard, Shield } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CardGrid, Card, Steps, Step } from '../_components';

export const metadata = docsMetadata({
  title: 'Account & settings',
  description:
    'A map of the Settings area: every page a regular user can open, your profile and preferences, password, notifications, and what deleting your account does.',
  path: '/docs/account',
});

export default function AccountPage() {
  return (
    <>
      <DocsHero
        eyebrow="Account & billing"
        title="Account & settings"
        lead="Everything personal to your account lives under Settings. This page maps each settings page a regular user can open, explains your profile and preferences, and describes what happens when you delete your account."
      />

      <DocsProse>
        <h2>Open Settings</h2>
        <p>
          Open the menu on your name in the sidebar and choose <strong>Settings</strong>. The
          settings area opens on <strong>Overview</strong>, with the list of settings pages on the left
          (a scrollable strip at the top on a phone).
        </p>
        <p>
          The list you see depends on your edition and your role. Pages reserved for the platform
          administrator never appear for a regular user; they are described in{' '}
          <a href="/admin">Administration</a>.
        </p>

        <h2>Map of the settings pages</h2>
        <p>The pages appear in the settings list in this order, grouped by topic.</p>
        <DocsTable
          caption="Settings pages visible to a regular user"
          rowHeaders
          head={['Page', 'What you do there', 'Covered in depth']}
          rows={[
            [
              <strong key="p">Overview</strong>,
              'Your profile, password, trophies, preferences, notification choices, and account deletion. Detailed below.',
              'This page',
            ],
            [
              <strong key="p">Organization</strong>,
              'Members and invitations, workspaces, single sign-on and the audit log of your organization. What you can change depends on your organization role.',
              <a key="l" href="/organizations">Organizations &amp; roles</a>,
            ],
            [
              <strong key="p">Information</strong>,
              'About LiveContext. On a self-hosted install it also shows the running version and optional components; on the cloud it shows the platform status.',
              <a key="l" href="/admin">Administration</a>,
            ],
            [
              <strong key="p">Pricing</strong>,
              'The plan grid. On a self-hosted install, plan actions go through the linked cloud account.',
              <a key="l" href="/billing">Plans &amp; billing</a>,
            ],
            [
              <strong key="p">Billing</strong>,
              'Billing & Subscription: your plan, payment method and invoices.',
              <a key="l" href="/billing">Plans &amp; billing</a>,
            ],
            [
              <strong key="p">Quota &amp; Usage</strong>,
              'Your credit balance, a breakdown of what consumed credits, and the full history.',
              <a key="l" href="/billing">Plans &amp; billing</a>,
            ],
            [
              <strong key="p">Storage</strong>,
              'How much storage you use against your limit, broken down by category (files, step outputs, tables, conversations and more), with a warning when you approach or reach the limit. In an organization workspace, all members consume from one shared pool.',
              <a key="l" href="/files">Files &amp; storage</a>,
            ],
            [
              <strong key="p">Refer &amp; earn</strong>,
              'Share your referral code or link, follow your rewards, and redeem a code you received.',
              <a key="l" href="/billing">Plans &amp; billing</a>,
            ],
            [
              <strong key="p">Public Access</strong>,
              'Every public entry point in one place: Webhooks, Chats, Forms, Schedules, and the shared links of Conversations and Applications.',
              <a key="l" href="/public-access">Public access</a>,
            ],
            [
              <strong key="p">Channels</strong>,
              'Where your approvals and your agents’ questions reach you when you are not in the app.',
              <a key="l" href="/channels">Channels</a>,
            ],
            [
              <strong key="p">Credentials &amp; Variables</strong>,
              'Connect external services (My Credentials, Available Integrations) and manage reusable workflow variables (Variables tab).',
              <a key="l" href="/integrations">Integrations</a>,
            ],
            [
              <strong key="p">Custom APIs</strong>,
              'Register, edit and delete your own API integrations.',
              <a key="l" href="/integrations">Integrations</a>,
            ],
            [
              <strong key="p">MCP Server</strong>,
              'Connect MCP clients such as Claude Code, Cursor or Codex to your LiveContext tools, with named access keys.',
              <a key="l" href="/mcp-server">MCP server</a>,
            ],
            [
              <strong key="p">AI Providers</strong>,
              'On the cloud, a regular user sees only Your keys: run your agents on your own provider API key.',
              <a key="l" href="/models">Models &amp; providers</a>,
            ],
          ]}
        />
        <Callout title="Edition and role gates">
          <p>
            <strong>Billing</strong> is hidden on a self-hosted Community Edition install. On a
            self-hosted install, <strong>AI Providers</strong> is visible to the platform administrator
            only; on the cloud, a regular user opens it to manage <strong>Your keys</strong>, which
            need the Pro plan or higher. The older{' '}
            <strong>Webhooks</strong> settings address now opens <strong>Public Access</strong>.
          </p>
        </Callout>
        <Callout title="Refer & earn on a self-hosted install">
          <p>
            Referrals and code redemption run on a cloud account. On a self-hosted install the page
            asks you to connect a cloud account first, and rewards land on that cloud account. Only the
            administrator can connect the install (see <a href="/admin">Administration</a>).
          </p>
        </Callout>

        <h2>Your profile</h2>
        <p>
          <strong>Overview</strong> has six tabs: <strong>Profile</strong>, <strong>Security</strong>,{' '}
          <strong>Trophies</strong>, <strong>Preferences</strong>, <strong>Notifications</strong> and{' '}
          <strong>Advanced</strong>.
        </p>
        <p>
          <strong>Profile</strong> shows your <strong>Account Information</strong>:
        </p>
        <ul>
          <li>
            <strong>Name</strong>: the display name others see. You can change it once every 7 days.
          </li>
          <li>
            <strong>Email</strong>: read-only.
          </li>
          <li>
            <strong>Account Type</strong>: how you sign in, for example <strong>Email/Password</strong>,
            Google, GitHub, Microsoft, or <strong>Single sign-on (SAML)</strong>.
          </li>
        </ul>
        <p>Below it, your public profile settings:</p>
        <ul>
          <li>
            <strong>Handle</strong>: your profile lives at <code>@handle</code>. A saved handle is 2 to
            32 characters of lowercase letters, digits and underscores. What you type is converted
            on save: capitals become lowercase, accents are dropped, and dots, hyphens, spaces and
            other symbols become underscores. You can change it once every 7 days.
          </li>
          <li>
            <strong>Bio</strong>: a short text about you, saved automatically.
          </li>
          <li>
            <strong>Profile visibility</strong>: <strong>Public</strong> (search engines can list your
            profile), <strong>Unlisted</strong> (reachable only by link) or{' '}
            <strong>Private</strong> (no profile page). Your name always appears on the apps you
            publish, whatever this setting.
          </li>
        </ul>
        <p>
          <strong>View public profile</strong> opens the page as others see it. See{' '}
          <a href="/marketplace">Marketplace</a> for how your profile appears next to what you publish.
        </p>

        <h3>Password</h3>
        <p>
          The <strong>Security</strong> tab exists only for accounts that sign in with an email and
          password. If you sign in with Google, GitHub, Microsoft, Facebook, or your organization&apos;s
          single sign-on, your password is managed by that provider and the tab is not shown.
        </p>
        <ul>
          <li>
            On the cloud, <strong>Change password</strong> takes you to the secure identity provider
            to update it, then brings you back.
          </li>
          <li>
            On a self-hosted install, fill in <strong>Current Password</strong>,{' '}
            <strong>New Password</strong> (at least 8 characters) and{' '}
            <strong>Confirm New Password</strong>, then select <strong>Update Password</strong>. You are
            asked to sign in again with the new password.
          </li>
        </ul>

        <h3>Trophies</h3>
        <p>
          <strong>Trophies</strong> lists the badges you have earned.
        </p>

        <h2>Preferences</h2>
        <p>
          The <strong>Preferences</strong> tab (<strong>General Preferences</strong>) holds how the app
          looks and behaves for you:
        </p>
        <DocsTable
          caption="Preferences"
          rowHeaders
          head={['Setting', 'Choices', 'Effect']}
          rows={[
            [
              <strong key="s">Language</strong>,
              'English, Français, Español, Deutsch, Português, 中文',
              'The interface language. Dates and numbers follow it too.',
            ],
            [
              <strong key="s">Theme</strong>,
              <span key="c"><strong>Light</strong>, <strong>Dark</strong>, <strong>System</strong></span>,
              'The interface appearance. System follows your device.',
            ],
            [
              <strong key="s">Default side panel position</strong>,
              <span key="c"><strong>Right</strong>, <strong>Bottom</strong></span>,
              'Where the side panel opens by default across the app.',
            ],
            [
              <strong key="s">Workflow layout</strong>,
              <span key="c"><strong>Left to right</strong>, <strong>Top to bottom</strong></span>,
              'The direction a new workflow starts in. A saved workflow keeps its own direction, which you change from its canvas settings.',
            ],
            [
              <strong key="s">Node inspector</strong>,
              <span key="c"><strong>Floating on the canvas</strong>, <strong>In the side panel</strong></span>,
              'Where a node’s configuration opens while you build.',
            ],
            [
              <strong key="s">Node click opens</strong>,
              <span key="c"><strong>Settings only</strong>, <strong>Settings, input and output</strong></span>,
              'How much of a node opens when you click it while building. Opening a run always shows the full view.',
            ],
            [
              <strong key="s">Bottom panel style</strong>,
              <span key="c"><strong>Full width (under the sidebar)</strong>, <strong>Content width</strong></span>,
              'How the panel renders when opened at the bottom.',
            ],
          ]}
        />
        <p>
          The same tab links to <strong>Open agent &amp; chat settings</strong>, the chat defaults
          applied to new conversations in the workspace.
        </p>

        <h2>Notifications</h2>
        <p>
          The <strong>Notifications</strong> tab sets where each kind of alert reaches you (email, a
          chat channel, both, or <strong>Bell only</strong>) for workflow failures, credits, account
          issues and tasks. The bell in the app always gets everything. See{' '}
          <a href="/notifications">Notifications</a> for the details.
        </p>
        <Callout title="Cloud only">
          <p>
            On the cloud the tab also has <strong>Email updates</strong>, an opt-in for occasional news
            and offers. Emails about your account are sent either way. A self-hosted install does not
            show this setting.
          </p>
        </Callout>

        <h2>Delete your account</h2>
        <p>
          Deletion happens in two stages. Your account is deactivated right away, then permanently
          deleted after a 30-day grace period.
        </p>
        <Steps>
          <Step n={1} title="Request the deletion">
            In <strong>Overview</strong>, open the <strong>Advanced</strong> tab and select{' '}
            <strong>Delete My Account</strong>.
          </Step>
          <Step n={2} title="Confirm">
            The <strong>Delete Account?</strong> dialog lists what happens: all your data is deleted,
            your subscription is cancelled, and your MCP tools are deleted. Type{' '}
            <code>DELETE</code> and select <strong>Delete Permanently</strong>. You are signed out.
          </Step>
        </Steps>
        <p>
          During the grace period nothing has been deleted yet: the account is only deactivated. If
          you sign back in, the app shows <strong>Your account is scheduled for deletion</strong> with
          the deletion date. Select <strong>Reactivate my account</strong> to cancel the deletion; your
          workflows, agents, tables and files come back untouched.
        </p>
        <Callout variant="warn" title="Permanent after 30 days">
          <p>
            Once the deletion date passes, the deletion is permanent and cannot be reversed. Save
            anything you want to keep before you request it.
          </p>
        </Callout>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common account problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'Saving a handle shows "This handle is invalid or already taken."',
              'Someone else has that handle, or fewer than 2 characters are left after conversion.',
              'Pick another handle of 2 to 32 lowercase letters, digits, or underscores.',
            ],
            [
              'Your handle or name will not save and a date is shown',
              'Each can be changed only once every 7 days.',
              'Wait until the date shown, then change it again.',
            ],
            [
              'The saved handle differs from what you typed',
              'Handles are converted on save: capitals become lowercase and dots, hyphens, and spaces become underscores.',
              'Nothing to fix. Your profile lives at the converted handle.',
            ],
            [
              'The Security tab is missing',
              'You sign in with Google, GitHub, Microsoft, Facebook, or single sign-on, so the provider manages your password.',
              'Change your password with that provider.',
            ],
            [
              'Signing in shows "Your account is scheduled for deletion"',
              'You requested account deletion less than 30 days ago.',
              'Select Reactivate my account to keep it, or leave it to be deleted on the date shown.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={CreditCard} title="Plans & billing" href="/billing">
            Plans, credits, usage history and invoices.
          </Card>
          <Card icon={Bell} title="Notifications" href="/notifications">
            The bell, alert routing and email alerts.
          </Card>
          <Card icon={Shield} title="Administration" href="/admin">
            The settings pages reserved for the platform administrator.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
