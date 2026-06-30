/**
 * Active workspace store (PR0.5c).
 *
 * Holds the org the user is *currently working in*, separate from their
 * `defaultOrganizationId` on the backend. When the user switches workspace
 * via the topbar / sidebar / settings page, this store is updated and the
 * `apiClient` reads the value to send `X-Active-Organization-ID` on every
 * request. The gateway validates the claim against the user's memberships
 * (see `AuthenticationFilter.addAuthHeaders`) and injects the matching
 * `X-Organization-ID` / `X-Organization-Role` downstream.
 *
 * Persistence: localStorage key `lc.activeOrg` so the active workspace
 * survives page reload (matches the SaaS "workspace switcher" convention -
 * Slack, Notion, Linear all persist the last-active workspace).
 *
 * Hydration: on app boot, `smart-providers.tsx` reconciles the persisted
 * workspace against the fresh membership list, then falls back to the default
 * org when the persisted value is stale.
 */

import { create } from 'zustand';
import { persist, createJSONStorage, subscribeWithSelector } from 'zustand/middleware';

export type OrgRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER';

export interface CurrentOrgMembershipSnapshot {
  id: string;
  currentUserRole?: OrgRole | null;
  isDefault?: boolean;
  /**
   * Workspace the OWNER soft-deleted that is still in its grace window. It keeps
   * appearing in `/organizations/me` (owner-only) so the Restore UI can list it,
   * but it is NOT enterable - the gateway rejects active-org claims for it. The
   * reconciler must never keep/promote it as the active workspace, otherwise the
   * user is stranded on a deleted workspace (prod bug 2026-06-06).
   */
  pendingDeletion?: boolean;
  /** Dormant team org (owner downgraded below team): visible but not enterable. */
  paused?: boolean;
}

interface CurrentOrgState {
  currentOrgId: string | null;
  currentOrgRole: OrgRole | null;
  setCurrentOrg: (orgId: string, role: OrgRole) => void;
  clear: () => void;
}

export const useCurrentOrgStore = create<CurrentOrgState>()(
  subscribeWithSelector(
    persist(
      (set) => ({
        currentOrgId: null,
        currentOrgRole: null,
        setCurrentOrg: (orgId, role) => set({ currentOrgId: orgId, currentOrgRole: role }),
        clear: () => set({ currentOrgId: null, currentOrgRole: null }),
      }),
      {
        name: 'lc.activeOrg',
        storage: typeof window !== 'undefined' ? createJSONStorage(() => localStorage) : undefined,
      }
    )
  )
);

/**
 * Non-reactive accessor used by `apiClient` to read the active org on every
 * request without subscribing to the store (the api client lives outside the
 * React tree). Reading `getState()` is cheap and stable.
 */
export function getActiveOrgIdForRequest(): string | null {
  return useCurrentOrgStore.getState().currentOrgId;
}

/**
 * Audit 2026-05-17 round-3 - convenience helper for raw `fetch()` sites
 * that bypass apiClient (binary downloads, streaming, multipart). Returns
 * a partial headers object so callers can spread it into their request.
 *
 * Usage:
 *   const headers = { ...getActiveOrgHeaderForRequest(), Authorization: `Bearer ${t}` };
 */
export function getActiveOrgHeaderForRequest(): Record<string, string> {
  const id = getActiveOrgIdForRequest();
  return id ? { 'X-Active-Organization-ID': id } : {};
}

/**
 * Per-request override of the active workspace for ONE apiClient call.
 *
 * <p>Used by the Quota / Storage workspace filters to scope a single request to a
 * workspace OTHER than the globally-active one, WITHOUT switching the whole app.
 * The apiClient gives a caller-supplied `X-Active-Organization-ID` precedence over
 * the global active-org provider (see `executeFetch` in `api-client.ts`), so
 * spreading the returned object into a request's options re-scopes just that call.
 *
 * <p>The gateway still validates the claim against the user's memberships, so an
 * org the user does not belong to is rejected server-side - this is a UX
 * affordance, not a trust boundary.
 *
 * <p>Returns {@code undefined} when {@code orgId} is falsy so the call falls back
 * to the global active workspace (unchanged behaviour). The helper returns the
 * full options shape ({@code { headers }}) rather than a bare header map so
 * callers can branch on a single value, e.g.
 * {@code apiClient.get(path, orgScopeRequestOptions(orgId))} or merge it with
 * params: {@code apiClient.get(path, { params, ...(orgScopeRequestOptions(orgId) ?? {}) })}.
 */
export function orgScopeRequestOptions(
  orgId?: string | null,
): { headers: Record<string, string> } | undefined {
  return orgId ? { headers: { 'X-Active-Organization-ID': orgId } } : undefined;
}

/**
 * Reconcile the persisted active workspace with the fresh membership list
 * returned by auth-service. This is the bootstrap safety net for:
 * - user signed out in one account and signed in as another;
 * - membership revoked while the tab was closed;
 * - role changed while localStorage still contains the previous role.
 *
 * The gateway validates X-Active-Organization-ID too, but it falls back to
 * the default org when the claim is stale. Keeping the frontend store aligned
 * prevents the UI from showing a workspace/role different from the request
 * context that backend services actually use.
 */
