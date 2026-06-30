'use client';

import React, { createContext, useContext, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';
import { useMobileDetection } from '@/hooks/useMobileDetection';

// ── Types ──

export interface SidePanelTab {
  id: string;
  label: string;
  icon: ReactNode;
  content: ReactNode;
  /** Optional sub-header rendered below the tab bar when this tab is active (e.g. model selector) */
  subHeader?: ReactNode;
  /** Show shimmer animation on the tab button (e.g. trigger ready) */
  shimmer?: boolean;
  /** Shimmer gradient color (default: blue) */
  shimmerColor?: string;
  /** Preferred width as a fraction of viewport (e.g. 0.5 for 50%). Overrides default 35% when tab is active. */
  preferredWidth?: number;
  /** Pinned tabs cannot be removed by the user (e.g. default AI Chat or Agent tab) */
  pinned?: boolean;
  /** Persistent tabs survive page group navigation changes (unlike regular pinned tabs) */
  persistent?: boolean;
  /** Keep tab content mounted in the DOM even when the panel is closed or another tab is active.
   *  Useful for components that maintain long-lived connections (SSE, WebSockets) or expensive state (ReactFlow canvas). */
  keepMounted?: boolean;
  /** Restrict this tab to specific page sections. List of locale-stripped path patterns
   *  with optional `*` segment wildcards (e.g. ['/app/workflow/...', '/app/marketplace/.../preview']).
   *  When set, the tab is dropped on navigation away from any matching pattern.
   *  Empty/omitted = visible everywhere. */
  scope?: string[];
  /** Inverse of `scope` - drop the tab on pages matching any pattern in this list.
   *  Same wildcard syntax as `scope`. Combine with `scope` for "everywhere except X". */
  excludeScope?: string[];
  /** Optional delete handler - shown in the tab's 3-dot menu for resource tabs */
  onDelete?: () => void;
}

interface SidePanelContextValue {
  // State
  isOpen: boolean;
  tabs: SidePanelTab[];
  activeTabId: string | null;
  /** True when a peek animation should play on mobile (panel has new content but didn't auto-open) */
  isPeeking: boolean;

  // Panel actions
  open(): void;
  close(): void;
  toggle(): void;

  // Tab actions
  addTab(tab: SidePanelTab): void;
  removeTab(tabId: string): void;
  updateTab(tabId: string, updates: Partial<SidePanelTab>): void;
  setActiveTab(tabId: string): void;
  clearTabs(): void;
  /** Reorder a tab from one index to another */
  moveTab(fromIndex: number, toIndex: number): void;

  /** Convenience: addTab + setActiveTab + open in one call */
  openTab(tab: SidePanelTab): void;

  /** Add tab + set active but don't open - triggers peek animation on mobile */
  openTabDeferred(tab: SidePanelTab): void;

  /** Dismiss the peek animation */
  dismissPeek(): void;
}

// ── Context ──

const SidePanelContext = createContext<SidePanelContextValue | null>(null);

/** Throws if used outside SidePanelProvider */
export function useSidePanel(): SidePanelContextValue {
  const ctx = useContext(SidePanelContext);
  if (!ctx) throw new Error('useSidePanel must be used within SidePanelProvider');
  return ctx;
}

/** Returns null if outside SidePanelProvider (safe for optional use) */
export function useSidePanelSafe(): SidePanelContextValue | null {
  return useContext(SidePanelContext);
}

// ── Helpers ──

/**
 * Extract a "page group" from a pathname so sub-route navigation
 * (e.g. /en/app/workflow/abc → /en/app/workflow/abc/run/123)
 * is treated as the same group and doesn't close the panel.
 *
 * Takes the first 4 path segments: /locale/app/section/id
 *
 * Exception - the chat area is ONE group. A new chat lives at /app or /app/chat
 * and, once the first reply is persisted, navigates to /app/c/{id}
 * (useMessageHandlersV2). With the plain 4-segment rule that looks like a
 * cross-group change and auto-closes the side panel on every first message.
 * Collapsing /app, /app/chat and /app/c/* to a single group keeps the panel
 * open across that transition (and across switching conversations). Mirrors the
 * chat tab scope ['/app/c/*', '/app/chat', '/app$'] used in AppHeader.
 *
 * Note: DM (/app/messages) renders chat-like UI but is intentionally its OWN
 * group - it has no new→id URL-freeze, so it is out of scope here.
 */
export function getPageGroup(path: string | null): string {
  if (!path) return '';
  const segments = path.split('/').filter(Boolean);
  // Locate the 'app' segment (segments[0] is the locale, e.g. 'en'/'de'; routes
  // may also be locale-less). The section is whatever follows it.
  const appIdx = segments.indexOf('app');
  if (appIdx !== -1) {
    const section = segments[appIdx + 1]; // undefined (=/app) | 'chat' | 'c' | other
    if (section === undefined || section === 'chat' || section === 'c') {
      return '__chat__';
    }
  }
  return '/' + segments.slice(0, 4).join('/');
}

/**
 * Locales the app supports - used for unambiguous prefix stripping. Keep aligned with
 * the locale list in next-intl config / middleware.
 */
const SUPPORTED_LOCALES = ['en', 'fr', 'es'] as const;
const LOCALE_PREFIX_RE = new RegExp(`^/(${SUPPORTED_LOCALES.join('|')})(?=/|$)`);

/**
 * Strip locale prefix from a pathname (e.g. '/en/app/foo' → '/app/foo').
 * Only strips known locales (en/fr/es) to avoid eating non-locale first segments.
 */
export function stripLocale(pathname: string | null): string {
  if (!pathname) return '';
  return pathname.replace(LOCALE_PREFIX_RE, '') || '/';
}

/**
 * Match a locale-stripped path against a pattern.
 *  - `*` matches exactly one path segment (wildcard).
 *  - Trailing `$` anchors the pattern to an exact match (no descendants).
 *  - Otherwise, patterns are prefix-style (path === pattern OR path startsWith pattern + '/').
 *
 *  Examples:
 *  - '/app/workflow/*'             → '/app/workflow/abc', '/app/workflow/abc/run/x'   (NOT '/app/workflow')
 *  - '/app/marketplace/*\/preview' → '/app/marketplace/abc/preview'                   (NOT '/app/marketplace/abc')
 *  - '/app/settings'               → '/app/settings', '/app/settings/profile'         (prefix)
 *  - '/app$'                       → '/app' only                                      (NOT '/app/anything')
 */
export function pathMatchesPattern(path: string, pattern: string): boolean {
  // Exact match - strip the trailing `$` and require strict equality.
  if (pattern.endsWith('$')) {
    return path === pattern.slice(0, -1);
  }
  if (!pattern.includes('*')) {
    return path === pattern || path.startsWith(pattern + '/');
  }
  const pathSegs = path.split('/').filter(Boolean);
  const patSegs = pattern.split('/').filter(Boolean);
  if (pathSegs.length < patSegs.length) return false;
  for (let i = 0; i < patSegs.length; i++) {
    if (patSegs[i] === '*') continue;
    if (patSegs[i] !== pathSegs[i]) return false;
  }
  return true;
}

/**
 * Convenience: does the locale-stripped pathname match any of the given patterns?
 * Re-uses `pathMatchesPattern` so call-sites never re-implement matching logic.
 */
export function pathMatchesAnyPattern(pathname: string | null, patterns: readonly string[]): boolean {
  if (!patterns || patterns.length === 0) return false;
  const path = stripLocale(pathname);
  return patterns.some(p => pathMatchesPattern(path, p));
}

/**
 * Check whether a tab is allowed on the given pathname based on its `scope` / `excludeScope`.
 * - `scope` (if set): tab is allowed only when at least one pattern matches.
 * - `excludeScope` (if set): tab is dropped when any pattern matches (overrides scope).
 */
function tabMatchesScope(tab: SidePanelTab, pathname: string | null): boolean {
  const path = stripLocale(pathname);
  if (tab.excludeScope && tab.excludeScope.some(p => pathMatchesPattern(path, p))) return false;
  if (!tab.scope || tab.scope.length === 0) return true;
  if (!path) return false;
  return tab.scope.some(p => pathMatchesPattern(path, p));
}

// ── Provider ──

export function SidePanelProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  const [tabs, setTabs] = useState<SidePanelTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const [isPeeking, setIsPeeking] = useState(false);
  const peekTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMobile = useMobileDetection();
  const pathname = usePathname();
  const prevPathnameRef = useRef(pathname);
  // isOpen is read inside the navigation effect (to close the panel on group change),
  // but we don't want it as a dependency - that would re-fire the path-change branch on
  // every open/close. The ref keeps the latest value without participating in deps.
  const isOpenRef = useRef(false);

  // Close panel and clear tabs when navigating to a different page group.
  // Sub-route changes within the same group (e.g. /workflow/abc → /workflow/abc/run/123)
  // keep the panel open so workflow panel persists between edit and run mode.
  useEffect(() => {
    if (prevPathnameRef.current === pathname) return;
    const prevGroup = getPageGroup(prevPathnameRef.current);
    const newGroup = getPageGroup(pathname);
    prevPathnameRef.current = pathname;

    if (prevGroup !== newGroup) {
      // Preserve persistent + pinned tabs across page group changes, BUT drop any
      // scoped tab whose scope no longer matches the new path (e.g. workflow-panel
      // pinned on /app/workflow must not leak to /app/profile).
      setTabs(prev => {
        const kept = prev.filter(t => (t.persistent || t.pinned) && tabMatchesScope(t, pathname));
        // Nested setState: queues an update against post-setTabs state. Safe in React 18.
        setActiveTabId(kept.length > 0 ? kept[0].id : null);
        return kept;
      });
      if (isOpenRef.current) setIsOpen(false);
    } else {
      // Same page-group navigation (e.g. /app/marketplace → /app/marketplace/X/preview):
      // still drop tabs whose scope/excludeScope no longer matches.
      setTabs(prev => {
        const filtered = prev.filter(t => tabMatchesScope(t, pathname));
        if (filtered.length === prev.length) return prev;
        setActiveTabId(prevActive => {
          if (prevActive && filtered.some(t => t.id === prevActive)) return prevActive;
          return filtered.length > 0 ? filtered[0].id : null;
        });
        return filtered;
      });
    }
  }, [pathname]);

  // Keep isOpenRef in sync with isOpen so the navigation effect can read the latest
  // value without re-firing on open/close.
  useEffect(() => {
    isOpenRef.current = isOpen;
  }, [isOpen]);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen(prev => !prev), []);

  const addTab = useCallback((tab: SidePanelTab) => {
    setTabs(prev => {
      const exists = prev.some(t => t.id === tab.id);
      if (exists) {
        // Update existing tab (content/label/icon may have changed)
        return prev.map(t => t.id === tab.id ? { ...t, ...tab } : t);
      }
      return [...prev, tab];
    });
    // Auto-select if no tab is active
    setActiveTabId(prev => prev ?? tab.id);
  }, []);

  const removeTab = useCallback((tabId: string) => {
    setTabs(prev => {
      // Pinned or persistent tabs cannot be removed
      const tab = prev.find(t => t.id === tabId);
      if (tab?.pinned || tab?.persistent) return prev;

      const remaining = prev.filter(t => t.id !== tabId);
      // If we removed the active tab, select the last remaining one
      setActiveTabId(prevActive => {
        if (prevActive !== tabId) return prevActive;
        return remaining.length > 0 ? remaining[remaining.length - 1].id : null;
      });
      return remaining;
    });
  }, []);

  const updateTab = useCallback((tabId: string, updates: Partial<SidePanelTab>) => {
    setTabs(prev => prev.map(t => t.id === tabId ? { ...t, ...updates } : t));
  }, []);

  const clearTabs = useCallback(() => {
    setTabs(prev => {
      const kept = prev.filter(t => t.persistent || t.pinned);
      if (kept.length > 0) {
        setActiveTabId(kept[0].id);
        return kept;
      }
      setActiveTabId(null);
      return [];
    });
  }, []);

  const moveTab = useCallback((fromIndex: number, toIndex: number) => {
    setTabs(prev => {
      if (fromIndex === toIndex) return prev;
      if (fromIndex < 0 || fromIndex >= prev.length) return prev;
      if (toIndex < 0 || toIndex >= prev.length) return prev;
      const next = [...prev];
      const [moved] = next.splice(fromIndex, 1);
      next.splice(toIndex, 0, moved);
      return next;
    });
  }, []);

  const openTab = useCallback((tab: SidePanelTab) => {
    // Add or update the tab
    setTabs(prev => {
      const exists = prev.some(t => t.id === tab.id);
      if (exists) return prev.map(t => t.id === tab.id ? { ...t, ...tab } : t);
      return [...prev, tab];
    });
    setActiveTabId(tab.id);
    setIsOpen(true);
  }, []);

  // Add tab + set active without opening - triggers peek animation on mobile
  const openTabDeferred = useCallback((tab: SidePanelTab) => {
    setTabs(prev => {
      const exists = prev.some(t => t.id === tab.id);
      if (exists) return prev.map(t => t.id === tab.id ? { ...t, ...tab } : t);
      return [...prev, tab];
    });
    setActiveTabId(tab.id);
    // Trigger peek animation
    setIsPeeking(true);
    if (peekTimerRef.current) clearTimeout(peekTimerRef.current);
    peekTimerRef.current = setTimeout(() => {
      setIsPeeking(false);
      peekTimerRef.current = null;
    }, 3000);
  }, []);

  const dismissPeek = useCallback(() => {
    setIsPeeking(false);
    if (peekTimerRef.current) {
      clearTimeout(peekTimerRef.current);
      peekTimerRef.current = null;
    }
  }, []);

  // Cleanup peek timer on unmount
  useEffect(() => {
    return () => {
      if (peekTimerRef.current) clearTimeout(peekTimerRef.current);
    };
  }, []);

  const value = useMemo<SidePanelContextValue>(() => ({
    isOpen,
    tabs,
    activeTabId,
    isPeeking,
    open,
    close,
    toggle,
    addTab,
    removeTab,
    updateTab,
    setActiveTab: setActiveTabId,
    clearTabs,
    moveTab,
    openTab,
    openTabDeferred,
    dismissPeek,
  }), [isOpen, tabs, activeTabId, isPeeking, open, close, toggle, addTab, removeTab, updateTab, setActiveTabId, clearTabs, moveTab, openTab, openTabDeferred, dismissPeek]);

  return (
    <SidePanelContext.Provider value={value}>
      {children}
    </SidePanelContext.Provider>
  );
}
