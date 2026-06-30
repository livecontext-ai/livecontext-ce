// @vitest-environment jsdom

/**
 * PR0.5c regression - pin the active workspace store contract.
 *
 * The store value is read by `apiClient` on every request via
 * `getActiveOrgIdForRequest()`. Drift here = silent loss of active-workspace
 * semantics → gateway falls back to default org → user sees wrong resources.
 */

import { renderHook } from '@testing-library/react';
import { describe, it, expect, beforeEach } from 'vitest';
import {
  useCurrentOrgStore,
  useCanMutateInCurrentOrg,
  getActiveOrgIdForRequest,
  reconcileCurrentOrgFromMemberships,
} from '../current-org-store';

beforeEach(() => {
  useCurrentOrgStore.setState({ currentOrgId: null, currentOrgRole: null });
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem('lc.activeOrg');
  }
});

describe('useCurrentOrgStore (PR0.5c active workspace store)', () => {
  it('starts empty (null/null) - gateway falls back to defaultOrganizationId', () => {
    expect(useCurrentOrgStore.getState().currentOrgId).toBeNull();
    expect(useCurrentOrgStore.getState().currentOrgRole).toBeNull();
  });

  it('setCurrentOrg stores both orgId and role atomically', () => {
    useCurrentOrgStore.getState().setCurrentOrg('team-uuid', 'ADMIN');

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('team-uuid');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('ADMIN');
  });

  it('clear resets both fields - used on signout', () => {
    useCurrentOrgStore.getState().setCurrentOrg('team-uuid', 'ADMIN');
    useCurrentOrgStore.getState().clear();

    expect(useCurrentOrgStore.getState().currentOrgId).toBeNull();
    expect(useCurrentOrgStore.getState().currentOrgRole).toBeNull();
  });

  it('switching workspaces overwrites both fields (no stale role from previous org)', () => {
    useCurrentOrgStore.getState().setCurrentOrg('team-uuid', 'ADMIN');
    useCurrentOrgStore.getState().setCurrentOrg('personal-uuid', 'OWNER');

    // CRITICAL: role MUST update with orgId - otherwise OWNER privileges
    // could leak across workspaces, or vice versa.
    expect(useCurrentOrgStore.getState().currentOrgId).toBe('personal-uuid');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('OWNER');
  });
});

describe('getActiveOrgIdForRequest (apiClient bridge)', () => {
  it('returns null when store is empty (signed out / pre-hydration)', () => {
    expect(getActiveOrgIdForRequest()).toBeNull();
  });

  it('returns the current orgId - used by apiClient to set X-Active-Organization-ID', () => {
    useCurrentOrgStore.getState().setCurrentOrg('active-org-uuid', 'MEMBER');

    expect(getActiveOrgIdForRequest()).toBe('active-org-uuid');
  });

  it('is non-reactive - does not subscribe (apiClient lives outside React)', () => {
    // Just exercising the API shape - Zustand getState() is synchronous and
    // cheap. Any future refactor that makes this async breaks the apiClient
    // contract (every request would need to await).
    const result = getActiveOrgIdForRequest();
    expect(typeof result === 'string' || result === null).toBe(true);
  });
});

describe('useCanMutateInCurrentOrg (viewer mutation gate)', () => {
  it('allows personal workspace mutations when the store is empty', () => {
    const { result } = renderHook(() => useCanMutateInCurrentOrg());

    expect(result.current).toBe(true);
  });

  it('blocks immediately from persisted VIEWER role before store hydration', () => {
    localStorage.setItem('lc.activeOrg', JSON.stringify({
      state: { currentOrgId: 'viewer-org', currentOrgRole: 'VIEWER' },
      version: 0,
    }));

    const { result } = renderHook(() => useCanMutateInCurrentOrg());

    expect(result.current).toBe(false);
  });

  it('allows persisted MEMBER role before store hydration', () => {
    localStorage.setItem('lc.activeOrg', JSON.stringify({
      state: { currentOrgId: 'member-org', currentOrgRole: 'MEMBER' },
      version: 0,
    }));

    const { result } = renderHook(() => useCanMutateInCurrentOrg());

    expect(result.current).toBe(true);
  });
});

