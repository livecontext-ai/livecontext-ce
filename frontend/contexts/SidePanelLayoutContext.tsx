'use client';

/**
 * SidePanelLayoutContext - user preference for WHERE the unified side panel docks.
 *
 * Orthogonal to SidePanelContext (which owns tabs/open state): this only holds the
 * dock POSITION so the layout, the header toggle icon, and the Settings selector all
 * agree. It is a purely-visual client preference (like the theme), so it lives in
 * localStorage - no backend round-trip.
 *
 *  - 'right'  (default): the panel is a row sibling of the main content and resizes
 *             by WIDTH (historical behavior).
 *  - 'bottom': the panel docks under the main content, spans the full width, and
 *             resizes by HEIGHT. The main content shrinks vertically to make room.
 *
 * ORG-AWARE: the choice is scoped per active workspace (per-(user, org), like the
 * chat defaults). Each workspace remembers its own dock position and switching the
 * active org re-hydrates the value - the layout in Org A never bleeds into Org B.
 * The active workspace comes from {@link useCurrentOrg} (localStorage `lc.activeOrg`);
 * the personal workspace (no org) uses the `personal` bucket.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

export type SidePanelPosition = 'right' | 'bottom';

export const DEFAULT_SIDE_PANEL_POSITION: SidePanelPosition = 'right';

interface SidePanelLayoutContextValue {
  position: SidePanelPosition;
  setPosition: (position: SidePanelPosition) => void;
}

const SidePanelLayoutContext = createContext<SidePanelLayoutContextValue | null>(null);

const STORAGE_PREFIX = 'lc.sidePanel.position';

export function isSidePanelPosition(value: string | null | undefined): value is SidePanelPosition {
  return value === 'right' || value === 'bottom';
}

/** localStorage key for a given workspace (null org = personal workspace). */
function storageKey(orgId: string | null | undefined): string {
  return `${STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function readStoredPosition(orgId: string | null | undefined): SidePanelPosition {
  if (typeof window === 'undefined') return DEFAULT_SIDE_PANEL_POSITION;
  try {
    const saved = window.localStorage.getItem(storageKey(orgId));
    return isSidePanelPosition(saved) ? saved : DEFAULT_SIDE_PANEL_POSITION;
  } catch {
    return DEFAULT_SIDE_PANEL_POSITION;
  }
}

export function SidePanelLayoutProvider({ children }: { children: React.ReactNode }) {
  // Active workspace - drives which per-org value we read/write.
  const { currentOrgId } = useCurrentOrg();

  // Start with the default so SSR and the first client render agree (no hydration
  // mismatch); the effect below restores the saved value for the active workspace.
  const [position, setPositionState] = useState<SidePanelPosition>(DEFAULT_SIDE_PANEL_POSITION);

  // Re-hydrate whenever the active workspace changes (initial mount included, once
  // currentOrgId resolves post-hydration). Deliberately an effect, not a render-time
  // derivation: reading localStorage during render would diverge from the SSR default
  // and trip a hydration mismatch (same reason ThemeProvider restores in an effect).
  useEffect(() => {
    // eslint-disable-next-line react-hooks/no-deriving-state-in-effects, react-hooks/set-state-in-effect
    setPositionState(readStoredPosition(currentOrgId));
  }, [currentOrgId]);

  const setPosition = useCallback((next: SidePanelPosition) => {
    setPositionState(next);
    try {
      window.localStorage.setItem(storageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable (private mode / SSR) - keep the in-memory value */
    }
  }, [currentOrgId]);

  const value = useMemo<SidePanelLayoutContextValue>(
    () => ({ position, setPosition }),
    [position, setPosition],
  );

  return (
    <SidePanelLayoutContext.Provider value={value}>
      {children}
    </SidePanelLayoutContext.Provider>
  );
}

/** Throws if used outside SidePanelLayoutProvider. */
export function useSidePanelLayout(): SidePanelLayoutContextValue {
  const ctx = useContext(SidePanelLayoutContext);
  if (!ctx) throw new Error('useSidePanelLayout must be used within SidePanelLayoutProvider');
  return ctx;
}

/**
 * Safe variant - returns the default 'right' layout (and a no-op setter) when used
 * outside a provider (e.g. shared-conversation embeds, which always dock right).
 */
export function useSidePanelLayoutSafe(): SidePanelLayoutContextValue {
  const ctx = useContext(SidePanelLayoutContext);
  return ctx ?? { position: DEFAULT_SIDE_PANEL_POSITION, setPosition: () => {} };
}
