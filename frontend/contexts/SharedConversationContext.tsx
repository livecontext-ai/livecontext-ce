'use client';

import React, { createContext, useContext } from 'react';

interface SharedConversationContextValue {
  /** The public share token - used to authenticate resource fetch calls. */
  token: string;
}

const SharedConversationContext = createContext<SharedConversationContextValue | null>(null);

export function SharedConversationProvider({
  token,
  children,
}: {
  token: string;
  children: React.ReactNode;
}) {
  return (
    <SharedConversationContext.Provider value={{ token }}>
      {children}
    </SharedConversationContext.Provider>
  );
}

/** Returns the share token, or null if not inside a shared conversation page. */
export function useSharedConversation(): SharedConversationContextValue | null {
  return useContext(SharedConversationContext);
}
