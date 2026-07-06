import { Store, Workflow, Users } from 'lucide-react';
import { docsMetadata } from '../_meta';
import { DocsHero, DocsProse, DocsTable, Callout, CodeBlock, Steps, Step, CardGrid, Card } from '../_components';

export const metadata = docsMetadata({
  title: 'Organizations, members & roles',
  description:
    'Personal vs team workspaces, creating/switching/deleting/restoring them, the OWNER/ADMIN/MEMBER/VIEWER permission matrix, inviting and managing members, per-member resource restrictions and quotas, the audit log, workspace avatars, SAML SSO, and how sign-in differs between cloud and self-hosted.',
  path: '/docs/organizations',
});

export default function OrganizationsPage() {
  return (
    <>
      <DocsHero
        eyebrow="Access & SSO"
        title="Organizations, members & roles"
        lead="Every workspace is an organization: a container for workflows, agents, tables, and files. You always have a personal one, and a team plan lets you create more, invite people into them with a role, and fine-tune exactly what each member can see and do."
      />

      <DocsProse>
        <h2>Workspaces: personal vs team</h2>
        <p>
          Every user gets a <strong>personal workspace</strong> automatically at signup, named after
          them (e.g. &ldquo;Alex&apos;s Workspace&rdquo;), with a unique slug. You are its{' '}
          <code>OWNER</code> and it is your default workspace. The personal workspace is special: it
          is never paused, it can never be deleted, and it never expires.
        </p>
        <p>
          A team plan lets an owner create <strong>extra workspaces</strong> beyond the personal one.
          Switching into one is an explicit action, it is never automatic. An extra workspace shares
          the owner&apos;s existing credit wallet rather than starting a new subscription, so members
          you invite into it draw from that same wallet (subject to any per-member quota you set, see
          below).
        </p>

        <h2>Creating, switching, deleting &amp; restoring workspaces</h2>
        <p>
          How many workspaces you can create is capped by your plan&apos;s workspace limit, which
          counts the personal workspace:
        </p>
        <DocsTable
          head={['Plan', 'Max workspaces']}
          rows={[
            ['Free / Starter', '1 (personal only)'],
            ['Pro', '3'],
            ['Team', '10'],
            ['Enterprise (all tiers)', 'Unlimited'],
          ]}
        />
        <p>
          Creating one over the cap fails with <code>WORKSPACE_LIMIT_REACHED</code>. Switching your
          default workspace re-selects which one you land in; on cloud, switching to a{' '}
          <em>different</em> workspace has a short cooldown (60 seconds by default) before you can
          switch again, re-selecting the one you&apos;re already in never counts against it. Self-hosted
          Community Edition has no switch cooldown.
        </p>
        <p>
          Deleting a non-personal workspace requires typing its exact name to confirm, GitHub-style,
          and only the <code>OWNER</code> can do it. Deletion is <strong>soft</strong>: the workspace
          disappears from your switcher, its pending invitations are cancelled, and any member whose
          default was that workspace is re-homed to one that still exists, but the underlying data
          is left intact during a grace window.
        </p>
        <Steps>
          <Step n={1} title="Delete">
            The <code>OWNER</code> deletes the workspace with a matching confirmation name. It is
            hidden immediately but not yet gone.
          </Step>
          <Step n={2} title="Grace window (30 days by default)">
            The workspace, its data, and its audit trail stay intact. The <code>OWNER</code> can
            restore it at any point during this window.
          </Step>
          <Step n={3} title="Restore, or hard-purge">
            The <code>OWNER</code> can restore it and it reappears exactly as it was. If nobody
            restores it before the window closes, a daily cleanup job permanently purges its
            operational data. The organization row itself is kept as a tombstone so billing and audit
            history stay valid, it is never fully deleted.
          </Step>
        </Steps>
        <Callout variant="warn">
          The personal workspace can never be deleted, paused, or purged, no matter what. There is
          always at least one workspace you cannot lose.
        </Callout>
        <p>
          If you downgrade your plan below your current workspace count, the extra workspaces (beyond
          your new cap, and never the personal one) are <strong>paused</strong> rather than deleted:
          nobody, including the owner, can enter them, but their data is retained. Upgrading again
          un-pauses them automatically.
        </p>

        <h2>Roles: the permission matrix</h2>
        <p>
          Every membership has exactly one of four roles. <code>MEMBER</code> is the default when
          someone is invited without specifying a role.
        </p>
        <DocsTable
          head={['Action', 'OWNER', 'ADMIN', 'MEMBER', 'VIEWER']}
          rows={[
            ['Read &amp; use org resources (workflows, tables, ...)', 'Yes', 'Yes', 'Yes', 'Read-only'],
            ['Invite members', 'Yes', 'Yes', 'No', 'No'],
            ['Change a member&apos;s role', 'Yes', 'No', 'No', 'No'],
            ['Remove a member (not another ADMIN, not the OWNER)', 'Yes', 'Yes*', 'No', 'No'],
            ['Cancel a pending invitation', 'Yes', 'Yes', 'No', 'No'],
            ['Rename the workspace, upload/remove its avatar', 'Yes', 'Yes', 'No', 'No'],
            ['Read the audit log', 'Yes', 'Yes', 'No', 'No'],
            ['Manage SAML SSO', 'Yes', 'Yes', 'No', 'No'],
            ['Set per-member quotas', 'Yes', 'Yes', 'No', 'No'],
            ['Set per-member resource restrictions', 'Yes', 'Yes', 'No', 'No'],
            ['Delete / restore the workspace', 'Yes', 'No', 'No', 'No'],
            ['Transfer ownership', 'Yes', 'No', 'No', 'No'],
          ]}
        />
        <p>
          * An <code>ADMIN</code> can remove a <code>MEMBER</code> or <code>VIEWER</code>, but not
          another <code>ADMIN</code> and never the <code>OWNER</code>. Nobody can remove themselves,
          use leave instead.
        </p>
        <Callout variant="info">
          <code>OWNER</code> and <code>ADMIN</code> are exempt from per-member resource
          restrictions: whatever is set on them is ignored, they always see everything. Usage
          quotas are a separate control, and a quota can never be set on the <code>OWNER</code>.
        </Callout>

        <h2>Inviting members by email + role</h2>
        <p>
          An <code>OWNER</code> or <code>ADMIN</code> invites by email and role (defaults to{' '}
          <code>MEMBER</code>; you cannot invite someone directly as <code>OWNER</code>, ownership only
          moves by transfer, see below). Inviting requires a team-capable plan (Team or Enterprise);
          on any other plan you get a &ldquo;Current plan does not support team members&rdquo; error.
        </p>
        <p>Each plan also caps total members (current members plus any still-pending invitations):</p>
        <DocsTable
          head={['Plan', 'Max members']}
          rows={[
            ['Free / Starter / Pro / Pay-as-you-go', '1 (just the owner)'],
            ['Team', '25'],
            ['Enterprise Basic', '25'],
            ['Enterprise Standard', '50'],
            ['Enterprise Premium', '100'],
            ['Enterprise Ultimate', '500'],
          ]}
        />
        <p>
          Inviting someone who is already a member, or who already has a pending invite, is rejected.
          To keep invites from being abused, an inviter is capped at 20 invitations per hour, and an
          organization at 50 per hour, both are logged in the audit log when they trip.
        </p>
        <Callout variant="info">
          Delivery differs by edition. Cloud emails the invite link. Self-hosted Community Edition has
          no email system, so an existing local user instead gets an in-app notification, and the
          invite response/pending list surface the raw token so an admin can hand the invitee a
          working accept link directly. A brand-new invitee can also register through that link even
          if public registration is closed on that install.
        </Callout>

        <h2>Invitation lifecycle</h2>
        <p>
          An invitation is <code>PENDING</code>, <code>ACCEPTED</code>, <code>EXPIRED</code>, or{' '}
          <code>CANCELLED</code>. There is no separate &ldquo;declined&rdquo; status, declining simply
          moves it to <code>CANCELLED</code>. Invitations expire 7 days after they&apos;re sent.
        </p>
        <DocsTable
          head={['Actor', 'Action', 'Effect']}
          rows={[
            ['Invitee', 'Accept (via emailed link, or from their invitation inbox)', 'Joins as the invited role. Requires the signed-in email to match the invited address.'],
            ['Invitee', 'Decline', 'Invitation moves to CANCELLED.'],
            ['OWNER / ADMIN', 'Cancel', 'Only while still PENDING.'],
          ]}
        />
        <p>
          Every invitation requires an explicit accept click, there is no silent auto-join for
          existing users. Accepting re-checks capacity at that moment too, an invite issued while there
          was room can still be refused if the workspace was deleted, or the team plan lapsed, or the
          member cap filled up, in the meantime.
        </p>

        <h2>Changing roles, removing members, leaving, ownership transfer</h2>
        <p>
          Changing a member&apos;s role is <code>OWNER</code>-only, and the owner cannot change their
          own role or promote anyone to <code>OWNER</code> that way, ownership only moves by the
          dedicated transfer action below.
        </p>
        <p>
          Leaving is available to everyone <em>except</em> the <code>OWNER</code>, who must transfer
          ownership first (an owner trying to leave gets{' '}
          <code>OWNER_CANNOT_LEAVE</code>). If the workspace you leave was your default, your
          next-oldest remaining membership becomes your new default automatically.
        </p>
        <p>
          Ownership transfer hands the <code>OWNER</code> role to another existing member (they must
          already be a member; you cannot transfer to yourself). The previous owner is demoted to{' '}
          <code>ADMIN</code>, never removed.
        </p>
        <Callout variant="warn">
          The billing subscription stays with the previous owner&apos;s account after a transfer, it
          does not follow the role. Plan resolution for the workspace now points at the new owner, so
          if they have no subscription of their own, team features can drop back to the free tier
          until they do. Plan for this before transferring ownership of a paid workspace.
        </Callout>

        <h2>Per-member resource restrictions: DENY vs READ</h2>
        <p>
          Beyond roles, an <code>OWNER</code> or <code>ADMIN</code> can fine-tune what a specific{' '}
          <code>MEMBER</code> or <code>VIEWER</code> can reach, resource by resource. Each restriction
          is a tri-state per resource:
        </p>
        <DocsTable
          head={['Level', 'Effect']}
          rows={[
            ['Full access (no restriction)', 'The default, the member sees and can use the resource per their role.'],
            ['Read-only (READ)', 'The resource stays visible, but any write to it is blocked.'],
            ['No access (DENY)', 'The resource is hidden entirely, from lists and from direct access.'],
          ]}
        />
        <p>
          Restrictable resource types are workflows, applications, interfaces, agents, datasources,
          projects, files, and skills. Restrictions can be set one at a time or replaced in bulk for a
          member and resource type in one call. <code>OWNER</code> and <code>ADMIN</code> can never be
          restricted, whatever is set on them is ignored, they always see everything.
        </p>

        <h2>Member quotas within a team plan</h2>
        <p>
          On top of restrictions, an <code>OWNER</code> or <code>ADMIN</code> can cap how much a given
          member consumes, on up to three independent dimensions: tool credits, storage, and LLM
          tokens, each per billing period. Leaving a dimension unset means no cap on that dimension.
          Caps reset on the same monthly cycle as the workspace owner&apos;s billing, and there is one
          cap configuration per member (not per resource).
        </p>
        <Callout variant="info">
          A quota is enforced against whoever actually runs the work, not whoever&apos;s wallet pays
          for it, so redirecting a member&apos;s usage to be billed to the owner does not let them
          bypass their own cap. A quota cannot be set on the <code>OWNER</code>.
        </Callout>

        <h2>Organization audit log</h2>
        <p>
          <code>OWNER</code> and <code>ADMIN</code> can read a paginated, newest-first audit log of
          membership and workspace events, optionally filtered by event type. Each entry resolves the
          actor and target to display names.
        </p>
        <CodeBlock language="text">{`ORG_MEMBER_INVITED · ORG_INVITE_ACCEPTED · ORG_INVITE_CANCELLED · ORG_INVITE_RATE_LIMITED
ORG_MEMBER_REMOVED · ORG_MEMBER_LEFT · ORG_ROLE_CHANGED · ORG_OWNERSHIP_TRANSFERRED
ORG_DELETED · ORG_RESTORED · ORG_PURGED
ORG_QUOTA_CAP_SET · ORG_QUOTA_CAP_REMOVED · ORG_QUOTA_CAP_EXCEEDED
ORG_SAML_SSO_CONFIGURED · ORG_SAML_SSO_DELETED · ORG_SAML_SSO_MEMBER_JOINED`}</CodeBlock>
        <p>
          The audit trail survives a workspace&apos;s hard-purge (the purge itself is recorded as{' '}
          <code>ORG_PURGED</code>), so history stays queryable even after the underlying data is gone.
        </p>

        <h2>Workspace avatar</h2>
        <p>
          A workspace can carry one avatar image (JPEG, PNG, GIF, or WebP, up to 5 MB), uploaded or
          replaced by an <code>OWNER</code>/<code>ADMIN</code>. Removing it is idempotent and falls
          back to a deterministic initials image derived from the workspace name, so there is never a
          broken image, the fallback always renders something. The avatar is served without caching,
          so a fresh upload or a rename shows up immediately everywhere it&apos;s displayed.
        </p>

        <h2>Organization SAML SSO (Team / Enterprise)</h2>
        <p>
          A Team or Enterprise workspace can wire its own SAML identity provider, managed by an{' '}
          <code>OWNER</code>/<code>ADMIN</code>. You provide the IdP&apos;s entity ID, its SSO URL
          (HTTPS, other than localhost for testing), and its certificate, plus a display name and
          whether to hide the connection on the generic login page.
        </p>
        <DocsTable
          head={['Status', 'Meaning']}
          rows={[
            ['DRAFT', 'Saved but not yet provisioned.'],
            ['ACTIVE', 'Provisioned and ready, logins through this IdP work.'],
            ['ERROR', 'Provisioning failed, an error message explains why.'],
            ['DISABLED', 'Turned off without deleting the configuration.'],
          ]}
        />
        <p>
          Once <code>ACTIVE</code>, signing in through that IdP auto-provisions the user as a{' '}
          <code>MEMBER</code> the first time (just-in-time provisioning), still subject to the
          workspace&apos;s team support and member-limit checks. If the connection isn&apos;t active,
          or the workspace was deleted, the login is refused.
        </p>
        <Callout variant="warn">
          Organization SAML SSO requires the cloud&apos;s Keycloak-backed authentication. Self-hosted
          Community Edition&apos;s embedded email/password mode cannot provision it, a SAML connection
          configured there goes to <code>ERROR</code>. This is distinct from direct social login
          (Google/GitHub) and from an end-user&apos;s own OAuth2 connections (Gmail, Slack, ...), both
          of which do work in Community Edition, only org-level SAML SSO needs Keycloak.
        </Callout>

        <h2>Signing in: cloud vs self-hosted</h2>
        <p>
          The two editions authenticate differently, and that difference is exactly why SAML and
          invite-email delivery only fully work on one of them:
        </p>
        <DocsTable
          head={['Edition', 'Sign-in', 'Consequences']}
          rows={[
            [
              'Cloud',
              'Keycloak: SSO, organization SAML, and social login.',
              'Invite emails are sent by SMTP; switching workspaces has the 60 second cooldown described above.',
            ],
            [
              'Self-hosted (Community Edition)',
              'Embedded email/password.',
              'No SMTP: invites arrive as an in-app notification, with the raw accept token available to admins. No switch cooldown. Organization SAML SSO cannot be provisioned.',
            ],
          ]}
        />
        <p>
          Community Edition can still close public registration after setup, an invite link with a
          valid pending token remains a way in for that specific invitee even while registration is
          otherwise closed.
        </p>

        <h2>Where to go next</h2>
        <CardGrid cols={3}>
          <Card icon={Users} title="Chat" href="/chat">
            Conversations and agent chats live inside a workspace too.
          </Card>
          <Card icon={Workflow} title="Workflows" href="/workflows">
            The automations your members build, run, and share.
          </Card>
          <Card icon={Store} title="Marketplace" href="/marketplace">
            Publish from a workspace, or acquire into one.
          </Card>
        </CardGrid>
      </DocsProse>
    </>
  );
}
