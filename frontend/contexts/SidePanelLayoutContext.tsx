'use client';

/**
 * SidePanelLayoutContext - layout state for WHERE the unified side panel docks.
 *
 * Orthogonal to SidePanelContext (which owns tabs/open state): this only holds the
 * dock POSITION so the layout, the header dock buttons, and the Settings selector
 * all agree. It is a purely-visual client preference (like the theme), so it lives
 * in localStorage - no backend round-trip.
 *
 * Three pieces of state:
 *  - `position` - the ACTIVE dock, driven by the header's two dock buttons:
 *      'right'       (default): the panel is a row sibling of the main content and
 *                    resizes by WIDTH (historical behavior).
 *      'bottom'      : the panel docks under the main content, content-width, and
 *                    resizes by HEIGHT. The main content shrinks vertically.
 *      'bottom-full' : same, but spanning the FULL viewport width (under the
 *                    sidebar too); the sidebar shrinks vertically above it.
 *  - `defaultPosition` - the user PREFERENCE (Settings > Preferences) for where the
 *      panel opens BY DEFAULT across the app: 'right' | 'bottom'. It seeds `position`
 *      on every mount (a 'bottom' default resolves through `bottomMode` to its
 *      variant), so the whole app opens the panel where the user chose. The two
 *      header dock buttons still override the ACTIVE dock live for that session.
 *      When unset (never picked), we fall back to the last-used `position` so
 *      existing users keep their sticky behavior.
 *  - `bottomMode` - the user PREFERENCE (Settings > Preferences) for which bottom
 *      variant the header's bottom-dock button opens: 'bottom' | 'bottom-full'.
 *      Defaults to 'bottom-full'. Changing it while a bottom dock is active
 *      repositions the open panel immediately (WYSIWYG).
 *
 * ORG-AWARE: all values are scoped per active workspace (per-(user, org), like the
 * chat defaults). Each workspace remembers its own layout and switching the active
 * org re-hydrates the values - the layout in Org A never bleeds into Org B. The
 * active workspace comes from {@link useCurrentOrg} (localStorage `lc.activeOrg`);
 * the personal workspace (no org) uses the `personal` bucket.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useCurrentOrg } from '@/lib/stores/current-org-store';

export type SidePanelPosition = 'right' | 'bottom' | 'bottom-full';
export type SidePanelBottomMode = 'bottom' | 'bottom-full';
/** The default-opening preference: only the two docks the user picks between. */
export type SidePanelDefaultPosition = 'right' | 'bottom';

export const DEFAULT_SIDE_PANEL_POSITION: SidePanelPosition = 'right';
export const DEFAULT_SIDE_PANEL_BOTTOM_MODE: SidePanelBottomMode = 'bottom-full';
export const DEFAULT_SIDE_PANEL_DEFAULT_POSITION: SidePanelDefaultPosition = 'right';

interface SidePanelLayoutContextValue {
  position: SidePanelPosition;
  setPosition: (position: SidePanelPosition) => void;
  defaultPosition: SidePanelDefaultPosition;
  setDefaultPosition: (position: SidePanelDefaultPosition) => void;
  bottomMode: SidePanelBottomMode;
  setBottomMode: (mode: SidePanelBottomMode) => void;
}

const SidePanelLayoutContext = createContext<SidePanelLayoutContextValue | null>(null);

const STORAGE_PREFIX = 'lc.sidePanel.position';
const DEFAULT_POSITION_STORAGE_PREFIX = 'lc.sidePanel.defaultPosition';
const BOTTOM_MODE_STORAGE_PREFIX = 'lc.sidePanel.bottomMode';

export function isSidePanelPosition(value: string | null | undefined): value is SidePanelPosition {
  return value === 'right' || value === 'bottom' || value === 'bottom-full';
}

export function isSidePanelBottomMode(value: string | null | undefined): value is SidePanelBottomMode {
  return value === 'bottom' || value === 'bottom-full';
}

export function isSidePanelDefaultPosition(value: string | null | undefined): value is SidePanelDefaultPosition {
  return value === 'right' || value === 'bottom';
}