export function reconcileCurrentOrgFromMemberships(
  memberships: CurrentOrgMembershipSnapshot[] | null | undefined,
  fallbackOrg?: CurrentOrgMembershipSnapshot | null,
): void {
  const state = useCurrentOrgStore.getState();
  // "Enterable" = a real membership the user can actually work in. A soft-deleted
  // (pendingDeletion) or paused org still shows up in /organizations/me but cannot
  // be the active workspace - the gateway rejects active-org claims for it. We
  // both KEEP and FALL BACK to enterable orgs only, so the user is never pinned to
  // a workspace whose requests silently resolve elsewhere (prod bug 2026-06-06).
  const enterable = (memberships ?? []).filter(
    (membership): membership is CurrentOrgMembershipSnapshot & { currentUserRole: OrgRole } =>
      !!membership?.id && !!membership.currentUserRole
      && !membership.pendingDeletion && !membership.paused,
  );

  const current = state.currentOrgId
    ? enterable.find((membership) => membership.id === state.currentOrgId)
    : null;

  if (current) {
    if (state.currentOrgRole !== current.currentUserRole) {
      state.setCurrentOrg(current.id, current.currentUserRole);
    }
    return;
  }

  const fallbackIsEnterable =
    !!fallbackOrg?.id && !!fallbackOrg.currentUserRole
    && !fallbackOrg.pendingDeletion && !fallbackOrg.paused;
  const fallback = (
    fallbackIsEnterable
      ? fallbackOrg as CurrentOrgMembershipSnapshot & { currentUserRole: OrgRole }
      : null
  ) ?? enterable.find((membership) => membership.isDefault)
    ?? enterable[0]
    ?? null;

  if (fallback) {
    state.setCurrentOrg(fallback.id, fallback.currentUserRole);
  } else {
    state.clear();
  }
}

/**
 * Hook-like wrapper for use in React components. Returns the active org
 * tuple - null when the store is empty (pre-hydration or signed-out).
 */
export function useCurrentOrg() {
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const currentOrgRole = useCurrentOrgStore((s) => s.currentOrgRole);
  const setCurrentOrg = useCurrentOrgStore((s) => s.setCurrentOrg);
  const clear = useCurrentOrgStore((s) => s.clear);
  return { currentOrgId, currentOrgRole, setCurrentOrg, clear };
}

/**
 * Returns true if the current user is OWNER of the active workspace.
 * Used by billing surfaces to grey payment CTAs (PR5 of the redesign).
 */
export function useIsCurrentOrgOwner(): boolean {
  return useCurrentOrgStore((s) => s.currentOrgRole === 'OWNER');
}

function readPersistedCurrentOrgRole(): { currentOrgId: string; currentOrgRole: OrgRole } | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem('lc.activeOrg');
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    const state = parsed?.state ?? parsed;
    const currentOrgId = state?.currentOrgId;
    const currentOrgRole = state?.currentOrgRole;
    if (
      typeof currentOrgId === 'string'
      && currentOrgId.length > 0
      && ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'].includes(currentOrgRole)
    ) {
      return { currentOrgId, currentOrgRole };
    }
  } catch {
    // Malformed persistence should not block personal-workspace mutations.
  }
  return null;
}

/**
 * Audit 2026-05-17 round-3 - destructive-action role gate.
 * VIEWER role cannot mutate anything in the workspace. Components
 * showing delete/edit buttons in a workspace context should hide or
 * disable them when this returns false. MEMBER+ can mutate.
 */
export function useCanMutateInCurrentOrg(): boolean {
  return useCurrentOrgStore((s) => {
    const persisted = s.currentOrgId === null && s.currentOrgRole === null
      ? readPersistedCurrentOrgRole()
      : null;
    if (persisted) {
      return persisted.currentOrgRole !== 'VIEWER';
    }
    // Personal workspace (no org) - always allowed (user owns own resources).
    if (s.currentOrgId === null) return true;
    // Org workspace - block VIEWER, allow OWNER / ADMIN / MEMBER.
    return s.currentOrgRole !== null && s.currentOrgRole !== 'VIEWER';
  });
}

/**
 * Audit 2026-05-17 round-3 - cross-tab workspace-switch broadcast.
 * Zustand `persist` writes to localStorage on every set, so listening
 * for `storage` events lets sibling tabs see workspace changes made in
 * another tab. On a foreign-tab switch, re-hydrate the store from the
 * fresh localStorage value and force a soft reload to flush stale
 * react-query caches that don't yet key on currentOrgId.
 *
 * Call this ONCE from the root layout (smart-providers).
 */
export function subscribeCrossTabOrgSwitch(): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (e: StorageEvent) => {
    if (e.key !== 'lc.activeOrg' || e.newValue === e.oldValue) return;
    try {
      const parsed = e.newValue ? JSON.parse(e.newValue) : null;
      const next = parsed?.state ?? parsed ?? null;
      if (!next || typeof next !== 'object') return;
      const nextOrgId: string | null = next.currentOrgId ?? null;
      const nextRole: OrgRole | null = next.currentOrgRole ?? null;
      const cur = useCurrentOrgStore.getState();
      if (cur.currentOrgId === nextOrgId && cur.currentOrgRole === nextRole) return;
      // Mirror the foreign tab's state so the current tab's stores are
      // coherent on the next render. Caller should also invalidate
      // react-query caches (see smart-providers wiring).
      if (nextOrgId && nextRole) {
        cur.setCurrentOrg(nextOrgId, nextRole);
      } else {
        cur.clear();
      }
    } catch {
      // localStorage value malformed - ignore (no rollback needed,
      // local state stays consistent).
    }
  };
  window.addEventListener('storage', handler);
  return () => window.removeEventListener('storage', handler);
}
