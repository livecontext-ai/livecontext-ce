'use client';

import { useEffect } from 'react';
import { usePathname } from 'next/navigation';
import { stripLocale } from '@/contexts/SidePanelContext';

/**
 * Which side-panel chat was ON SCREEN when this page was last left by a reload.
 *
 * A reload rebuilds the side panel closed, and that is wrong for the one reload the product
 * causes itself: connecting an OAuth account from a chat in the panel sends the whole page to
 * the provider and back. The conversation that asked for the account is the thing the person
 * was using, and it can only carry on (its credential card approves itself, the agent resumes)
 * once it is on screen again. A plain F5 in the middle of a conversation is the same case.
 *
 * The chat marks itself while it is on screen and unmarks itself when it leaves (panel closed,
 * another tab chosen, another page), and a reload runs no unmount. So what is left in the
 * browser tab's session storage for this page is exactly what was visible when it went away.
 * Keyed by the locale-free path, which is the path the OAuth round trip returns to.
 *
 * Two chats mark themselves: the AI Chat tab, and the chat of the workflow page's own panel. The
 * application page's panel is kept mounted even when hidden, so "mounted" says nothing there about
 * being on screen, and it does not take part: a card raised in that chat is still rebuilt when the
 * reader opens the panel again, it just is not brought back open by itself.
 */
const KEY_PREFIX = 'lc.sidePanel.onScreen:';

function storageKey(pathname: string | null): string | null {
  const path = stripLocale(pathname);
  return path ? `${KEY_PREFIX}${path}` : null;
}

function read(key: string): string | null {
  try {
    return window.sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

/** Mark `tabId` as on screen for the current page while mounted with a non-null id. */
export function useMarkOnScreenAcrossReload(tabId: string | null): void {
  const pathname = usePathname();
  useEffect(() => {
    const key = storageKey(pathname);
    if (!tabId || !key) return;
    try {
      window.sessionStorage.setItem(key, tabId);
    } catch {
      // Storage refused (private mode, quota): the panel simply opens closed after a reload.
    }
    return () => {
      try {
        if (read(key) === tabId) window.sessionStorage.removeItem(key);
      } catch {
        // Same as above.
      }
    };
  }, [tabId, pathname]);
}

/** Whether `tabId` was on screen on this page when it was last left by a reload. */
export function wasOnScreenBeforeReload(tabId: string, pathname: string | null): boolean {
  if (typeof window === 'undefined') return false;
  const key = storageKey(pathname);
  return !!key && read(key) === tabId;
}

/**
 * The same answer, spent: the mark is removed once read. For a caller that registers on every
 * visit of a page, so a mark left by a round trip that never came back (an OAuth connect
 * abandoned at the provider) cannot reopen the chat on a later, ordinary visit. The chat marks
 * itself again as soon as it is back on screen.
 */
export function takeOnScreenBeforeReload(tabId: string, pathname: string | null): boolean {
  if (!wasOnScreenBeforeReload(tabId, pathname)) return false;
  try {
    window.sessionStorage.removeItem(storageKey(pathname)!);
  } catch {
    // Storage refused: the answer still stands for this call.
  }
  return true;
}
