'use client';

/**
 * ConversationActivityContext - shared open/closed state for the Conversation
 * Activity card.
 *
 * The toggle button lives in the header (ChatHeader, inside AppHeader) while the
 * card itself renders in the conversation content area (ChatPageLayout). Both sit
 * under the /app layout, so this provider is mounted there to link them without
 * prop-drilling across the two subtrees.
 */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

interface ConversationActivityContextValue {
  /** Whether the activity card is currently shown. */
  isOpen: boolean;
  /** Toggle the card open/closed (drives the header button focus state). */
  toggle: () => void;
  /** Set the open state directly (e.g. close after jumping to a message). */
  setOpen: (open: boolean) => void;
}

const ConversationActivityContext = createContext<ConversationActivityContextValue | null>(null);

// A single GLOBAL preference (not keyed per conversation): once the user opens the
// activity card it stays open across conversations and reloads, and stays closed if
// they close it. Persisted in localStorage.
const STORAGE_KEY = 'lc.conversationActivity.open';

function persistOpen(open: boolean): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, open ? '1' : '0');
  } catch {
    /* localStorage unavailable (private mode / SSR) - ignore */
  }
}

export function ConversationActivityProvider({ children }: { children: React.ReactNode }) {
  // Start closed for a stable SSR/first paint, then restore the saved preference
  // after mount (avoids a hydration mismatch on the persisted value).
  const [isOpen, setIsOpen] = useState(false);
  useEffect(() => {
    try {
      if (window.localStorage.getItem(STORAGE_KEY) === '1') setIsOpen(true);
    } catch {
      /* ignore */
    }
  }, []);

  const toggle = useCallback(() => setIsOpen(prev => { const next = !prev; persistOpen(next); return next; }), []);
  const setOpen = useCallback((open: boolean) => { setIsOpen(open); persistOpen(open); }, []);

  const value = useMemo(() => ({ isOpen, toggle, setOpen }), [isOpen, toggle, setOpen]);

  return (
    <ConversationActivityContext.Provider value={value}>
      {children}
    </ConversationActivityContext.Provider>
  );
}

/**
 * Access the activity-card open state. Returns a no-op fallback when used outside
 * the provider so consumers (header button, card) never crash on isolated mounts
 * (e.g. unit tests rendering a single component).
 */
export function useConversationActivity(): ConversationActivityContextValue {
  const ctx = useContext(ConversationActivityContext);
  if (!ctx) {
    return { isOpen: false, toggle: () => {}, setOpen: () => {} };
  }
  return ctx;
}
