'use client';

import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { IS_CE } from '@/lib/edition';

export interface WorkspaceEntitlements {
  /**
   * The plan tier that governs entitlements. Cloud: the active-workspace tier
   * (activeOrgPlanCode, set by auth-service from the gateway-resolved X-User-Plan
   * header) with the per-user subscription plan as fallback. CE: the GOVERNING
   * cloud plan of the linked account (null when unlinked). null = unknown / FREE.
   */
  effectivePlanCode: string | null;
  /**
   * Additional workspaces are a PRO+ entitlement (shared-wallet model). FREE/STARTER
   * own only their personal workspace. The backend enforces the exact per-plan cap
   * (PRO=3, TEAM=10, ENTERPRISE=unlimited); this only decides whether to surface the
   * "create / upgrade for more workspaces" affordance.
   */
  canCreateWorkspace: boolean;
  /**
   * Team collaboration (invite members, assign roles, share a credit pool) is the
   * value of TEAM / ENTERPRISE. FREE / STARTER / PRO have no member-management surface.
   */
  canInviteTeammates: boolean;
}

/**
 * Plan-tier workspace/team entitlements for a component that resolves the effective
 * plan itself (rather than receiving it as a prop). Resolves the same effective plan
 * in both editions - cloud-governed in a linked CE - and reads from react-query's
 * shared caches, so it adds no network traffic over what the app already fetches.
 *
 * Used by the organization settings page. NOTE: {@code AppSidebar}'s UserSection is
 * deliberately NOT a consumer - it is presentational and receives {@code planCode} as
 * a prop from its parent, deriving the SAME `canCreateWorkspace` / `canInviteTeammates`
 * booleans inline. Keep the two tier rules (PRO+ → workspace, TEAM+ → invite) in sync.
 */
export function useWorkspaceEntitlements(): WorkspaceEntitlements {
  const { subscription } = useSubscription();
  const { status: ceLinkStatus } = useCeCloudLinkStatus();

  const activeOrgPlanCode = (subscription as any)?.activeOrgPlanCode || null;
  const personalPlanCode = (subscription as any)?.subscription?.planCode || null;
  const planCode = activeOrgPlanCode || personalPlanCode;
  // DISPLAY plan: a non-owner member who inherits the admin's install link has no per-user
  // cloudPlanCode, so fall back to the install-link plan for VISIBILITY (the "CE <plan>" badge,
  // cloud catalog access). This is intentionally permissive - it is NOT an entitlement.
  const effectivePlanCode = IS_CE
    ? (ceLinkStatus?.cloudPlanCode ?? ceLinkStatus?.installCloudPlanCode ?? null)
    : planCode;
  // CAPABILITY plan: what THIS user actually paid for. The install-link fallback is visibility-only
  // and must NOT grant team/workspace capability a member never paid for. A member who merely
  // inherits the admin's install link has no per-user cloudPlanCode, so they resolve to FREE here
  // and stay FREE in their own workspace (the backend enforces the same: invite -> 403, create
  // workspace -> 403 "Workspace limit reached"). Only a per-user cloud plan unlocks team capability.
  const capabilityPlanCode = IS_CE ? (ceLinkStatus?.cloudPlanCode ?? null) : planCode;

  const canCreateWorkspace =
    capabilityPlanCode === 'PRO' ||
    capabilityPlanCode === 'TEAM' ||
    (capabilityPlanCode?.startsWith('ENTERPRISE') ?? false);
  const canInviteTeammates =
    capabilityPlanCode === 'TEAM' || (capabilityPlanCode?.startsWith('ENTERPRISE') ?? false);

  return { effectivePlanCode, canCreateWorkspace, canInviteTeammates };
}
