// @vitest-environment jsdom
/**
 * Single source of truth for plan-tier workspace/team entitlements, shared by the
 * sidebar workspace switcher and the organization settings Workspaces tab. These pin:
 * - additional workspaces unlock at PRO+ (PRO/TEAM/ENTERPRISE), team invites at TEAM+,
 * - cloud resolves from the active-workspace tier (falling back to the personal plan),
 * - a CE install follows the GOVERNING cloud plan of its linked account (not the local one),
 * - an unlinked CE has no entitlement.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

const editionState = vi.hoisted(() => ({ isCe: false }));
const subState = vi.hoisted(() => ({ subscription: undefined as unknown }));
const ceState = vi.hoisted(() => ({ status: null as unknown }));

vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return editionState.isCe;
  },
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({ subscription: subState.subscription }),
}));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: ceState.status, isLoading: false, isCloudLinked: false }),
}));

import { useWorkspaceEntitlements } from '../useWorkspaceEntitlements';

beforeEach(() => {
  editionState.isCe = false;
  subState.subscription = undefined;
  ceState.status = null;
});

describe('useWorkspaceEntitlements', () => {
  // ── Cloud (IS_CE=false): entitlements follow the active-org / personal subscription plan ──
  it('FREE → cannot create a workspace and cannot invite teammates', () => {
    subState.subscription = { subscription: { planCode: 'FREE' } };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBe('FREE');
    expect(result.current.canCreateWorkspace).toBe(false);
    expect(result.current.canInviteTeammates).toBe(false);
  });

  it('STARTER → cannot create a workspace and cannot invite teammates (only the personal one)', () => {
    subState.subscription = { subscription: { planCode: 'STARTER' } };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBe('STARTER');
    expect(result.current.canCreateWorkspace).toBe(false);
    expect(result.current.canInviteTeammates).toBe(false);
  });

  it('PRO → can create a workspace but cannot invite teammates (workspaces are PRO+, invites TEAM+)', () => {
    subState.subscription = { subscription: { planCode: 'PRO' } };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.canCreateWorkspace).toBe(true);
    expect(result.current.canInviteTeammates).toBe(false);
  });

  it('TEAM → can create a workspace AND invite teammates', () => {
    subState.subscription = { subscription: { planCode: 'TEAM' } };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.canCreateWorkspace).toBe(true);
    expect(result.current.canInviteTeammates).toBe(true);
  });

  it('ENTERPRISE_* → both entitlements (prefix match)', () => {
    subState.subscription = { subscription: { planCode: 'ENTERPRISE_SCALE' } };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.canCreateWorkspace).toBe(true);
    expect(result.current.canInviteTeammates).toBe(true);
  });

  it('prefers the active-workspace tier over the personal subscription plan', () => {
    subState.subscription = { activeOrgPlanCode: 'PRO', subscription: { planCode: 'FREE' } };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBe('PRO');
    expect(result.current.canCreateWorkspace).toBe(true);
  });

  it('no subscription data → null plan, no entitlement', () => {
    subState.subscription = undefined;
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBeNull();
    expect(result.current.canCreateWorkspace).toBe(false);
  });

  // ── CE (IS_CE=true): entitlements follow the GOVERNING cloud plan of the linked account ──
  it('CE: uses the linked cloud plan (cloudPlanCode), ignoring the local subscription', () => {
    editionState.isCe = true;
    subState.subscription = { subscription: { planCode: 'TEAM' } }; // local plan - must be ignored
    ceState.status = { linked: true, cloudPlanCode: 'FREE' };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBe('FREE');
    expect(result.current.canCreateWorkspace).toBe(false);
  });

  it('CE: a PRO cloud plan unlocks workspace creation (but not team invites)', () => {
    editionState.isCe = true;
    ceState.status = { linked: true, cloudPlanCode: 'PRO' };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.canCreateWorkspace).toBe(true);
    expect(result.current.canInviteTeammates).toBe(false);
  });

  it('CE: a TEAM cloud plan unlocks both workspace creation and team invites', () => {
    editionState.isCe = true;
    ceState.status = { linked: true, cloudPlanCode: 'TEAM' };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.canCreateWorkspace).toBe(true);
    expect(result.current.canInviteTeammates).toBe(true);
  });

  it('CE unlinked (no cloud plan) → null plan, no entitlement', () => {
    editionState.isCe = true;
    ceState.status = null;
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBeNull();
    expect(result.current.canCreateWorkspace).toBe(false);
  });

  it('CE inheriting member: install plan shows for DISPLAY but capability stays FREE (no create/invite)', () => {
    // Member: per-user link is false (no cloudPlanCode); they only inherit the admin's install link.
    // The install plan drives the DISPLAY badge (effectivePlanCode = TEAM, so they see "CE Team" and
    // the cloud catalog), but it must NOT grant team/workspace CAPABILITY they never paid for: the
    // member stays FREE (canCreateWorkspace / canInviteTeammates = false), matching the backend
    // (invite -> 403, create-workspace -> 403). Only a per-user cloud plan unlocks those.
    editionState.isCe = true;
    ceState.status = { linked: false, installLinked: true, installCloudPlanCode: 'TEAM' };
    const { result } = renderHook(() => useWorkspaceEntitlements());
    expect(result.current.effectivePlanCode).toBe('TEAM');
    expect(result.current.canCreateWorkspace).toBe(false);
    expect(result.current.canInviteTeammates).toBe(false);
  });
});