describe('reconcileCurrentOrgFromMemberships (bootstrap safety)', () => {
  it('hydrates the default membership when the store is empty', () => {
    reconcileCurrentOrgFromMemberships([
      { id: 'personal-org', currentUserRole: 'OWNER', isDefault: true },
      { id: 'team-org', currentUserRole: 'MEMBER', isDefault: false },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('personal-org');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('OWNER');
  });

  it('keeps a valid persisted workspace and refreshes its role', () => {
    useCurrentOrgStore.getState().setCurrentOrg('team-org', 'MEMBER');

    reconcileCurrentOrgFromMemberships([
      { id: 'personal-org', currentUserRole: 'OWNER', isDefault: true },
      { id: 'team-org', currentUserRole: 'ADMIN', isDefault: false },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('team-org');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('ADMIN');
  });

  it('replaces a stale persisted workspace with the default membership', () => {
    useCurrentOrgStore.getState().setCurrentOrg('revoked-org', 'OWNER');

    reconcileCurrentOrgFromMemberships([
      { id: 'personal-org', currentUserRole: 'OWNER', isDefault: true },
      { id: 'team-org', currentUserRole: 'MEMBER', isDefault: false },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('personal-org');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('OWNER');
  });

  it('clears the store when no membership is available', () => {
    useCurrentOrgStore.getState().setCurrentOrg('revoked-org', 'ADMIN');

    reconcileCurrentOrgFromMemberships([]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBeNull();
    expect(useCurrentOrgStore.getState().currentOrgRole).toBeNull();
  });

  // ── Regression: workspace-delete stranded the user on the deleted org ──────
  // Repro of the prod bug (2026-06-06): the user deleted the workspace they were
  // *currently in*. /organizations/me keeps returning that org (owner-only, with
  // `pendingDeletion: true`) so the Restore UI can show it - but the org is no
  // longer enterable. The old reconcile saw a valid id+role and KEPT it as the
  // active workspace, so every request still sent X-Active-Organization-ID for
  // the deleted org and the user was stuck on an empty workspace, unable to get
  // back to their main one. Reconcile must evict a non-enterable current org.
  it('evicts a soft-deleted (pendingDeletion) current workspace and falls back to the default', () => {
    useCurrentOrgStore.getState().setCurrentOrg('team-org', 'OWNER');

    reconcileCurrentOrgFromMemberships([
      { id: 'personal-org', currentUserRole: 'OWNER', isDefault: true },
      { id: 'team-org', currentUserRole: 'OWNER', isDefault: false, pendingDeletion: true },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('personal-org');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('OWNER');
  });

  it('evicts a paused current workspace and falls back to the default', () => {
    useCurrentOrgStore.getState().setCurrentOrg('team-org', 'MEMBER');

    reconcileCurrentOrgFromMemberships([
      { id: 'personal-org', currentUserRole: 'OWNER', isDefault: true },
      { id: 'team-org', currentUserRole: 'MEMBER', isDefault: false, paused: true },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('personal-org');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('OWNER');
  });

  it('never promotes a soft-deleted org as the fallback, even when it is still flagged default', () => {
    // Store empty (post-signout). The soft-deleted org is even marked isDefault
    // (e.g. backend promotion lagged) - the OLD fallback picked the default blindly
    // and would have landed the user on the deleted org. The enterable workspace
    // must win instead. (Distinguishing test: fails on pre-fix code.)
    reconcileCurrentOrgFromMemberships([
      { id: 'deleted-org', currentUserRole: 'OWNER', isDefault: true, pendingDeletion: true },
      { id: 'paused-org', currentUserRole: 'MEMBER', isDefault: false, paused: true },
      { id: 'live-org', currentUserRole: 'OWNER', isDefault: false },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('live-org');
  });

  it('clears the store when EVERY membership is unenterable (deleted/paused)', () => {
    // Degenerate state: nothing is enterable. Must clear (gateway then resolves the
    // user's own personal workspace) rather than pin the user to a deleted default.
    useCurrentOrgStore.getState().setCurrentOrg('whatever', 'OWNER');

    reconcileCurrentOrgFromMemberships([
      { id: 'deleted-org', currentUserRole: 'OWNER', isDefault: true, pendingDeletion: true },
      { id: 'paused-org', currentUserRole: 'MEMBER', isDefault: false, paused: true },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBeNull();
  });

  it('ignores a non-enterable fallbackOrg hint (single-org /organizations/current path)', () => {
    // The /organizations/current boot path passes the single current org as the
    // fallbackOrg hint. If that org is itself pending deletion, it must not be
    // adopted as the active workspace.
    reconcileCurrentOrgFromMemberships(
      [],
      { id: 'deleted-current', currentUserRole: 'OWNER', pendingDeletion: true },
    );

    expect(useCurrentOrgStore.getState().currentOrgId).toBeNull();
  });

  it('keeps an enterable non-default current workspace untouched (no false eviction)', () => {
    // Guard against over-eager eviction: a normal workspace the user is browsing
    // must NOT be swapped out just because it is not the default.
    useCurrentOrgStore.getState().setCurrentOrg('team-org', 'ADMIN');

    reconcileCurrentOrgFromMemberships([
      { id: 'personal-org', currentUserRole: 'OWNER', isDefault: true },
      { id: 'team-org', currentUserRole: 'ADMIN', isDefault: false },
    ]);

    expect(useCurrentOrgStore.getState().currentOrgId).toBe('team-org');
    expect(useCurrentOrgStore.getState().currentOrgRole).toBe('ADMIN');
  });
});
