import { CreditCard, Server, Workflow } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Organizations & roles',
  description:
    'Workspaces, members and roles: create, switch, delete and restore workspaces, invite members, restrict access, set member quotas, read the audit log, and set up SAML SSO with verified domains.',
  path: '/docs/organizations',
});

export default function OrganizationsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Share & host"
        title="Organizations & roles"
        lead="Every workspace is an organization: a container for workflows, agents, tables, and files. You always have a personal one. Pro, Team, and Enterprise let you create more, and Team and Enterprise let you invite people into them with a role and control what each member can see, spend, and do."
      />

      <DocsProse>
        <h2>Workspaces: personal vs extra</h2>
        <p>
          Every user gets a personal workspace automatically at signup, named after
          them (for example &ldquo;Alex&apos;s Workspace&rdquo;). You are its <code>OWNER</code> and it
          is your default workspace. The personal workspace is special: it is never paused, it can
          never be deleted, and it never expires.
        </p>
        <p>
          On Pro, Team, and Enterprise, an owner can
          create extra workspaces beyond the personal one. Switching into one is an
          explicit action, never automatic. An extra workspace does not start a new subscription: it
          shares the owner&apos;s plan, so members you invite into it draw from the owner&apos;s credit
          wallet (subject to any per-member quota you set, see below).
        </p>
        <p>
          Storage is one shared pool per account. The plan&apos;s storage allowance
          belongs to the owner&apos;s account, not to each workspace: what is stored in every workspace
          the owner owns is added up. Once the pool is full, every one of those workspaces refuses new
          files, including a workspace that has stored nothing itself. See{' '}
          <a href="/billing">Plans &amp; billing</a> for the allowance of each plan.
        </p>
        <p>
          You manage all of this in <strong>Settings &gt; Organization</strong>, which has four tabs:{' '}
          <strong>Members</strong>, <strong>Workspaces</strong>, <strong>Security</strong>, and{' '}
          <strong>Advanced</strong>.
        </p>

        <h2>Creating, switching, deleting, and restoring workspaces</h2>
        <p>
          How many workspaces you can have is capped by your plan. The count includes the personal
          workspace:
        </p>
        <DocsTable
          caption="Maximum workspaces per plan"
          head={['Plan', 'Max workspaces']}
          rowHeaders
          rows={[
            ['Free, Starter', '1 (personal only)'],
            ['Pro', '3'],
            ['Team', '10'],
            ['Enterprise (all tiers)', 'Unlimited'],
          ]}
        />
        <p>
          Creating one over the cap fails with <code>WORKSPACE_LIMIT_REACHED</code>. Switching changes
          which workspace you land in. On the cloud, after switching to a <em>different</em> workspace
          you wait a few seconds (5 by default) before you can switch again; re-selecting the one you
          are already in never counts. The self-hosted Community Edition has no switch cooldown.
        </p>
        <p>
          Deleting a workspace other than the personal one requires typing its exact name to confirm,
          and only the <code>OWNER</code> can do it. Deletion is soft: the workspace
          disappears from your switcher, its pending invitations are cancelled, and any member whose
          default was that workspace is moved to one that still exists, but the data is kept during a
          grace window.
        </p>
        <Steps>
          <Step n={1} title="Delete">
            The <code>OWNER</code> deletes the workspace with the matching confirmation name. It is
            hidden immediately but not yet gone.
          </Step>
          <Step n={2} title="Grace window (30 days by default)">
            The workspace, its data, and its audit trail stay intact. The <code>OWNER</code> can
            restore it at any time during this window.
          </Step>
          <Step n={3} title="Restore, or permanent purge">
            If the <code>OWNER</code> restores it, it reappears as it was. If nobody restores it before
            the window closes, a daily cleanup permanently removes its data. Billing and audit history
            stay valid after the purge.
          </Step>
        </Steps>
        <Callout variant="warn">
          The personal workspace can never be deleted, paused, or purged. There is always at least one
          workspace you cannot lose.
        </Callout>
        <p>
          If you downgrade below your current workspace count, the extra workspaces beyond the new cap
          (never the personal one) are paused rather than deleted: nobody, including
          the owner, can enter them, but their data is kept. Upgrading again un-pauses them
          automatically.
        </p>

        <h2>Roles: the permission matrix</h2>
        <p>
          Every membership has exactly one of four roles. <code>MEMBER</code> is the default when
          someone is invited without choosing a role.
        </p>
        <DocsTable
          head={['Action', 'OWNER', 'ADMIN', 'MEMBER', 'VIEWER']}
          caption="What each workspace role can do"
          rowHeaders
          rows={[
            ['Read and use workspace resources (workflows, tables, and so on)', 'Yes', 'Yes', 'Yes', 'Read-only'],
            ['Invite members', 'Yes', 'Yes', 'No', 'No'],
            ["Change a member's role", 'Yes', 'No', 'No', 'No'],
            ['Remove a member (see note)', 'Yes', 'Yes', 'No', 'No'],
            ['Cancel a pending invitation', 'Yes', 'Yes', 'No', 'No'],
            ['Rename the workspace, upload or remove its avatar', 'Yes', 'Yes', 'No', 'No'],
            ['Read the audit log', 'Yes', 'Yes', 'No', 'No'],
            ['Manage SAML SSO and verified domains', 'Yes', 'Yes', 'No', 'No'],
            ['Set per-member quotas', 'Yes', 'Yes', 'No', 'No'],
            ['Set per-member resource restrictions', 'Yes', 'Yes', 'No', 'No'],
            ['Delete or restore the workspace', 'Yes', 'No', 'No', 'No'],
          ]}
        />
        <p>
          Note: an <code>ADMIN</code> can remove a <code>MEMBER</code> or <code>VIEWER</code>, but not
          another <code>ADMIN</code> and never the <code>OWNER</code>. Nobody can remove themselves: use
          leave instead.
        </p>
        <Callout variant="info">
          <code>OWNER</code> and <code>ADMIN</code> are exempt from per-member resource restrictions:
          they always see everything. Usage quotas are a separate control, and a quota can never be set
          on the <code>OWNER</code>.
        </Callout>

        <h2>Inviting members</h2>
        <p>
          An <code>OWNER</code> or <code>ADMIN</code> invites by email and role with{' '}
          <strong>Invite Member</strong> on the <strong>Members</strong> tab. The role defaults to{' '}
          <code>MEMBER</code>, and you cannot invite someone as <code>OWNER</code>. Inviting requires a
          Team or Enterprise plan; on any other plan you get a &ldquo;Current plan does not support team
          members&rdquo; error.
        </p>
        <p>Each plan also caps total members (current members plus pending invitations):</p>
        <DocsTable
          caption="Maximum members per workspace, by plan"
          head={['Plan', 'Max members']}
          rowHeaders
          rows={[
            ['Free, Starter, Pro', '1 (just the owner)'],
            ['Team', '25'],
            ['Enterprise Basic', '25'],
            ['Enterprise Standard', '50'],
            ['Enterprise Premium', '100'],
            ['Enterprise Ultimate', '500'],
          ]}
        />
        <p>
          Inviting someone who is already a member, or who already has a pending invitation, is
          rejected. To prevent abuse, one person can send at most 20 invitations per hour and a
          workspace at most 50 per hour; hitting either limit is recorded in the audit log.
        </p>
        <Callout variant="info" title="Cloud vs self-hosted delivery">
          The cloud emails the invitation link. The self-hosted Community Edition never emails
          invitations (even when SMTP is configured for other mail): an existing local user gets an
          in-app notification instead, and the admin gets a <strong>Copy invite link</strong> to hand
          over directly. A new person can register through that link even when public registration is
          closed on the install.
        </Callout>

        <h2>Invitation lifecycle</h2>
        <p>
          An invitation is <code>PENDING</code>, <code>ACCEPTED</code>, <code>EXPIRED</code>, or{' '}
          <code>CANCELLED</code>. There is no separate &ldquo;declined&rdquo; status: declining moves it
          to <code>CANCELLED</code>. Invitations expire 7 days after they are sent.
        </p>
        <DocsTable
          caption="Invitation actions by actor"
          head={['Actor', 'Action', 'Effect']}
          rows={[
            ['Invitee', 'Accept (from the link, or from their invitation inbox)', 'Joins with the invited role. The signed-in email must match the invited address.'],
            ['Invitee', 'Decline', 'The invitation moves to CANCELLED.'],
            ['OWNER or ADMIN', 'Cancel', 'Only while the invitation is still PENDING.'],
          ]}
        />
        <p>
          Every invitation needs an explicit accept: nobody is added silently. Accepting re-checks
          capacity at that moment, so an invitation can still be refused if the workspace was deleted,
          the team plan lapsed, or the member cap filled up in the meantime.
        </p>

        <h2>Changing roles, removing members, and leaving</h2>
        <p>
          Only the <code>OWNER</code> can change a member&apos;s role, and not their own role, and never
          to <code>OWNER</code>.
        </p>
        <p>
          Everyone except the <code>OWNER</code> can leave a workspace. An owner who tries gets{' '}
          <code>OWNER_CANNOT_LEAVE</code>. If the workspace you leave was your default, your next-oldest
          remaining membership becomes your default automatically.
        </p>
        <Callout variant="info" title="Ownership transfer">
          Transferring ownership to another member is not offered in the app at the moment. To stop
          owning a workspace, delete it (see above).
        </Callout>

        <h2>Per-member resource restrictions</h2>
        <p>
          Beyond roles, an <code>OWNER</code> or <code>ADMIN</code> can fine-tune what a specific{' '}
          <code>MEMBER</code> or <code>VIEWER</code> can reach, resource by resource, with{' '}
          <strong>Manage Access</strong> on the <strong>Members</strong> tab. Each resource has one of
          three levels:
        </p>
        <DocsTable
          caption="Per-member resource access levels"
          head={['Level', 'Effect']}
          rowHeaders
          rows={[
            ['Full access', 'The default. The member sees and uses the resource according to their role.'],
            ['Read-only', 'The resource stays visible, but any change to it is blocked.'],
            ['No access', 'The resource is hidden entirely, from lists and from direct access.'],
          ]}
        />
        <p>
          You can restrict workflows, applications, interfaces, agents, datasources, projects, files,
          and skills, one at a time or in bulk (<strong>Allow all</strong>, <strong>Block all</strong>).
          Restrictions set on an <code>OWNER</code> or <code>ADMIN</code> are ignored.
        </p>

        <h2>Member quotas</h2>
        <p>
          An <code>OWNER</code> or <code>ADMIN</code> can also cap how much a member consumes, with{' '}
          <strong>Manage quota</strong>, on up to three independent dimensions: credits, storage, and
          LLM tokens. An unset dimension has no cap. Caps reset on the same monthly cycle as the
          owner&apos;s subscription, and there is one cap configuration per member.
        </p>
        <Callout variant="info">
          A quota applies to the person who runs the work, not to the wallet that pays for it, so
          having usage billed to the owner does not let a member bypass their own cap. A quota cannot be
          set on the <code>OWNER</code>.
        </Callout>

        <h2>Audit log</h2>
        <p>
          <code>OWNER</code> and <code>ADMIN</code> can read the workspace audit log on the{' '}
          <strong>Security</strong> tab: newest first, paginated, and filterable by event type. Each
          entry shows who did it and to whom.
        </p>
        <CodeBlock language="text" title="Audit event types">{`ORG_MEMBER_INVITED · ORG_INVITE_ACCEPTED · ORG_INVITE_CANCELLED · ORG_INVITE_RATE_LIMITED
ORG_MEMBER_REMOVED · ORG_MEMBER_LEFT · ORG_ROLE_CHANGED · ORG_OWNERSHIP_TRANSFERRED
ORG_DELETED · ORG_RESTORED · ORG_PURGED
ORG_QUOTA_CAP_SET · ORG_QUOTA_CAP_REMOVED · ORG_QUOTA_CAP_EXCEEDED
ORG_SAML_SSO_CONFIGURED · ORG_SAML_SSO_DELETED · ORG_SAML_SSO_MEMBER_JOINED
ORG_SSO_DOMAIN_ADDED · ORG_SSO_DOMAIN_VERIFIED · ORG_SSO_DOMAIN_REMOVED`}</CodeBlock>
        <p>
          The audit trail survives a permanent purge (the purge itself is recorded as{' '}
          <code>ORG_PURGED</code>), so history stays readable after the data is gone.
        </p>

        <h2>Workspace avatar</h2>
        <p>
          A workspace can carry one avatar image (JPEG, PNG, GIF, or WebP, up to 5 MB), uploaded or
          replaced by an <code>OWNER</code> or <code>ADMIN</code>. Removing it falls back to an image
          with the workspace&apos;s initials, so there is never a broken image. A new upload or a rename
          shows up everywhere right away.
        </p>

        <h2>SAML SSO</h2>
        <Callout variant="info" title="Cloud only, Team and Enterprise">
          SAML SSO is available on Team and Enterprise workspaces on LiveContext Cloud, and is managed
          by an <code>OWNER</code> or <code>ADMIN</code>. On the self-hosted Community Edition the panel
          reads &ldquo;SAML SSO is only available on LiveContext Cloud. Self-hosted installs can&apos;t
          enable SSO.&rdquo;
        </Callout>
        <p>
          A workspace can connect its own SAML identity provider (IdP). SAML users are added as
          workspace members on their first login. Setting it up has two parts: the connection, and at
          least one verified email domain.
        </p>

        <h3>Connect your identity provider</h3>
        <Steps>
          <Step n={1} title="Open the SAML SSO panel">
            Go to <strong>Settings &gt; Organization</strong>, then the <strong>Security</strong> tab.
          </Step>
          <Step n={2} title="Enter the IdP details">
            Fill in <strong>Provider name</strong>, <strong>IdP entity ID</strong>,{' '}
            <strong>Single sign-on URL</strong> (HTTPS; plain HTTP is accepted only for localhost), and
            the <strong>X.509 signing certificate</strong>. When you edit an existing connection, leave
            the certificate blank to keep the current one. Then click <strong>Save SSO</strong>.
          </Step>
          <Step n={3} title="Configure your IdP">
            Copy the <strong>Service provider details</strong> into your IdP: <strong>SP entity ID</strong>,{' '}
            <strong>ACS URL</strong>, and <strong>Metadata URL</strong>. The panel also shows the{' '}
            <strong>Workspace SSO URL</strong> (a direct sign-in link for this workspace) and the{' '}
            <strong>Certificate fingerprint</strong>.
          </Step>
          <Step n={4} title="Verify at least one email domain">
            See the next section. Until a domain is verified, nobody can join through SSO.
          </Step>
        </Steps>
        <p>
          With <strong>Hide from the global login page</strong>, users sign in through the{' '}
          <strong>Workspace SSO URL</strong> instead of seeing a button for your workspace on the shared
          login page.
        </p>
        <DocsTable
          caption="Identity provider statuses"
          head={['Status', 'Meaning']}
          rowHeaders
          rows={[
            ['Draft', 'Saved but not yet provisioned.'],
            ['Active', 'Provisioned and ready: sign-ins through this IdP work.'],
            ['Error', 'Provisioning failed; an error message explains why.'],
            ['Disabled', 'Turned off without deleting the configuration.'],
          ]}
        />

        <h3>Verify your email domains</h3>
        <p>
          SSO sign-in works only for addresses on a domain this workspace has proven it owns. You prove
          it with a DNS TXT record, in the <strong>Verified email domains</strong> list of the same
          panel.
        </p>
        <Steps>
          <Step n={1} title="Add the domain">
            Type the domain (for example <code>company.com</code>) and click <strong>Add domain</strong>.
            It shows as <strong>Pending</strong>, with the record to publish.
          </Step>
          <Step n={2} title="Publish the TXT record">
            At your DNS provider, add a TXT record with the <strong>Name</strong> and{' '}
            <strong>Value</strong> the panel shows. The name is <code>_livecontext-sso.</code> followed by
            your domain, and the value starts with <code>livecontext-sso-verification=</code>. The record
            sits on its own name, so it never touches your domain&apos;s other TXT records such as SPF.
          </Step>
          <Step n={3} title="Click Check now">
            When the record is found, the domain shows as <strong>Verified</strong>. DNS changes can
            take a few minutes to appear: if the record is not visible yet, you are told so and can
            check again later.
          </Step>
        </Steps>
        <ul>
          <li>A workspace can list at most 20 domains.</li>
          <li>A domain can be verified by only one workspace.</li>
          <li>
            A new member can join through SSO only when the email their IdP sends is on
            a verified domain. Otherwise sign-in is refused with &ldquo;This email address is not on a
            domain verified for this workspace&apos;s SSO&rdquo;. People who are already members keep
            signing in as before.
          </li>
          <li>
            Joining through SSO still respects the team plan and the member cap. If the connection is
            not active, or the workspace was deleted, sign-in is refused.
          </li>
        </ul>

        <h3>How people sign in with SSO</h3>
        <p>
          On the LiveContext sign-in page, a person chooses <strong>Sign in with SSO</strong> and enters
          their <strong>Work email</strong>. If the email is on a verified domain of a workspace whose
          connection is active, they are sent to that workspace&apos;s identity provider. Otherwise they
          see &ldquo;No SSO is set up for this email domain. Sign in another way, or ask your workspace
          admin.&rdquo; People can also go straight to your <strong>Workspace SSO URL</strong>.
        </p>

        <h2>Signing in: cloud vs self-hosted</h2>
        <DocsTable
          head={['Topic', 'Cloud', 'Self-hosted (Community Edition)']}
          caption="Sign-in and workspace differences between cloud and self-hosted"
          rowHeaders
          rows={[
            ['Sign-in', 'Email and password, social login, and workspace SAML SSO.', 'Built-in email and password.'],
            ['SAML SSO', 'Team and Enterprise workspaces.', 'Not available.'],
            ['Invitations', 'Sent by email.', 'Never emailed: an in-app notification for existing users, plus a copyable invite link.'],
            ['Workspace switch cooldown', 'A few seconds (5 by default).', 'None.'],
          ]}
        />
        <p>
          The Community Edition can close public registration after setup. An invite link with a valid
          pending invitation still lets that specific person register. See{' '}
          <a href="/self-host">Self-hosting</a>.
        </p>

        <h2>Troubleshooting</h2>
        <DocsTable
          caption="Common workspace and member problems"
          rowHeaders
          head={['Symptom', 'Cause', 'Fix']}
          rows={[
            [
              'Inviting fails with "Current plan does not support team members"',
              'Only Team and Enterprise plans can have members.',
              'Upgrade the owner\'s plan to Team or Enterprise.',
            ],
            [
              'Inviting fails with "Member limit reached"',
              'Members plus pending invitations already fill the plan\'s member cap.',
              'Cancel pending invitations you no longer need, remove a member, or upgrade.',
            ],
            [
              <span key="s">
                Creating a workspace fails with <code>WORKSPACE_LIMIT_REACHED</code>
              </span>,
              'You already have as many workspaces as your plan allows, the personal one included.',
              'Delete a workspace you no longer use, or upgrade.',
            ],
            [
              'A workspace shows as Paused and cannot be entered',
              'It is beyond the current plan\'s workspace cap, usually after a downgrade. Its data is kept.',
              'Upgrade the plan: paused workspaces come back automatically.',
            ],
            [
              'An invitee cannot accept the invitation',
              'They are signed in with a different email than the invited address, or the invitation is older than 7 days.',
              'Sign in with the invited address, or send a new invitation.',
            ],
            [
              'On a self-hosted install, the invited person never gets an email',
              'The Community Edition never emails invitations.',
              'Use Copy invite link in Settings > Organization and send the link yourself.',
            ],
          ]}
        />

        <h2>Related pages</h2>
        <CardGrid cols={3}>
          <Card icon={CreditCard} title="Plans & billing" href="/billing">
            Who pays for members&apos; usage, and the storage each plan includes.
          </Card>
          <Card icon={Server} title="Self-hosting" href="/self-host">
            Workspaces and invitations on your own install.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            The automations your members build, run, and share.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