/** localStorage key for a given workspace (null org = personal workspace). */
function storageKey(orgId: string | null | undefined): string {
  return `${STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function defaultPositionStorageKey(orgId: string | null | undefined): string {
  return `${DEFAULT_POSITION_STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

function bottomModeStorageKey(orgId: string | null | undefined): string {
  return `${BOTTOM_MODE_STORAGE_PREFIX}:${orgId ?? 'personal'}`;
}

/**
 * The stored default-opening preference for a workspace, or `null` when the user
 * has never picked one (so callers can fall back to the last-used position).
 */
function readStoredDefaultPosition(orgId: string | null | undefined): SidePanelDefaultPosition | null {
  if (typeof window === 'undefined') return null;
  try {
    const saved = window.localStorage.getItem(defaultPositionStorageKey(orgId));
    return isSidePanelDefaultPosition(saved) ? saved : null;
  } catch {
    return null;
  }
}

function readStoredBottomMode(orgId: string | null | undefined): SidePanelBottomMode {
  if (typeof window === 'undefined') return DEFAULT_SIDE_PANEL_BOTTOM_MODE;
  try {
    const saved = window.localStorage.getItem(bottomModeStorageKey(orgId));
    if (isSidePanelBottomMode(saved)) return saved;
    // Legacy seed: before bottomMode existed, the dock position select stored the
    // chosen bottom variant in the position bucket. Honor it so a user who picked
    // 'bottom' (content width) keeps that variant on the new bottom button.
    const savedPosition = window.localStorage.getItem(storageKey(orgId));
    if (isSidePanelBottomMode(savedPosition)) return savedPosition;
    return DEFAULT_SIDE_PANEL_BOTTOM_MODE;
  } catch {
    return DEFAULT_SIDE_PANEL_BOTTOM_MODE;
  }
}

/** Resolve a default-opening preference to a concrete active dock. */
function resolveDefaultToPosition(
  defaultPosition: SidePanelDefaultPosition,
  bottomMode: SidePanelBottomMode,
): SidePanelPosition {
  return defaultPosition === 'right' ? 'right' : bottomMode;
}

/**
 * The active dock the panel should open with on mount for a workspace:
 *  1. if the user picked a default-opening preference, honor it (a 'bottom' default
 *     resolves through the chosen bottomMode variant);
 *  2. otherwise fall back to the last-used stored position (backward-compatible
 *     sticky behavior for users who never set a default);
 *  3. otherwise the global default ('right').
 */
function readInitialPosition(
  orgId: string | null | undefined,
  bottomMode: SidePanelBottomMode,
): SidePanelPosition {
  const preferred = readStoredDefaultPosition(orgId);
  if (preferred) return resolveDefaultToPosition(preferred, bottomMode);
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

  // Start with the defaults so SSR and the first client render agree (no hydration
  // mismatch); the effect below restores the saved values for the active workspace.
  const [position, setPositionState] = useState<SidePanelPosition>(DEFAULT_SIDE_PANEL_POSITION);
  const [defaultPosition, setDefaultPositionState] = useState<SidePanelDefaultPosition>(DEFAULT_SIDE_PANEL_DEFAULT_POSITION);
  const [bottomMode, setBottomModeState] = useState<SidePanelBottomMode>(DEFAULT_SIDE_PANEL_BOTTOM_MODE);

  // Re-hydrate whenever the active workspace changes (initial mount included, once
  // currentOrgId resolves post-hydration). Deliberately an effect, not a render-time
  // derivation: reading localStorage during render would diverge from the SSR default
  // and trip a hydration mismatch (same reason ThemeProvider restores in an effect).
  useEffect(() => {
    const mode = readStoredBottomMode(currentOrgId);
    const preferredDefault = readStoredDefaultPosition(currentOrgId);
    // Restoring persisted preferences into state on mount / org switch is the one
    // legitimate synchronous setState-in-effect here (SSR-safe hydration, like
    // ThemeProvider); reading localStorage during render would trip a mismatch.
    /* eslint-disable react-hooks/set-state-in-effect */
    setBottomModeState(mode);
    setDefaultPositionState(preferredDefault ?? DEFAULT_SIDE_PANEL_DEFAULT_POSITION);
    setPositionState(readInitialPosition(currentOrgId, mode));
    /* eslint-enable react-hooks/set-state-in-effect */
    // Make the legacy seed STICKY: if the bottomMode bucket is empty but a legacy
    // bottom position is stored, persist the seed now. Otherwise a later dock
    // change (position bucket overwritten to 'right') would silently revert the
    // migrated preference to the default on the next mount.
    try {
      const storedMode = window.localStorage.getItem(bottomModeStorageKey(currentOrgId));
      if (!isSidePanelBottomMode(storedMode)) {
        const legacyPosition = window.localStorage.getItem(storageKey(currentOrgId));
        if (isSidePanelBottomMode(legacyPosition)) {
          window.localStorage.setItem(bottomModeStorageKey(currentOrgId), legacyPosition);
        }
      }
    } catch {
      /* localStorage unavailable - the in-memory seed still applies this session */
    }
  }, [currentOrgId]);

  const setPosition = useCallback((next: SidePanelPosition) => {
    setPositionState(next);
    try {
      window.localStorage.setItem(storageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable (private mode / SSR) - keep the in-memory value */
    }
  }, [currentOrgId]);

  const setDefaultPosition = useCallback((next: SidePanelDefaultPosition) => {
    setDefaultPositionState(next);
    try {
      window.localStorage.setItem(defaultPositionStorageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable - keep the in-memory value */
    }
    // WYSIWYG: apply the newly chosen default to the ACTIVE dock right away so the
    // change is visible without waiting for the next open (a 'bottom' default lands
    // on the current bottomMode variant).
    setPosition(resolveDefaultToPosition(next, bottomMode));
  }, [currentOrgId, bottomMode, setPosition]);

  const setBottomMode = useCallback((next: SidePanelBottomMode) => {
    setBottomModeState(next);
    try {
      window.localStorage.setItem(bottomModeStorageKey(currentOrgId), next);
    } catch {
      /* localStorage unavailable - keep the in-memory value */
    }
    // WYSIWYG: if a bottom dock is currently active, reposition it to the newly
    // chosen variant right away (a 'right' dock is left untouched).
    if (position !== 'right' && position !== next) {
      setPosition(next);
    }
  }, [currentOrgId, position, setPosition]);

  const value = useMemo<SidePanelLayoutContextValue>(
    () => ({ position, setPosition, defaultPosition, setDefaultPosition, bottomMode, setBottomMode }),
    [position, setPosition, defaultPosition, setDefaultPosition, bottomMode, setBottomMode],
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
 * Safe variant - returns the default 'right' layout (and no-op setters) when used
 * outside a provider (e.g. shared-conversation embeds, which always dock right).
 */
export function useSidePanelLayoutSafe(): SidePanelLayoutContextValue {
  const ctx = useContext(SidePanelLayoutContext);
  return ctx ?? {
    position: DEFAULT_SIDE_PANEL_POSITION,
    setPosition: () => {},
    defaultPosition: DEFAULT_SIDE_PANEL_DEFAULT_POSITION,
    setDefaultPosition: () => {},
    bottomMode: DEFAULT_SIDE_PANEL_BOTTOM_MODE,
    setBottomMode: () => {},
  };
}
